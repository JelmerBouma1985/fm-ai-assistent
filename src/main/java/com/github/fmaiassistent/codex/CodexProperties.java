package com.github.fmaiassistent.codex;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.codex")
public record CodexProperties(
        boolean enabled,
        String executable,
        String workingDirectory,
        Duration startupTimeout,
        Duration requestTimeout,
        Duration shutdownTimeout) {

    public CodexProperties {
        executable = executable == null || executable.isBlank() ? "codex" : executable;
        workingDirectory = workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory;
        startupTimeout = positiveOrDefault(startupTimeout, Duration.ofSeconds(20));
        requestTimeout = positiveOrDefault(requestTimeout, Duration.ofSeconds(20));
        shutdownTimeout = positiveOrDefault(shutdownTimeout, Duration.ofSeconds(5));
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
