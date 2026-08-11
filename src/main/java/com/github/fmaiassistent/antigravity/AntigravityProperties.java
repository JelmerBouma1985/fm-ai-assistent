package com.github.fmaiassistent.antigravity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.antigravity")
public record AntigravityProperties(
        boolean enabled,
        String executable,
        String workingDirectory,
        Duration printTimeout,
        Duration shutdownTimeout,
        String model,
        String effort,
        String agent,
        boolean sandbox) {

    public AntigravityProperties {
        executable = blankDefault(executable, "agy");
        workingDirectory = blankDefault(workingDirectory, ".");
        printTimeout = positiveOrDefault(printTimeout, Duration.ofMinutes(15));
        shutdownTimeout = positiveOrDefault(shutdownTimeout, Duration.ofSeconds(5));
        model = blankToNull(model);
        effort = blankToNull(effort);
        agent = blankToNull(agent);
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
