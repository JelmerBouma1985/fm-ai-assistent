package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.FixtureRepository;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import com.github.fmaiassistent.domain.entity.FixtureEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FmFixtureToolsTest {
    @Test
    void oppositionFitnessReachesKeyPlayersAndAvailabilityWithoutInventingUnknownValues() {
        var fixtures = mock(FixtureRepository.class);
        var clubs = mock(ClubRepository.class);
        var players = mock(PlayerDatabaseService.class);
        var snapshots = mock(SnapshotStatusService.class);
        when(fixtures.findAllByOrderByKickoffDateAscKickoffTimeAsc()).thenReturn(List.of(
                new FixtureEntity("2026-11-02", "15:00", "League", "Home", "Away", "Away", "Stadium", true)));
        when(snapshots.reference()).thenReturn(Map.of("game_date", "2026-11-01", "managed_club", "Home"));
        when(clubs.findAll()).thenReturn(List.of());
        when(players.findAllPlayerEntities()).thenReturn(List.of(PlayerEntity.fromExportRow(Map.of(
                "unique_id", 1L, "name", "Opposition player", "playing_club", "Away", "injured", true,
                "condition", 8765, "sharpness", 5432, "fatigue", -200))));
        var result = new FmFixtureTools(fixtures, clubs, players, snapshots).analyzeNextOpposition();
        for (String list : List.of("key_players", "unavailable")) {
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, Object>>) result.get(list);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).containsEntry("condition", 8765).containsEntry("sharpness", 5432)
                    .containsEntry("fatigue", -200).containsEntry("match_load", null).containsEntry("training_load", null)
                    .containsEntry("injury_susceptibility", null).containsEntry("overall_risk", null);
        }
    }

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
