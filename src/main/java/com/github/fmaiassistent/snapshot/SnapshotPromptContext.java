package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.ai.AiPromptContextContributor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Supplies current database identity on every turn without reading FM memory. */
@Service
public class SnapshotPromptContext implements AiPromptContextContributor {
    private final SnapshotStatusService snapshots;

    public SnapshotPromptContext(SnapshotStatusService snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    public String contextFor(String conversationKey) {
        Map<String, Object> snapshot = snapshots.reference();
        return "FM26 database snapshot: " + snapshot;
    }
}
