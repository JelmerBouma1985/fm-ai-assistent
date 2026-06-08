package com.example.fmgenie26.player;

import com.example.fmgenie26.fm.FmMemoryStrings;
import com.example.fmgenie26.fm.FmOffsets;
import com.example.fmgenie26.linux.LinuxProcessReader;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.fmgenie26.player.AttributeDefinitions.CURRENT_ABILITY_REL;
import static com.example.fmgenie26.player.AttributeDefinitions.CURRENT_REPUTATION_REL;
import static com.example.fmgenie26.player.AttributeDefinitions.DISPLAY_VALUE_REL;
import static com.example.fmgenie26.player.AttributeDefinitions.HIDDEN_DIRECT_FIELDS;
import static com.example.fmgenie26.player.AttributeDefinitions.HISTORY_COPY_SOURCE_REL;
import static com.example.fmgenie26.player.AttributeDefinitions.HOME_REPUTATION_REL;
import static com.example.fmgenie26.player.AttributeDefinitions.POSITION_FIELDS;
import static com.example.fmgenie26.player.AttributeDefinitions.POTENTIAL_ABILITY_REL;
import static com.example.fmgenie26.player.AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET;
import static com.example.fmgenie26.player.AttributeDefinitions.VISIBLE_FIELDS;
import static com.example.fmgenie26.player.AttributeDefinitions.WORLD_REPUTATION_REL;

public class PlayerExporter {
    public static final List<String> FIELD_NAMES = buildFieldNames();

    private final GameDateFinder gameDateFinder = new GameDateFinder();

    public ExportResult exportClub(int pid, String club, int build, Long gamePluginBase) throws IOException {
        ExportResult allPlayers = exportAllPlayers(pid, build, gamePluginBase);
        String target = club.toLowerCase();
        List<Map<String, Object>> rows = allPlayers.rows().stream()
                .filter(row -> String.valueOf(row.get("club")).equalsIgnoreCase(target)
                        || String.valueOf(row.get("playing_club")).equalsIgnoreCase(target))
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()))
                .toList();
        return new ExportResult(allPlayers.gameDate(), rows);
    }

    public ExportResult exportAllPlayers(int pid, int build, Long gamePluginBase) throws IOException {
        try (LinuxProcessReader reader = new LinuxProcessReader(pid)) {
            LocalDate gameDate = gameDateFinder.find(reader).orElse(null);
            FmOffsets.Bounds bounds = FmOffsets.peopleBounds(reader, build, gamePluginBase);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long index = 0; index < bounds.count(); index++) {
                long slotAddress = bounds.start() + index * 8;
                var recordOpt = reader.qwordOrNull(slotAddress);
                if (recordOpt.isEmpty()) {
                    continue;
                }
                long record = recordOpt.get();
                try {
                    var contractedClubAddress = currentClubAddress(reader, record);
                    var playingClubAddress = playingClubAddress(reader, record).or(() -> contractedClubAddress);
                    String contractedClub = contractedClubAddress.flatMap(value -> FmMemoryStrings.clubDisplayName(reader, value)).orElse("");
                    String playingClub = playingClubAddress.flatMap(value -> FmMemoryStrings.clubDisplayName(reader, value)).orElse(contractedClub);
                    Map<String, Object> row = decodeRow(reader, (int) index, record, contractedClub, playingClub, gameDate);
                    contractedClubAddress.ifPresent(value -> row.put("_club_address", value));
                    playingClubAddress.ifPresent(value -> row.put("_playing_club_address", value));
                    int ca = ((Number) row.get("ca")).intValue();
                    int pa = ((Number) row.get("pa")).intValue();
                    if (ca <= 0 || ca > 200 || pa <= 0 || pa > 200) {
                        continue;
                    }
                    rows.add(row);
                } catch (IOException | RuntimeException ignored) {
                }
            }
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));
            return new ExportResult(gameDate == null ? "" : gameDate.toString(), rows);
        }
    }

    public Map<String, Object> decodeRow(LinuxProcessReader reader, int index, long record, String club, LocalDate gameDate) throws IOException {
        String playingClub = FmMemoryStrings.playingClubName(reader, record).orElse(club);
        return decodeRow(reader, index, record, club, playingClub, gameDate);
    }

    public Map<String, Object> decodeRow(
            LinuxProcessReader reader,
            int index,
            long record,
            String club,
            String playingClub,
            LocalDate gameDate) throws IOException {
        int maxSourceOffset = Math.max(
                POSITION_FIELDS.stream().mapToInt(FieldDef::offset).max().orElseThrow(),
                VISIBLE_FIELDS.stream().mapToInt(FieldDef::offset).max().orElseThrow())
                - SOURCE_OBJECT_BASE_OFFSET;
        byte[] data = reader.readBytes(record + HISTORY_COPY_SOURCE_REL, maxSourceOffset + 1);

        String loanClub = !club.isBlank() && !playingClub.equalsIgnoreCase(club) ? playingClub : "";
        LocalDate dob = dateOfBirth(reader, record);
        long displayValue = reader.readU32(record + DISPLAY_VALUE_REL);
        Salary salary = salaryValues(reader, record);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("record", "0x" + Long.toHexString(record));
        row.put("name", FmMemoryStrings.playerName(reader, record).orElse("0x" + Long.toHexString(record)));
        row.put("gender", playerGender(reader, record));
        row.put("nationality", FmMemoryStrings.playerNationality(reader, record).orElse(""));
        row.put("club", club);
        row.put("playing_club", playingClub);
        row.put("loan_club", loanClub);
        row.put("is_loaned_out", loanClub.isBlank() ? "no" : "yes");
        row.put("current_reputation", reader.readU16(record + CURRENT_REPUTATION_REL));
        row.put("home_reputation", reader.readU16(record + HOME_REPUTATION_REL));
        row.put("world_reputation", reader.readU16(record + WORLD_REPUTATION_REL));
        row.put("ca", (int) reader.readI16(record + CURRENT_ABILITY_REL));
        row.put("pa", (int) reader.readI16(record + POTENTIAL_ABILITY_REL));
        row.put("asking_price", AttributeDefinitions.roundObservedAskingPrice(displayValue));
        row.put("asking_price_raw", displayValue);
        row.put("contract_end_date", contractEndDate(reader, record));
        row.put("salary_pa", salary.annualRounded());
        row.put("salary_weekly_raw", salary.weeklyRaw());
        row.put("date_of_birth", dob == null ? "" : dob.toString());
        row.put("age", dob == null || gameDate == null ? "" : ageOn(dob, gameDate));
        row.put("age_as_of", gameDate == null ? "" : gameDate.toString());

        for (FieldDef field : POSITION_FIELDS) {
            int raw = data[field.offset() - SOURCE_OBJECT_BASE_OFFSET] & 0xff;
            row.put(field.name(), AttributeDefinitions.direct20(raw));
        }
        for (FieldDef field : VISIBLE_FIELDS) {
            int raw = data[field.offset() - SOURCE_OBJECT_BASE_OFFSET] & 0xff;
            row.put(field.name(), AttributeDefinitions.norm20(raw));
        }
        for (FieldDef field : HIDDEN_DIRECT_FIELDS) {
            row.put(field.name(), reader.readU8(record + field.offset()));
        }
        return row;
    }

    private static LocalDate dateOfBirth(LinuxProcessReader reader, long record) throws IOException {
        DatePair pair = readDatePair(reader, record + 0x88);
        return GameDateFinder.validDayYear(pair.day(), pair.year()) ? GameDateFinder.dayYearToDate(pair.day(), pair.year()) : null;
    }

    private static String contractEndDate(LinuxProcessReader reader, long record) throws IOException {
        var registration = reader.qwordOrNull(record + 0xA8);
        if (registration.isEmpty()) {
            return "";
        }
        DatePair pair = readDatePair(reader, registration.get() + 0x48);
        return GameDateFinder.validDayYear(pair.day(), pair.year())
                ? GameDateFinder.dayYearToDate(pair.day(), pair.year()).toString()
                : "";
    }

    private static java.util.Optional<Long> currentClubAddress(LinuxProcessReader reader, long record) {
        return reader.qwordOrNull(record + 0xA8)
                .flatMap(registration -> reader.qwordOrNull(registration + 0x10))
                .flatMap(registrationBody -> reader.qwordOrNull(registrationBody + 0x30));
    }

    private static java.util.Optional<Long> playingClubAddress(LinuxProcessReader reader, long record) {
        return reader.qwordOrNull(record - 0x158)
                .flatMap(teamBody -> reader.qwordOrNull(teamBody + 0x30));
    }

    private static String playerGender(LinuxProcessReader reader, long record) throws IOException {
        int value = reader.readU8(record + 0x19);
        return (value & 0x10) != 0 ? "female" : "male";
    }

    private static Salary salaryValues(LinuxProcessReader reader, long record) throws IOException {
        var registration = reader.qwordOrNull(record + 0xA8);
        if (registration.isEmpty()) {
            return new Salary(0, 0);
        }
        long weeklyRaw = reader.readU32(registration.get() + 0x20);
        long annualRaw = weeklyRaw * 52;
        return new Salary(weeklyRaw, Math.round(annualRaw / 1000.0) * 1000);
    }

    private static DatePair readDatePair(LinuxProcessReader reader, long address) throws IOException {
        int day = reader.readU16(address);
        int year = reader.readU16(address + 2);
        return new DatePair(day, year);
    }

    private static int ageOn(LocalDate dob, LocalDate gameDate) {
        int age = gameDate.getYear() - dob.getYear();
        if (gameDate.getMonthValue() < dob.getMonthValue()
                || (gameDate.getMonthValue() == dob.getMonthValue() && gameDate.getDayOfMonth() < dob.getDayOfMonth())) {
            age--;
        }
        return age;
    }

    private static List<String> buildFieldNames() {
        List<String> names = new ArrayList<>(List.of(
                "index", "record", "name", "gender", "nationality", "club", "playing_club", "loan_club", "is_loaned_out",
                "current_reputation", "home_reputation", "world_reputation", "ca", "pa",
                "asking_price", "asking_price_raw", "contract_end_date", "salary_pa",
                "salary_weekly_raw", "date_of_birth", "age", "age_as_of"));
        POSITION_FIELDS.stream().map(FieldDef::name).forEach(names::add);
        VISIBLE_FIELDS.stream().map(FieldDef::name).forEach(names::add);
        HIDDEN_DIRECT_FIELDS.stream().map(FieldDef::name).forEach(names::add);
        return List.copyOf(names);
    }

    public record ExportResult(String gameDate, List<Map<String, Object>> rows) {
    }

    private record DatePair(int day, int year) {
    }

    private record Salary(long weeklyRaw, long annualRounded) {
    }
}
