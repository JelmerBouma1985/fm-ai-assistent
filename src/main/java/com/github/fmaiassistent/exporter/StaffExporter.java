package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.GameDateFinder;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.staff.StaffAttributeDefinitions;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StaffExporter {
    private static final int UNIQUE_ID_REL = 0x0C;
    private static final int STAFF_CA_REL = 0xDA;
    private static final int STAFF_PA_REL = 0xDC;
    private static final int HOME_REPUTATION_REL = 0xD4;
    private static final int CURRENT_REPUTATION_REL = 0xD6;
    private static final int WORLD_REPUTATION_REL = 0xD8;

    public static final List<String> FIELD_NAMES = buildFieldNames();

    public ExportResult exportAllStaff(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            FmOffsets.Bounds bounds = FmOffsets.peopleBounds(reader, build, gamePluginBase);
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            Set<Long> seenUniqueIds = new LinkedHashSet<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long index = 0; index < bounds.count(); index++) {
                var person = reader.qwordOrNull(bounds.start() + index * Long.BYTES);
                if (person.isEmpty()) {
                    continue;
                }
                try {
                    PersonMemoryClassifier.Classification classification = classifier.classify(person.get());
                    if (!classification.type().hasStandaloneStaffData()) {
                        continue;
                    }
                    Map<String, Object> row = decodeRow(
                            reader, Math.toIntExact(index), person.get(), classification.dynamicOffset());
                    long uniqueId = ((Number) row.get("unique_id")).longValue();
                    int ca = ((Number) row.get("ca")).intValue();
                    int pa = ((Number) row.get("pa")).intValue();
                    String name = String.valueOf(row.get("name"));
                    if (uniqueId <= 0 || name.isBlank() || !validAbility(ca) || !validAbility(pa)
                            || !seenUniqueIds.add(uniqueId)) {
                        continue;
                    }
                    rows.add(row);
                } catch (IOException | RuntimeException ignored) {
                    // FM collections contain several person subtypes. A single unreadable
                    // object must not prevent the rest of the snapshot from loading.
                }
            }
            LocalDate gameDate = new GameDateFinder().find(reader, rows.size(), build, gamePluginBase).orElse(null);
            applyAges(rows, gameDate);
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
            return new ExportResult(gameDate == null ? "" : gameDate.toString(), rows);
        }
    }

    Map<String, Object> decodeRow(ProcessMemoryReader reader, int index, long person, int dynamicOffset) throws IOException {
        long staffBase = person - dynamicOffset;
        byte[] attributes = reader.readBytes(
                staffBase + StaffAttributeDefinitions.ATTRIBUTES_REL,
                StaffAttributeDefinitions.ALL.stream().mapToInt(StaffAttributeDefinitions.StaffAttribute::offset).max().orElseThrow() + 1);
        var contract = reader.qwordOrNull(person + 0xA8);
        var team = contract.flatMap(value -> reader.qwordOrNull(value + 0x10));
        var club = team.flatMap(value -> reader.qwordOrNull(value + 0x30));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("staff_index", index);
        row.put("record_address", "0x" + Long.toHexString(person));
        row.put("unique_id", reader.readU32(person + UNIQUE_ID_REL));
        row.put("name", FmMemoryStrings.playerName(reader, person).orElse(""));
        row.put("gender", (reader.readU8(person + 0x19) & 0x10) != 0 ? "female" : "male");
        row.put("nationality", FmMemoryStrings.playerNationality(reader, person).orElse(""));
        row.put("club", FmMemoryStrings.clubDisplayName(reader, club.orElse(null)).orElse(""));
        club.ifPresent(address -> row.put("_club_address", address));
        row.put("division", division(reader, team.orElse(null)));
        int jobId = contract.map(value -> readU8(reader, value + 0x26, 0)).orElse(0);
        row.put("job_id", jobId);
        row.put("job", jobName(jobId));
        row.put("date_of_birth", date(reader, person + 0x88));
        row.put("age", "");
        row.put("age_as_of", "");
        row.put("salary_weekly_raw", contract.map(value -> readU32(reader, value + 0x20, 0)).orElse(0L));
        row.put("contract_end_date", contract.map(value -> date(reader, value + 0x48)).orElse(""));
        row.put("home_reputation", reader.readU16(staffBase + HOME_REPUTATION_REL));
        row.put("current_reputation", reader.readU16(staffBase + CURRENT_REPUTATION_REL));
        row.put("world_reputation", reader.readU16(staffBase + WORLD_REPUTATION_REL));
        row.put("ca", reader.readU16(staffBase + STAFF_CA_REL));
        row.put("pa", (int) reader.readI16(staffBase + STAFF_PA_REL));
        for (StaffAttributeDefinitions.StaffAttribute attribute : StaffAttributeDefinitions.ALL) {
            row.put(attribute.key(), staffAttribute(attribute, attributes[attribute.offset()] & 0xff));
        }
        return row;
    }

    private static String division(ProcessMemoryReader reader, Long team) {
        if (team == null) {
            return "";
        }
        for (long offset : List.of(0x50L, 0x60L)) {
            var competition = reader.qwordOrNull(team + offset);
            if (competition.isPresent()) {
                var name = FmMemoryStrings.competitionDisplayName(reader, competition.get());
                if (name.isPresent()) {
                    return name.get();
                }
            }
        }
        return "";
    }

    private static int staffAttribute(StaffAttributeDefinitions.StaffAttribute attribute, int raw) {
        int value = attribute.storage() == StaffAttributeDefinitions.Storage.DIRECT
                ? raw : Math.round(raw / 5.0f);
        return Math.max(0, Math.min(20, value));
    }

    private static boolean validAbility(int value) {
        return value > 0 && value <= 200;
    }

    private static int readU8(ProcessMemoryReader reader, long address, int fallback) {
        try {
            return reader.readU8(address);
        } catch (IOException | RuntimeException exception) {
            return fallback;
        }
    }

    private static long readU32(ProcessMemoryReader reader, long address, long fallback) {
        try {
            long value = reader.readU32(address);
            return value == 0xffff_ffffL ? fallback : value;
        } catch (IOException | RuntimeException exception) {
            return fallback;
        }
    }

    private static String date(ProcessMemoryReader reader, long address) {
        try {
            int day = reader.readU16(address);
            int year = reader.readU16(address + 2);
            return GameDateFinder.validDayYear(day, year) ? GameDateFinder.dayYearToDate(day, year).toString() : "";
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private static void applyAges(List<Map<String, Object>> rows, LocalDate gameDate) {
        for (Map<String, Object> row : rows) {
            String dob = String.valueOf(row.get("date_of_birth"));
            if (gameDate == null || dob.isBlank()) {
                continue;
            }
            try {
                LocalDate birthDate = LocalDate.parse(dob);
                int age = gameDate.getYear() - birthDate.getYear();
                if (gameDate.getMonthValue() < birthDate.getMonthValue()
                        || gameDate.getMonthValue() == birthDate.getMonthValue()
                        && gameDate.getDayOfMonth() < birthDate.getDayOfMonth()) age--;
                row.put("age", age);
                row.put("age_as_of", gameDate.toString());
            } catch (DateTimeException ignored) {
            }
        }
    }

    public static String jobName(int jobId) {
        return switch (jobId) {
            case 1 -> "Player";
            case 2 -> "Coach";
            case 3 -> "Player/Coach";
            case 4 -> "Chairperson";
            case 6 -> "Director";
            case 8 -> "Managing Director";
            case 10 -> "Director of Football";
            case 12 -> "Physiotherapist";
            case 14 -> "Scout";
            case 16 -> "Manager";
            case 17 -> "Player/Manager";
            case 20 -> "Assistant Manager";
            case 21 -> "Player/Assistant Manager";
            case 22 -> "Performance Analyst";
            case 24 -> "General Manager";
            case 26 -> "Fitness Coach";
            case 27 -> "Player/Fitness Coach";
            case 34 -> "Goalkeeping Coach";
            case 35 -> "Player/Goalkeeping Coach";
            case 36 -> "Head of Performance Analysis";
            case 38 -> "Club Doctor";
            case 40 -> "Head of Sports Science";
            case 42 -> "Data Analyst";
            case 44 -> "Chief Scout";
            case 45 -> "Player/Chief Scout";
            case 46 -> "Doctor";
            case 48 -> "Sports Scientist";
            case 49 -> "Player/Youth Coach";
            case 50 -> "Head Physiotherapist";
            case 52 -> "Under 19s Manager";
            case 54 -> "First Team Coach";
            case 64 -> "Head of Youth Development";
            case 65 -> "Player/Head of Youth Development";
            case 66 -> "Owner";
            case 70 -> "President";
            case 86 -> "Loan Manager";
            case 88 -> "Technical Director";
            case 144 -> "Interim Manager";
            default -> "Staff Member";
        };
    }

    private static List<String> buildFieldNames() {
        List<String> fields = new ArrayList<>(List.of(
                "staff_index", "record_address", "unique_id", "name", "gender", "nationality",
                "club", "division", "job_id", "job", "date_of_birth", "age", "age_as_of",
                "salary_weekly_raw", "contract_end_date", "home_reputation", "current_reputation",
                "world_reputation", "ca", "pa"));
        StaffAttributeDefinitions.ALL.stream().map(StaffAttributeDefinitions.StaffAttribute::key).forEach(fields::add);
        return List.copyOf(fields);
    }

    public record ExportResult(String gameDate, List<Map<String, Object>> rows) {
    }
}
