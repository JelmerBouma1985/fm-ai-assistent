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
    void fixtureQueriesUseTheSelectedManagersClub() {
        var snapshots = mock(SnapshotStatusService.class);
        when(snapshots.reference()).thenReturn(Map.of("managed_club", "Feyenoord", "managed_club_state", "available"));
        var fixtures = mock(FixtureRepository.class);
        var cambuur = new com.github.fmaiassistent.domain.entity.FixtureEntity("2026-11-20", "15:00", "League",
                "SC Cambuur", "Opponent A", "Opponent A", "Stadium", true);
        var feyenoord = new com.github.fmaiassistent.domain.entity.FixtureEntity("2026-11-21", "15:00", "League",
                "Opponent B", "Feyenoord", "Opponent B", "Stadium", false);
        when(fixtures.findAllByOrderByKickoffDateAscKickoffTimeAsc()).thenReturn(java.util.List.of(cambuur, feyenoord));
        var tools = new FmFixtureTools(fixtures, mock(ClubRepository.class), mock(PlayerDatabaseService.class), snapshots);
        org.assertj.core.api.Assertions.assertThat(tools.getManagedClubFixtures(null, null).get("count")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(tools.getManagedClubFixtures(null, null).get("fixtures").toString())
                .contains("Opponent B").doesNotContain("Opponent A", "SC Cambuur");
        when(snapshots.reference()).thenReturn(Map.of("managed_club", "SC Cambuur", "managed_club_state", "available"));
        org.assertj.core.api.Assertions.assertThat(tools.getManagedClubFixtures(null, null).get("fixtures").toString())
                .contains("Opponent A").doesNotContain("Feyenoord");
    }
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
