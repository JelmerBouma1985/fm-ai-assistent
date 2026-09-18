package com.github.fmaiassistent.snapshot;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SnapshotPromptContextTest {
    @Test
    void checksEveryTurnAndSendsOnlyChangedIdentityOrFreshness() {
        SnapshotStatusService snapshots = mock(SnapshotStatusService.class);
        when(snapshots.reference()).thenReturn(
                Map.of("snapshot_id", "before-load", "freshness", "unverified", "refresh_policy", "verbose"),
                Map.of("snapshot_id", "before-load", "freshness", "unverified", "refresh_policy", "verbose"),
                Map.of("snapshot_id", "before-load", "freshness", "stale", "refresh_policy", "verbose"),
                Map.of("snapshot_id", "after-load", "freshness", "unverified", "refresh_policy", "verbose"));
        SnapshotPromptContext context = new SnapshotPromptContext(snapshots);

        assertThat(context.contextFor("same-conversation")).contains("before-load");
        assertThat(context.contextFor("same-conversation")).isEmpty();
        assertThat(context.contextFor("same-conversation")).contains("stale");
        assertThat(context.contextFor("same-conversation")).contains("after-load");
        verify(snapshots, times(4)).reference();
        verifyNoMoreInteractions(snapshots);
    }
}
