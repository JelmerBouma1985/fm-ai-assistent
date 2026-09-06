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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SnapshotStatusServiceTest {
    @Test
    void exposesStableSnapshotIdentityWithoutAProcessProbe() {
        LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        DatabaseLoadAllService loader = mock(DatabaseLoadAllService.class);
        RefreshCoordinator refreshes = mock(RefreshCoordinator.class);
        ManagedClubContextService clubs = mock(ManagedClubContextService.class);
        TacticContextService tactics = mock(TacticContextService.class);
        when(metadata.findAll()).thenReturn(List.of(
                new LoadMetadataEntity("snapshot_id", "snapshot-1"),
                new LoadMetadataEntity("game_date", "2029-07-01"),
                new LoadMetadataEntity("loaded_at", "2026-08-22T10:00:00+02:00"),
                new LoadMetadataEntity("players_count", "100000"),
                new LoadMetadataEntity("people_extraction_ms", "1234"),
                new LoadMetadataEntity("quality_people_unreadable", "2")));
        when(clubs.current()).thenReturn(ManagedClubContext.notLoaded(0));
        when(tactics.current()).thenReturn(new TacticContext(0, "No tactic", null, null, List.of(), List.of()));
        when(refreshes.status()).thenReturn(new RefreshCoordinator.Status(
                RefreshCoordinator.State.IDLE, null, null, null, null));
        SnapshotStatusService service = new SnapshotStatusService(metadata, loader, refreshes, clubs, tactics);

        Map<String, Object> status = service.status(false);

        assertThat(status).containsEntry("snapshot_id", "snapshot-1")
                .containsEntry("game_date", "2029-07-01")
                .containsEntry("players", 100000L)
                .containsEntry("live_probe", "not_requested")
                .containsEntry("refresh_state", "idle")
                .containsEntry("people_extraction_ms", 1234L)
                .containsEntry("data_quality", Map.of("people_unreadable", 2L))
                .containsEntry("state", "loaded");
    }

    @Test
    void routesRefreshThroughSharedCoordinator() throws Exception {
        LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        DatabaseLoadAllService loader = mock(DatabaseLoadAllService.class);
        RefreshCoordinator refreshes = mock(RefreshCoordinator.class);
        when(metadata.findAll()).thenReturn(List.of());
        when(refreshes.refreshAndWait(null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null))
                .thenReturn(new DatabaseLoadAllService.LoadAllResult(42, "2034-07-01", 10, 20, 30, 40, "snapshot-2"));
        SnapshotStatusService service = new SnapshotStatusService(metadata, loader, refreshes,
                mock(ManagedClubContextService.class), mock(TacticContextService.class));

        assertThat(service.refresh()).containsEntry("refreshed", true).containsEntry("players", 10L);
        verify(refreshes).refreshAndWait(null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null);
        verifyNoInteractions(loader);
    }
    @Test
    void reusesCurrentAndUnverifiedSnapshotsWithoutExtractingRam() throws Exception {
        for (String freshness : List.of("verified_current", "unverified")) {
            LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
            when(metadata.findAll()).thenReturn(List.of(new LoadMetadataEntity("snapshot_id", "manual-load")));
            RefreshCoordinator refreshes = mock(RefreshCoordinator.class);
            SnapshotStatusService service = org.mockito.Mockito.spy(new SnapshotStatusService(metadata,
                    mock(DatabaseLoadAllService.class), refreshes,
                    mock(ManagedClubContextService.class), mock(TacticContextService.class)));
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            status.put("state", "loaded");
            status.put("stale", freshness.equals("verified_current") ? false : null);
            status.put("snapshot_id", "manual-load");
            org.mockito.Mockito.doReturn(status).when(service).status(true);

            assertThat(service.refresh()).containsEntry("refreshed", false).containsEntry("snapshot", status);
            verifyNoInteractions(refreshes);
        }
    }

    @Test
    void reloadsWhenStaleOrForcedAndDiscardsFreshnessAfterManualLoad() throws Exception {
        for (boolean force : List.of(false, true)) {
            LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
            when(metadata.findAll()).thenReturn(List.of(new LoadMetadataEntity("snapshot_id", "snapshot-1")));
            RefreshCoordinator refreshes = mock(RefreshCoordinator.class);
            when(refreshes.refreshAndWait(null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null))
                    .thenReturn(new DatabaseLoadAllService.LoadAllResult(42, "2034-07-01", 10, 20, 30, 40, "snapshot-1"));
            SnapshotStatusService service = org.mockito.Mockito.spy(new SnapshotStatusService(metadata,
                    mock(DatabaseLoadAllService.class), refreshes,
                    mock(ManagedClubContextService.class), mock(TacticContextService.class)));
            org.mockito.Mockito.doReturn(Map.of("state", "loaded", "stale", true)).when(service).status(true);

            assertThat(service.refresh(force)).containsEntry("refreshed", true);
            verify(refreshes).refreshAndWait(null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null);
            if (force) {
                verify(service, org.mockito.Mockito.never()).status(true);
            }
            assertThat(service.reference()).containsEntry("freshness", "verified_current");
            when(metadata.findAll()).thenReturn(List.of(new LoadMetadataEntity("snapshot_id", "manual-load")));
            assertThat(service.reference()).containsEntry("freshness", "unverified")
                    .containsEntry("stale_reasons", List.of()).containsEntry("refresh_recommended", false);
        }
    }

    @Test
    void unreadableGameKeepsLoadedDataAndDoesNotStartRefresh() throws Exception {
        LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        when(metadata.findAll()).thenReturn(List.of(new LoadMetadataEntity("snapshot_id", "manual-load")));
        DatabaseLoadAllService loader = mock(DatabaseLoadAllService.class);
        when(loader.detectFmPid()).thenThrow(new java.io.IOException("FM unavailable"));
        RefreshCoordinator refreshes = mock(RefreshCoordinator.class);
        when(refreshes.status()).thenReturn(new RefreshCoordinator.Status(
                RefreshCoordinator.State.IDLE, null, null, null, null));
        ManagedClubContextService clubs = mock(ManagedClubContextService.class);
        when(clubs.current()).thenReturn(ManagedClubContext.notLoaded(0));
        TacticContextService tactics = mock(TacticContextService.class);
        when(tactics.current()).thenReturn(new TacticContext(0, "No tactic", null, null, List.of(), List.of()));
        SnapshotStatusService service = new SnapshotStatusService(metadata, loader, refreshes, clubs, tactics);

        assertThat(service.refresh()).containsEntry("refreshed", false)
                .containsEntry("reason", "snapshot_loaded_freshness_unverified");
        assertThat(service.reference()).containsEntry("refresh_recommended", false)
                .containsEntry("stale_reasons", List.of("fm_not_running_or_unreadable"));
        verify(refreshes, org.mockito.Mockito.never()).refreshAndWait(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

}
