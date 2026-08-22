package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.service.DatabaseLoadAllService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotStatusServiceTest {
    @Test
    void exposesStableSnapshotIdentityWithoutAProcessProbe() {
        LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        DatabaseLoadAllService loader = mock(DatabaseLoadAllService.class);
        ManagedClubContextService clubs = mock(ManagedClubContextService.class);
        TacticContextService tactics = mock(TacticContextService.class);
        when(metadata.findAll()).thenReturn(List.of(
                new LoadMetadataEntity("snapshot_id", "snapshot-1"),
                new LoadMetadataEntity("game_date", "2029-07-01"),
                new LoadMetadataEntity("loaded_at", "2026-08-22T10:00:00+02:00"),
                new LoadMetadataEntity("players_count", "100000")));
        when(clubs.current()).thenReturn(ManagedClubContext.notLoaded(0));
        when(tactics.current()).thenReturn(new TacticContext(0, "No tactic", null, null, List.of(), List.of()));
        SnapshotStatusService service = new SnapshotStatusService(metadata, loader, clubs, tactics);

        Map<String, Object> status = service.status(false);

        assertThat(status).containsEntry("snapshot_id", "snapshot-1")
                .containsEntry("game_date", "2029-07-01")
                .containsEntry("players", 100000L)
                .containsEntry("live_probe", "not_requested")
                .containsEntry("state", "loaded");
    }
}
