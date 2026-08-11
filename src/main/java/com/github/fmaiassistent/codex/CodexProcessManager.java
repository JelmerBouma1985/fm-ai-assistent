package com.github.fmaiassistent.codex;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
class CodexProcessManager {
    private static final Logger log = LoggerFactory.getLogger(CodexProcessManager.class);

    private final CodexProperties properties;
    private final CodexProcessLauncher launcher;
    private final CodexWorkspaceResolver workspaceResolver;
    private final CodexExecutableResolver executableResolver;
    private CodexManagedProcess process;
    private volatile boolean stopping;

    CodexProcessManager(
            CodexProperties properties,
            CodexProcessLauncher launcher,
            CodexWorkspaceResolver workspaceResolver,
            CodexExecutableResolver executableResolver) {
        this.properties = properties;
        this.launcher = launcher;
        this.workspaceResolver = workspaceResolver;
        this.executableResolver = executableResolver;
    }

    synchronized CodexManagedProcess start() {
        stopping = false;
        if (!properties.enabled()) {
            throw new CodexException("Codex integration is disabled");
        }
        if (process != null && process.isAlive()) {
            return process;
        }
        Path workingDirectory = workspaceResolver.workingDirectory();
        if (!Files.isDirectory(workingDirectory)) {
            throw new CodexException("Codex working directory does not exist: " + workingDirectory);
        }
        try {
            String executable = executableResolver.resolve();
            process = launcher.launch(List.of(executable, "app-server", "--stdio"), workingDirectory);
        } catch (IOException ex) {
            throw new CodexException("Codex is not installed or could not be started. Make sure `"
                    + properties.executable() + "` is available on PATH.", ex);
        }
        CodexManagedProcess started = process;
        log.info("Started Codex app-server process pid={} cwd={}", started.pid(), workingDirectory);
        Thread.ofVirtual().name("codex-app-server-stderr").start(() -> readStderr(started));
        return started;
    }

    private void readStderr(CodexManagedProcess running) {
        try {
            String line;
            while ((line = running.stderr().readLine()) != null) {
                log.warn("Codex app-server: {}", line);
            }
        } catch (IOException ex) {
            if (running.isAlive()) {
                log.warn("Could not read Codex app-server stderr", ex);
            }
        }
    }

    synchronized boolean isAlive() {
        return process != null && process.isAlive();
    }

    boolean isStopping() {
        return stopping;
    }

    @PreDestroy
    synchronized void stop() {
        stopping = true;
        if (process == null) {
            return;
        }
        try {
            process.closeInput();
        } catch (IOException ex) {
            log.debug("Could not close Codex app-server stdin", ex);
        }
        process.terminate(properties.shutdownTimeout());
        log.info("Stopped Codex app-server process pid={}", process.pid());
        process = null;
    }
}
