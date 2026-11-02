package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.domain.entity.FixtureEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.FixtureRepository;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FmFixtureToolsTest {
    @Test
    void oppositionFitnessReachesKeyPlayersAndAvailabilityWithoutInventingUnknownValues() {
        var fixtures = mock(FixtureRepository.class);
        var clubs = mock(ClubRepository.class);
        var players = mock(PlayerDatabaseService.class);
        var snapshots = mock(SnapshotStatusService.class);
        when(fixtures.findAllByOrderByKickoffDateAscKickoffTimeAsc()).thenReturn(List.of(
                new FixtureEntity("2026-11-02", "15:00", "League", "Home", "Away", "Away", "Stadium", true)));
        when(snapshots.reference()).thenReturn(Map.of("game_date", "2026-11-01"));
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
}
