package com.github.fmaiassistent.decision;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.tactic.TacticDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LineupOptimizerServiceTest {
    @Test
    void findsTheGloballyBestUniqueLineupInsteadOfGreedySlotWinners() {
        RoleFitService fits = mock(RoleFitService.class);
        TacticDefinition.TacticSlot firstSlot = slot(1);
        TacticDefinition.TacticSlot secondSlot = slot(2);
        PlayerEntity versatile = player("Versatile", 1001L, 140, false);
        PlayerEntity specialist = player("Specialist", 1002L, 140, false);
        when(fits.slotFit(versatile, firstSlot)).thenReturn(fit(1, 90, 18));
        when(fits.slotFit(versatile, secondSlot)).thenReturn(fit(2, 89, 18));
        when(fits.slotFit(specialist, firstSlot)).thenReturn(fit(1, 88, 18));
        when(fits.slotFit(specialist, secondSlot)).thenReturn(fit(2, 10, 10));
        LineupOptimizerService service = new LineupOptimizerService(fits);

        LineupOptimizerService.Result result = service.optimize(
                List.of(versatile, specialist),
                new TacticDefinition("Test", "Custom", "Positive", List.of(firstSlot, secondSlot)),
                LineupOptimizerService.Constraints.defaults());

        assertThat(result.assignments()).hasSize(2);
        assertThat(result.assignmentFor(1).player().getUniqueId()).isEqualTo(1002L);
        assertThat(result.assignmentFor(2).player().getUniqueId()).isEqualTo(1001L);
        assertThat(result.assignments()).extracting(value -> value.player().getUniqueId()).doesNotHaveDuplicates();
        assertThat(result.totalScore()).isEqualTo(177.0);
        assertThat(result.alternatives().get(1)).anySatisfy(alternative -> {
            assertThat(alternative.player().getUniqueId()).isEqualTo(1001L);
            assertThat(alternative.disruptsAnotherSlot()).isTrue();
            assertThat(alternative.assignedTacticSlot()).isEqualTo(2);
        });
    }

    @Test
    void honorsExplicitInjuredLockButRejectsConflictingUnavailableLock() {
        RoleFitService fits = mock(RoleFitService.class);
        TacticDefinition.TacticSlot slot = slot(1);
        PlayerEntity injured = player("Injured", 2001L, 150, true);
        when(fits.slotFit(injured, slot)).thenReturn(fit(1, 60, 12));
        LineupOptimizerService service = new LineupOptimizerService(fits);
        TacticDefinition tactic = new TacticDefinition("Test", "Custom", "Positive", List.of(slot));

        LineupOptimizerService.Result result = service.optimize(List.of(injured), tactic,
                new LineupOptimizerService.Constraints(15, false, Set.of(),
                        List.of(new LineupOptimizerService.LockedAssignment(1, 2001L)),
                        3, "snapshot", "fingerprint"));

        assertThat(result.assignmentFor(1).player().getUniqueId()).isEqualTo(2001L);
        assertThat(result.assignmentFor(1).locked()).isTrue();
        assertThat(result.warnings()).contains(
                "locked_player_injured:2001",
                "locked_player_below_position_threshold:2001");

        assertThatThrownBy(() -> service.optimize(List.of(injured), tactic,
                new LineupOptimizerService.Constraints(15, false, Set.of(2001L),
                        List.of(new LineupOptimizerService.LockedAssignment(1, 2001L)),
                        3, "snapshot", "fingerprint")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicitly unavailable");
    }

    private static TacticDefinition.TacticSlot slot(int index) {
        return new TacticDefinition.TacticSlot(index,
                new TacticDefinition.PhaseRole("DC", "Ball-Playing Centre-Back", "Defend"),
                new TacticDefinition.PhaseRole("DC", "Covering Centre-Back", "Defend"));
    }

    private static RoleFitService.SlotFit fit(int slot, double overall, int position) {
        RoleFitService.Fit role = new RoleFitService.Fit(15, List.of(), List.of(), true);
        return new RoleFitService.SlotFit(slot, position, 15.0, overall, position >= 15,
                new RoleFitService.PhaseFit("In Possession", "DC", "Ball-Playing Centre-Back", position, role, true),
                new RoleFitService.PhaseFit("Out of Possession", "DC", "Covering Centre-Back", position, role, true));
    }

    private static PlayerEntity player(String name, long uniqueId, int ca, boolean injured) {
        Map<String, Object> row = new HashMap<>();
        PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("unique_id", uniqueId);
        row.put("name", name);
        row.put("ca", ca);
        row.put("injured", injured);
        return PlayerEntity.fromExportRow(row);
    }
}
