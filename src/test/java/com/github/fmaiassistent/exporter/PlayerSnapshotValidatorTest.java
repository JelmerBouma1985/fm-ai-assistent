package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerSnapshotValidatorTest {
    @Test
    void acceptsValidRowsAndBlanks() {
        Map<String, Object> full = validRow();
        Map<String, Object> sparse = new HashMap<>(Map.of("unique_id", 7L));

        assertThatNoException().isThrownBy(() ->
                PlayerSnapshotValidator.validate(List.of(full, sparse), 2, "2026-09-01"));
    }

    @Test
    void acceptsEmptySnapshotWithoutSlots() {
        assertThatNoException().isThrownBy(() ->
                PlayerSnapshotValidator.validate(List.of(), 0, ""));
    }

    @Test
    void rejectsEmptyPlayersWhenSlotsExist() {
        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(), 5000, "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no players decoded from 5000 people slots");
    }

    @Test
    void skipsRowsWithoutUniqueId() {
        Map<String, Object> row = validRow();
        row.remove("unique_id");

        assertThatNoException().isThrownBy(() ->
                PlayerSnapshotValidator.validate(List.of(row), 1, "2026-09-01"));
    }

    @Test
    void rejectsZeroUniqueId() {
        Map<String, Object> row = validRow();
        row.put("unique_id", 0L);

        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(row), 1, "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid unique_id");
    }

    @Test
    void rejectsOutOfRangeAbility() {
        Map<String, Object> row = validRow();
        row.put("ca", 500);

        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(row), 1, "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ca=500")
                .hasMessageContaining("unique_id=42");
    }

    @Test
    void rejectsImplausibleAge() {
        Map<String, Object> row = validRow();
        row.put("age", 200);

        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(row), 1, "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("age=200");
    }

    @Test
    void rejectsImplausibleHeight() {
        Map<String, Object> row = validRow();
        row.put("height_cm", 300);

        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(row), 1, "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("height_cm=300");
    }

    @Test
    void rejectsImplausibleBirthDate() {
        Map<String, Object> row = validRow();
        row.put("date_of_birth", "2030-01-01");

        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(row), 1, "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("implausible date_of_birth=2030-01-01");
    }

    @Test
    void skipsDateBoundsWithoutGameDate() {
        Map<String, Object> row = validRow();
        row.put("date_of_birth", "2030-01-01");

        assertThatNoException().isThrownBy(() ->
                PlayerSnapshotValidator.validate(List.of(row), 1, ""));
    }

    @Test
    void rejectsMalformedBirthDateWithoutGameDate() {
        Map<String, Object> row = validRow();
        row.put("date_of_birth", "not-a-date");

        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(row), 1, ""))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("malformed date_of_birth=not-a-date");
    }

    @Test
    void acceptsValidStaffRow() {
        assertThatNoException().isThrownBy(() ->
                PlayerSnapshotValidator.validateStaff(List.of(validStaffRow()), "2026-09-01"));
    }

    @Test
    void rejectsDriftedStaffAbility() {
        Map<String, Object> row = validStaffRow();
        row.put("ca", 3500);

        assertThatThrownBy(() -> PlayerSnapshotValidator.validateStaff(List.of(row), "2026-09-01"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ca=3500")
                .hasMessageContaining("Staff snapshot");
    }

    @Test
    void acceptsValidClubRow() {
        assertThatNoException().isThrownBy(() ->
                PlayerSnapshotValidator.validateClubs(List.of(validClubRow())));
    }

    @Test
    void rejectsNegativeClubBudget() {
        Map<String, Object> row = validClubRow();
        row.put("transferBudget", -1L);

        assertThatThrownBy(() -> PlayerSnapshotValidator.validateClubs(List.of(row)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("transferBudget=-1");
    }

    private static Map<String, Object> validRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("unique_id", 42L);
        row.put("ca", 150);
        row.put("pa", 180);
        row.put("age", 24);
        row.put("height_cm", 185);
        row.put("date_of_birth", "2002-03-04");
        return row;
    }

    private static Map<String, Object> validStaffRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("unique_id", 123456L);
        row.put("ca", 145);
        row.put("pa", 160);
        row.put("age", 36);
        row.put("date_of_birth", "1990-04-10");
        return row;
    }

    private static Map<String, Object> validClubRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "Test FC");
        row.put("reputation", 7500);
        row.put("transferBudget", 5_000_000L);
        row.put("payrollBudget", 2_000_000L);
        return row;
    }
}
