package com.github.fmaiassistent.snapshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.snapshot")
public record SnapshotProperties(Duration liveProbeTimeout) {
    public SnapshotProperties {
        if (liveProbeTimeout == null || liveProbeTimeout.isNegative() || liveProbeTimeout.isZero()) {
            liveProbeTimeout = Duration.ofSeconds(5);
        }
    }

    public SnapshotProperties() {
        this(Duration.ofSeconds(5));
    }
}
