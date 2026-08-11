package com.github.fmaiassistent.antigravity;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
class AntigravityProcessRunner {
    private static final Logger log = LoggerFactory.getLogger(AntigravityProcessRunner.class);
    private static final int MAX_STDERR_CHARS = 16_000;

    private final AntigravityProcessLauncher launcher;
    private final AntigravityStreamParser parser;
    private final AntigravityWorkspaceResolver workspaceResolver;
    private final AntigravityProperties properties;
    private final Map<String, RunningTurn> active = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    AntigravityProcessRunner(
            AntigravityProcessLauncher launcher,
            AntigravityStreamParser parser,
            AntigravityWorkspaceResolver workspaceResolver,
            AntigravityProperties properties) {
        this.launcher = launcher;
        this.parser = parser;
        this.workspaceResolver = workspaceResolver;
        this.properties = properties;
    }

    AntigravityTurnHandle start(
            String turnId,
            List<String> command,
            Consumer<AntigravityStreamEvent> listener) {
        if (stopping) {
            throw new AntigravityException(
                    AntigravityException.Code.PROCESS_START_FAILED, "Antigravity is shutting down");
        }
        Path workingDirectory = workspaceResolver.workingDirectory();
        if (!Files.isDirectory(workingDirectory)) {
            throw new AntigravityException(
                    AntigravityException.Code.PROCESS_START_FAILED,
                    "Antigravity working directory does not exist: " + workingDirectory);
        }
        AntigravityManagedProcess process;
        try {
            process = launcher.launch(command, workingDirectory);
        } catch (IOException ex) {
            throw new AntigravityException(
                    AntigravityException.Code.PROCESS_START_FAILED,
                    "Antigravity CLI could not be started", ex);
        }
        RunningTurn running = new RunningTurn(process);
        active.put(turnId, running);
        log.info("Started Antigravity turn turnId={} pid={} cwd={}", turnId, process.pid(), workingDirectory);

        CompletableFuture<Void> stdoutDone = new CompletableFuture<>();
        CompletableFuture<Void> stderrDone = new CompletableFuture<>();
        Thread.ofVirtual().name("antigravity-stdout-" + turnId).start(() ->
                readStdout(running, listener, stdoutDone));
        Thread.ofVirtual().name("antigravity-stderr-" + turnId).start(() ->
                readStderr(running, stderrDone));

        CompletableFuture<AntigravityProcessResult> completion = process.onExit()
                .thenCompose(exitCode -> CompletableFuture.allOf(stdoutDone, stderrDone)
                        .handle((ignored, readError) -> {
                            active.remove(turnId, running);
                            if (readError != null && !running.cancelled.get()) {
                                throw new AntigravityException(
                                        AntigravityException.Code.STREAM_PARSE_FAILED,
                                        "Could not read Antigravity output", readError);
                            }
                            log.info("Antigravity turn exited turnId={} pid={} exitCode={} cancelled={}",
                                    turnId, process.pid(), exitCode, running.cancelled.get());
                            return new AntigravityProcessResult(
                                    exitCode,
                                    running.stderr.toString(),
                                    running.cancelled.get(),
                                    running.timedOut.get(),
                                    running.resultReceived.get());
                        }));
        CompletableFuture.delayedExecutor(
                        properties.printTimeout().plusSeconds(30).toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> timeout(turnId));
        return new AntigravityTurnHandle(turnId, completion, () -> cancel(turnId));
    }

    private void readStdout(
            RunningTurn running,
            Consumer<AntigravityStreamEvent> listener,
            CompletableFuture<Void> done) {
        try {
            String line;
            while ((line = running.process.stdout().readLine()) != null) {
                try {
                    parser.parse(line).ifPresent(event -> {
                        if (event instanceof AntigravityStreamEvent.Result) {
                            running.resultReceived.set(true);
                        }
                        safelyAccept(listener, event);
                    });
                } catch (AntigravityException ex) {
                    log.warn("Ignoring malformed Antigravity stream line", ex);
                }
            }
            done.complete(null);
        } catch (IOException ex) {
            if (running.cancelled.get() || stopping) {
                done.complete(null);
            } else {
                done.completeExceptionally(ex);
            }
        }
    }

    private void readStderr(RunningTurn running, CompletableFuture<Void> done) {
        try {
            String line;
            while ((line = running.process.stderr().readLine()) != null) {
                appendBounded(running.stderr, line);
                log.debug("Antigravity CLI: {}", line);
            }
            done.complete(null);
        } catch (IOException ex) {
            if (running.cancelled.get() || stopping) {
                done.complete(null);
            } else {
                done.completeExceptionally(ex);
            }
        }
    }

    private static void appendBounded(StringBuilder target, String line) {
        synchronized (target) {
            if (target.length() < MAX_STDERR_CHARS) {
                int remaining = MAX_STDERR_CHARS - target.length();
                target.append(line, 0, Math.min(line.length(), remaining)).append('\n');
            }
        }
    }

    private static void safelyAccept(Consumer<AntigravityStreamEvent> listener, AntigravityStreamEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ex) {
            log.warn("Antigravity event listener failed", ex);
        }
    }

    private void cancel(String turnId) {
        RunningTurn running = active.get(turnId);
        if (running == null || !running.cancelled.compareAndSet(false, true)) {
            return;
        }
        log.info("Stopping Antigravity turn turnId={} pid={}", turnId, running.process.pid());
        running.process.terminate(properties.shutdownTimeout());
    }

    private void timeout(String turnId) {
        RunningTurn running = active.get(turnId);
        if (running == null || !running.timedOut.compareAndSet(false, true)) {
            return;
        }
        log.warn("Antigravity turn exceeded the process timeout turnId={} pid={}", turnId, running.process.pid());
        running.process.terminate(properties.shutdownTimeout());
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        active.keySet().forEach(this::cancel);
        active.clear();
    }

    private static final class RunningTurn {
        private final AntigravityManagedProcess process;
        private final StringBuilder stderr = new StringBuilder();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean resultReceived = new AtomicBoolean();

        private RunningTurn(AntigravityManagedProcess process) {
            this.process = process;
        }
    }
}
