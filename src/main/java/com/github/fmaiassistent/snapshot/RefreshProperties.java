package com.github.fmaiassistent.snapshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.snapshot.refresh")
public record RefreshProperties(Duration timeout, Duration shutdownTimeout) {
    public RefreshProperties {
        timeout = valid(timeout) ? timeout : Duration.ofMinutes(3);
        shutdownTimeout = valid(shutdownTimeout) ? shutdownTimeout : Duration.ofSeconds(5);
    }

    private static boolean valid(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
