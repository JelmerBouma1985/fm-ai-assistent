package com.github.fmaiassistent.copilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.copilot")
public record CopilotProperties(
        boolean enabled,
        String executable,
        String workingDirectory,
        Duration startupTimeout,
        Duration shutdownTimeout,
        Duration permissionTimeout,
        String model,
        String reasoningEffort) {

    public CopilotProperties {
        executable = blankDefault(executable, "copilot");
        workingDirectory = blankDefault(workingDirectory, ".");
        startupTimeout = positiveOrDefault(startupTimeout, Duration.ofSeconds(30));
        shutdownTimeout = positiveOrDefault(shutdownTimeout, Duration.ofSeconds(8));
        permissionTimeout = positiveOrDefault(permissionTimeout, Duration.ofMinutes(5));
        model = blankToNull(model);
        reasoningEffort = blankToNull(reasoningEffort);
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
