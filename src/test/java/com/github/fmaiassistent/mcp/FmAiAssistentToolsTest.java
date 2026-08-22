package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.recruitment.RecruitmentCaseService;
import com.github.fmaiassistent.service.ClubDatabaseService;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.shortlist.ShortlistFileService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import com.github.fmaiassistent.web.mapper.PlayerMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FmAiAssistentToolsTest {
    @Test
    void explicitPriceCeilingDoesNotSilentlyShrinkToClubBudget() {
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        ClubDatabaseService clubs = mock(ClubDatabaseService.class);
        RecruitmentCaseService cases = mock(RecruitmentCaseService.class);
        SnapshotStatusService snapshots = mock(SnapshotStatusService.class);
        ClubEntity managingClub = club("Test FC", 5_000, 5_000_000L);
        PlayerEntity candidate = player("Candidate", 2002000001L, "Other FC", 22, 135, 165, 8_000_000L, 18);
        when(players.findAllPlayerEntities()).thenReturn(List.of(candidate));
        when(clubs.findAllClubs()).thenReturn(List.of(managingClub));
        when(cases.byPlayerUniqueId()).thenReturn(Map.of());
        when(snapshots.reference()).thenReturn(Map.of("snapshot_id", "test"));
        FmAiAssistentTools tools = new FmAiAssistentTools(
                players,
                clubs,
                mock(PlayerMapper.class),
                mock(JdbcTemplate.class),
                mock(ShortlistFileService.class),
                cases,
                snapshots);

        Map<String, Object> result = tools.transferShortlist(
                "Test FC", "DC", null, null, 15,
                24, 120, 150, 10_000_000L, null,
                null, null, "low", null, null, null, null, 8);

        assertThat(result).containsKey("snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> criteria = (Map<String, Object>) result.get("criteria");
        assertThat(criteria)
                .containsEntry("max_asking_price", 10_000_000L)
                .containsEntry("club_transfer_budget", 5_000_000L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst()).containsEntry("price_fit", "requires_sales")
                .containsEntry("willingness_source", "heuristic")
                .containsKey("score_components");
    }

    private static ClubEntity club(String name, int reputation, long transferBudget) {
        Map<String, Object> row = new HashMap<>();
        ClubExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("name", name);
        row.put("gender", "male");
        row.put("reputation", reputation);
        row.put("transferBudget", transferBudget);
        row.put("payrollBudget", 1_000_000L);
        return ClubEntity.fromExportRow(row);
    }

    private static PlayerEntity player(
            String name,
            long uniqueId,
            String club,
            int age,
            int ca,
            int pa,
            long askingPrice,
            int defenderCentral) {
        Map<String, Object> row = new HashMap<>();
        PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("unique_id", uniqueId);
        row.put("name", name);
        row.put("gender", "male");
        row.put("club", club);
        row.put("playing_club", club);
        row.put("age", String.valueOf(age));
        row.put("age_as_of", "2029-07-01");
        row.put("ca", ca);
        row.put("pa", pa);
        row.put("asking_price", askingPrice);
        row.put("current_reputation", 4_000);
        row.put("world_reputation", 4_000);
        row.put("DefenderCentral", defenderCentral);
        return PlayerEntity.fromExportRow(row);
    }
}
