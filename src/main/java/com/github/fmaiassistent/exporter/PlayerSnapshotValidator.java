package com.github.fmaiassistent.exporter;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Structural sanity checks on decoded player rows before they replace the
 * snapshot. RAM values are exact, so tight ranges with zero tolerance apply:
 * any single violation means the memory layout drifted and the load aborts,
 * leaving the previous snapshot in place. Blank values are skipped because
 * several fields are legitimately absent for some records.
 */
public final class PlayerSnapshotValidator {
    private PlayerSnapshotValidator() {
    }

    /**
     * Validates decoded player rows. {@code gameDate} is the snapshot's game
     * date and may be blank, in which case date-relative checks are skipped.
     *
     * @throws IOException when any anchor fails
     */
    public static void validate(List<Map<String, Object>> rows, int peopleSlots, String gameDate)
            throws IOException {
        if (rows.isEmpty()) {
            if (peopleSlots > 0) {
                throw new IOException("Player snapshot failed anchor validation: "
                        + "no players decoded from " + peopleSlots + " people slots");
            }
            return;
        }
        LocalDate reference = parseDate(gameDate);
        for (Map<String, Object> row : rows) {
            String identity = "unique_id=" + row.get("unique_id");
            if (row.containsKey("unique_id")) {
                Long uniqueId = asLong(row.get("unique_id"));
                if (uniqueId == null || uniqueId <= 0) {
                    throw new IOException("Player snapshot failed anchor validation: "
                            + "invalid unique_id (" + identity + ")");
                }
            }
            checkRange(row, "ca", 1, 200, identity);
            checkRange(row, "pa", 1, 200, identity);
            checkRange(row, "age", 14, 60, identity);
            checkRange(row, "height_cm", 130, 230, identity);
            checkBirthDate(row, reference, identity);
        }
    }

    private static void checkRange(
            Map<String, Object> row, String field, long min, long max, String identity) throws IOException {
        Long value = asLong(row.get(field));
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IOException("Player snapshot failed anchor validation: "
                    + field + "=" + value + " out of range [" + min + ".." + max + "] (" + identity + ")");
        }
    }

    private static void checkBirthDate(Map<String, Object> row, LocalDate reference, String identity)
            throws IOException {
        Object raw = row.get("date_of_birth");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return;
        }
        LocalDate birthDate = parseDate(String.valueOf(raw));
        if (birthDate == null) {
            throw new IOException("Player snapshot failed anchor validation: "
                    + "malformed date_of_birth=" + raw + " (" + identity + ")");
        }
        if (reference == null) {
            return;
        }
        if (birthDate.isAfter(reference) || birthDate.isBefore(reference.minusYears(70))) {
            throw new IOException("Player snapshot failed anchor validation: "
                    + "implausible date_of_birth=" + raw + " (" + identity + ")");
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException notIsoDate) {
            return null;
        }
    }
}
