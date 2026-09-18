package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.ai.AiPromptContextContributor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Supplies current database identity on every turn without reading FM memory. */
@Service
public class SnapshotPromptContext implements AiPromptContextContributor {
    private final SnapshotStatusService snapshots;
    private final Cache<String, Map<String, Object>> delivered = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterAccess(Duration.ofHours(12)).build();

    public SnapshotPromptContext(SnapshotStatusService snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public String contextFor(String conversationKey) {
        Map<String, Object> snapshot = new LinkedHashMap<>(snapshots.reference());
        snapshot.remove("refresh_policy");
        Map<String, Object> previous = delivered.asMap().put(conversationKey, snapshot);
        return snapshot.equals(previous) ? "" : "FM26 database snapshot: " + snapshot;
    }
}
