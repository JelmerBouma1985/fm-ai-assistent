package com.github.fmaiassistent.copilot;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
class CopilotLoginRunner {
    private static final long LOGIN_TIMEOUT_MINUTES = 10;

    void login(String executable, Path workingDirectory, Map<String, String> environment)
            throws IOException, InterruptedException {
        Process process = command(executable, workingDirectory, environment).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new IOException("GitHub Copilot sign-in timed out. Please try again.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("GitHub Copilot sign-in was not completed. Please try again.");
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
    }

    static ProcessBuilder command(String executable, Path workingDirectory, Map<String, String> environment) {
        ProcessBuilder command = new ProcessBuilder(executable, "login", "--web-flow")
                .directory(workingDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().putAll(environment);
        return command;
    }
}
