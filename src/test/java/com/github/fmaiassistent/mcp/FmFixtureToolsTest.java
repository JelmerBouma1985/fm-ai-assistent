package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.FixtureRepository;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FmFixtureToolsTest {
    @Test
    void nationalJobIsNotReportedAsUnemploymentByClubFixtureTools() {
        SnapshotStatusService snapshots = mock(SnapshotStatusService.class);
        when(snapshots.reference()).thenReturn(Map.of("managed_club_state", "national_team"));
        FmFixtureTools tools = new FmFixtureTools(mock(FixtureRepository.class), mock(ClubRepository.class),
                mock(PlayerDatabaseService.class), snapshots);
        assertThatThrownBy(() -> tools.getManagedClubFixtures(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("national-team job");
        assertThatThrownBy(tools::analyzeNextOpposition)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("National-team fixtures are not loaded");
    }

    @Test
    void unemployedManagerCannotReadStaleFixturesOrAnalyzeOpposition() {
        SnapshotStatusService snapshots = mock(SnapshotStatusService.class);
        when(snapshots.reference()).thenReturn(Map.of("managed_club_state", "unemployed"));
        FixtureRepository fixtures = mock(FixtureRepository.class);
        ClubRepository clubs = mock(ClubRepository.class);
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        FmFixtureTools tools = new FmFixtureTools(fixtures, clubs, players, snapshots);

        assertThatThrownBy(() -> tools.getManagedClubFixtures(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unemployed");
        assertThatThrownBy(tools::analyzeNextOpposition)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unemployed");
        verifyNoInteractions(fixtures, clubs, players);
    }
}
