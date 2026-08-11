package com.github.fmaiassistent.antigravity;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
class SystemAntigravityProcessLauncher implements AntigravityProcessLauncher {
    @Override
    public AntigravityManagedProcess launch(List<String> command, Path workingDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(false);
        return new SystemManagedProcess(builder.start());
    }

    private static final class SystemManagedProcess implements AntigravityManagedProcess {
        private final Process process;
        private final BufferedReader stdout;
        private final BufferedReader stderr;

        private SystemManagedProcess(Process process) {
            this.process = process;
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
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
        public void terminate(Duration timeout) {
            if (!process.isAlive()) {
                return;
            }
            boolean interrupted = sendInterrupt();
            if (!interrupted) {
                process.destroy();
            }
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

        private boolean sendInterrupt() {
            Path kill = Path.of("/bin/kill");
            if (!Files.isExecutable(kill)) {
                return false;
            }
            try {
                Process signal = new ProcessBuilder(
                        kill.toString(), "-INT", Long.toString(process.pid())).start();
                return signal.waitFor(1, TimeUnit.SECONDS) && signal.exitValue() == 0;
            } catch (IOException ex) {
                return false;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
