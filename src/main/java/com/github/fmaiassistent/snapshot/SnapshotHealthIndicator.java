package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.repository.LoadMetadataRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Actuator health component for snapshot availability and refresh state. */
@Component("fmSnapshot")
public final class SnapshotHealthIndicator implements HealthIndicator {
    private final LoadMetadataRepository metadata;
    private final RefreshCoordinator refreshes;

    public SnapshotHealthIndicator(LoadMetadataRepository metadata, RefreshCoordinator refreshes) {
        this.metadata = metadata;
        this.refreshes = refreshes;
    }

    @Override
    public Health health() {
        RefreshCoordinator.Status status = refreshes.status();
        Map<String, String> values = new LinkedHashMap<>();
        metadata.findAll().forEach(row -> values.put(row.getKey(), row.getValue()));
        Health.Builder health = switch (status.state()) {
            case FAILED -> Health.down();
            case IDLE -> values.containsKey("snapshot_id") ? Health.up() : Health.unknown();
            case RUNNING, SUCCEEDED -> Health.up();
        };
        health.withDetail("refreshState", status.state().name().toLowerCase());
        if (values.containsKey("snapshot_id")) {
            health.withDetail("snapshotId", values.get("snapshot_id"));
        }
        if (values.containsKey("game_date")) {
            health.withDetail("gameDate", values.get("game_date"));
        }
        if (status.failure() != null) {
            health.withDetail("failure", status.failure());
        }
        return health.build();
    }
}
