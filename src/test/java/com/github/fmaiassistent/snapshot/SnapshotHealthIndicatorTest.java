package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotHealthIndicatorTest {
    @Test
    void reportsLoadedSnapshotDetails() {
        LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        RefreshCoordinator refreshes = mock(RefreshCoordinator.class);
        when(metadata.findAll()).thenReturn(List.of(
                new LoadMetadataEntity("snapshot_id", "s1"),
                new LoadMetadataEntity("game_date", "2033-06-01")));
        when(refreshes.status()).thenReturn(new RefreshCoordinator.Status(
                RefreshCoordinator.State.IDLE, null, null, null, null));

        var health = new SnapshotHealthIndicator(metadata, refreshes).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("snapshotId", "s1");
    }
}
