package com.github.fmaiassistent.staff;

import com.github.fmaiassistent.domain.entity.StaffEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaffRoleRatingCalculatorTest {
    @Test
    void calculatesTheNineFm26AssignmentsUsingTheReferenceWeights() {
        StaffEntity staff = staffWithAttributes(Map.ofEntries(
                Map.entry("authority", 12), Map.entry("determination", 14), Map.entry("motivating", 16),
                Map.entry("attacking", 18), Map.entry("defending", 10), Map.entry("fitness", 8),
                Map.entry("goalkeeping", 6), Map.entry("possession", 15), Map.entry("technical", 17),
                Map.entry("tactical", 13), Map.entry("set_pieces", 11), Map.entry("tactical_knowledge", 19)));

        assertThat(StaffRoleRatingCalculator.ratings(staff)).hasSize(9);
        var attackingTechnical = StaffRoleRatingCalculator.rating(staff, "attacking_technical").orElseThrow();
        assertThat(attackingTechnical.score20()).isEqualTo(16.2);
        assertThat(attackingTechnical.stars()).isEqualTo(4.5);
        assertThat(attackingTechnical.quality()).isEqualTo("Very Good");
        assertThat(attackingTechnical.attributes()).extracting(StaffRoleRatingCalculator.RoleAttribute::key)
                .containsExactly("attacking", "technical", "authority", "determination", "motivating");
    }

    @Test
    void usesFm26HalfStarRoundingAndQualityThresholds() {
        StaffEntity average = staffWithAttributes(Map.of());
        var rating = StaffRoleRatingCalculator.rating(average, "fitness").orElseThrow();

        assertThat(rating.score20()).isEqualTo(10.0);
        assertThat(rating.stars()).isEqualTo(3.0);
        assertThat(rating.quality()).isEqualTo("Average");
        assertThat(StaffRoleRatingCalculator.quality(20)).isEqualTo("Elite");
        assertThat(StaffRoleRatingCalculator.quality(18)).isEqualTo("Outstanding");
        assertThat(StaffRoleRatingCalculator.quality(15)).isEqualTo("Very Good");
        assertThat(StaffRoleRatingCalculator.quality(12)).isEqualTo("Good");
        assertThat(StaffRoleRatingCalculator.quality(7)).isEqualTo("Competent");
        assertThat(StaffRoleRatingCalculator.quality(4)).isEqualTo("Reasonable");
        assertThat(StaffRoleRatingCalculator.quality(3.9)).isEqualTo("Unsuited");
    }

    @Test
    void possessionTechnicalUsesTechnicalAsTheFm26SpecialistAttribute() {
        StaffEntity staff = staffWithAttributes(Map.of("technical", 20, "possession", 1));

        var rating = StaffRoleRatingCalculator.rating(staff, "possession_technical").orElseThrow();
        assertThat(rating.attributes()).extracting(StaffRoleRatingCalculator.RoleAttribute::key)
                .containsExactly("technical", "authority", "determination", "motivating");
        assertThat(rating.score20()).isEqualTo(16.0);
    }

    private static StaffEntity staffWithAttributes(Map<String, Integer> overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        StaffAttributeDefinitions.ALL.forEach(attribute -> row.put(attribute.key(), 10));
        row.putAll(overrides);
        row.put("unique_id", 1L);
        row.put("name", "Test Coach");
        return StaffEntity.fromExportRow(row);
    }
}
