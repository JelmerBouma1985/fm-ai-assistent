package com.github.fmaiassistent.codex;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
class SystemCodexProcessLauncher implements CodexProcessLauncher {
    @Override
    public CodexManagedProcess launch(List<String> command, Path workingDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false);
        prependExecutableDirectoryToPath(builder, command.getFirst());
        Process process = builder.start();
        return new SystemManagedProcess(process);
    }

    static void prependExecutableDirectoryToPath(ProcessBuilder builder, String executable) {
        Path executablePath = Path.of(executable);
        if (!executablePath.isAbsolute() || executablePath.getParent() == null) {
            return;
        }
        String directory = executablePath.getParent().toString();
        String currentPath = builder.environment().getOrDefault("PATH", "");
        if (List.of(currentPath.split(File.pathSeparator)).contains(directory)) {
            return;
        }
        builder.environment().put("PATH", currentPath.isBlank()
                ? directory
                : directory + File.pathSeparator + currentPath);
    }

    private static final class SystemManagedProcess implements CodexManagedProcess {
        private final Process process;
        private final BufferedReader stdout;
        private final BufferedReader stderr;
        private final BufferedWriter stdin;

        private SystemManagedProcess(Process process) {
            this.process = process;
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public BufferedReader stdout() {
            return stdout;
        }

        @Override
        public BufferedReader stderr() {
            return stderr;
        }

        @Override
        public BufferedWriter stdin() {
            return stdin;
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return process.onExit().thenApply(Process::exitValue);
        }

        @Override
        public void closeInput() throws IOException {
            stdin.close();
        }

        @Override
        public void terminate(Duration timeout) {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
