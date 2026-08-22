package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.decision.RoleFitService;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.recruitment.RecruitmentCaseService;
import com.github.fmaiassistent.service.ClubDatabaseService;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.github.fmaiassistent.tactic.TacticDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FmDecisionToolsTest {
    @Test
    void analyzesPairedTacticPhasesAndComparesPlayersByUniqueId() {
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        ClubDatabaseService clubs = mock(ClubDatabaseService.class);
        ManagedClubContextService managed = mock(ManagedClubContextService.class);
        TacticContextService tactics = mock(TacticContextService.class);
        RoleFitService fits = mock(RoleFitService.class);
        RecruitmentCaseService cases = mock(RecruitmentCaseService.class);
        SnapshotStatusService snapshots = mock(SnapshotStatusService.class);
        ClubEntity club = club("Test FC");
        PlayerEntity first = player("First", 2002000001L, 140, 160);
        PlayerEntity second = player("Second", 2002000002L, 130, 175);
        when(players.findAllPlayerEntities()).thenReturn(List.of(first, second));
        when(clubs.findAllClubs()).thenReturn(List.of(club));
        when(snapshots.reference()).thenReturn(Map.of("snapshot_id", "snapshot-1"));

        TacticDefinition.TacticSlot slot = new TacticDefinition.TacticSlot(
                1,
                new TacticDefinition.PhaseRole("DC", "Ball-Playing Centre-Back", "Defend"),
                new TacticDefinition.PhaseRole("DC", "Covering Centre-Back", "Cover"));
        TacticDefinition definition = new TacticDefinition("Dual phase", "Custom", "Positive", List.of(slot));
        when(tactics.current()).thenReturn(new TacticContext(
                1, "Dual phase", "test", "markdown", List.of("test.fmf"), List.of(), definition));
        RoleFitService.Fit roleFit = new RoleFitService.Fit(15, List.of("passing:15"), List.of("marking:12"), true);
        RoleFitService.SlotFit slotFit = new RoleFitService.SlotFit(
                1, 18, 15.0, 82, true,
                new RoleFitService.PhaseFit("In Possession", "DC", "Ball-Playing Centre-Back", 18, roleFit, true),
                new RoleFitService.PhaseFit("Out of Possession", "DC", "Covering Centre-Back", 18, roleFit, true));
        when(fits.slotFit(any(), any())).thenReturn(slotFit);
        when(fits.positionScore(any(), anyString())).thenAnswer(invocation ->
                "DC".equals(invocation.getArgument(1)) ? 18 : 0);

        FmDecisionTools tools = new FmDecisionTools(
                players, clubs, managed, tactics, fits, cases, snapshots);

        Map<String, Object> analysis = tools.analyzeSquad("Test FC", 15, 12);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) analysis.get("tactic_slots");
        assertThat(slots).hasSize(1);
        assertThat(slots.getFirst()).containsEntry("coverage", "healthy");
        @SuppressWarnings("unchecked")
        Map<String, Object> inPossession = (Map<String, Object>) slots.getFirst().get("in_possession");
        @SuppressWarnings("unchecked")
        Map<String, Object> outOfPossession = (Map<String, Object>) slots.getFirst().get("out_of_possession");
        assertThat(inPossession)
                .containsEntry("role", "Ball-Playing Centre-Back");
        assertThat(outOfPossession)
                .containsEntry("role", "Covering Centre-Back");

        Map<String, Object> comparison = tools.comparePlayers(
                List.of(2002000001L, 2002000002L), "Test FC", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> compared = (List<Map<String, Object>>) comparison.get("players");
        assertThat(compared).extracting(row -> row.get("player_unique_id"))
                .containsExactlyInAnyOrder(2002000001L, 2002000002L);
        assertThat(compared).allSatisfy(row -> assertThat(row)
                .containsKeys("decision_score", "score_components", "tactic_fit", "development", "risks"));
    }

    @Test
    void plansSquadMovesWithExplicitUnknownsAndKnownQuotes() {
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        ClubDatabaseService clubs = mock(ClubDatabaseService.class);
        ManagedClubContextService managed = mock(ManagedClubContextService.class);
        TacticContextService tactics = mock(TacticContextService.class);
        RoleFitService fits = mock(RoleFitService.class);
        RecruitmentCaseService cases = mock(RecruitmentCaseService.class);
        SnapshotStatusService snapshots = mock(SnapshotStatusService.class);
        ClubEntity club = club("Test FC");
        PlayerEntity outgoing = player("Outgoing", 2002000001L, 125, 135);
        PlayerEntity incoming = player("Incoming", 2002000002L, 145, 175, "Other FC");
        when(players.findAllPlayerEntities()).thenReturn(List.of(outgoing, incoming));
        when(clubs.findAllClubs()).thenReturn(List.of(club));
        when(tactics.current()).thenReturn(new TacticContext(
                0, "No tactic loaded", null, null, List.of(), List.of()));
        when(snapshots.reference()).thenReturn(Map.of("snapshot_id", "snapshot-1"));
        when(fits.positionScore(any(), anyString())).thenReturn(0);
        FmDecisionTools tools = new FmDecisionTools(
                players, clubs, managed, tactics, fits, cases, snapshots);

        Map<String, Object> result = tools.planSquadMoves(
                List.of(2002000002L),
                List.of(2002000001L),
                List.of(
                        new FmDecisionTools.PlayerQuote(2002000002L, 7_000_000L, null),
                        new FmDecisionTools.PlayerQuote(2002000001L, 2_000_000L, null)),
                "Test FC");

        @SuppressWarnings("unchecked")
        Map<String, Object> finances = (Map<String, Object>) result.get("finances");
        assertThat(finances)
                .containsEntry("known_spend", 7_000_000L)
                .containsEntry("known_receipts", 2_000_000L)
                .containsEntry("known_remaining_budget", 5_000_000L)
                .containsEntry("budget_projection_exact", true)
                .containsEntry("wage_projection_exact", false);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).contains("incoming_wage_demands_unknown");
    }

    private static ClubEntity club(String name) {
        Map<String, Object> row = new HashMap<>();
        ClubExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("name", name);
        row.put("gender", "male");
        row.put("reputation", 5_000);
        row.put("transferBudget", 10_000_000L);
        row.put("payrollBudget", 1_000_000L);
        return ClubEntity.fromExportRow(row);
    }

    private static PlayerEntity player(String name, long uniqueId, int ca, int pa) {
        return player(name, uniqueId, ca, pa, "Test FC");
    }

    private static PlayerEntity player(String name, long uniqueId, int ca, int pa, String club) {
        Map<String, Object> row = new HashMap<>();
        PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("unique_id", uniqueId);
        row.put("name", name);
        row.put("gender", "male");
        row.put("club", club);
        row.put("playing_club", club);
        row.put("age", "22");
        row.put("age_as_of", "2029-07-01");
        row.put("ca", ca);
        row.put("pa", pa);
        row.put("DefenderCentral", 18);
        row.put("Professionalism", 15);
        row.put("Determination", 15);
        row.put("Ambition", 15);
        return PlayerEntity.fromExportRow(row);
    }
}
