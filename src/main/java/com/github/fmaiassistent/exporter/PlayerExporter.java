package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import com.github.fmaiassistent.linux.GameDateFinder;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.fmaiassistent.player.AttributeDefinitions.CURRENT_ABILITY_REL;
import static com.github.fmaiassistent.player.AttributeDefinitions.CURRENT_REPUTATION_REL;
import static com.github.fmaiassistent.player.AttributeDefinitions.DISPLAY_VALUE_REL;
import static com.github.fmaiassistent.player.AttributeDefinitions.HIDDEN_DIRECT_FIELDS;
import static com.github.fmaiassistent.player.AttributeDefinitions.HISTORY_COPY_SOURCE_REL;
import static com.github.fmaiassistent.player.AttributeDefinitions.HOME_REPUTATION_REL;
import static com.github.fmaiassistent.player.AttributeDefinitions.POSITION_FIELDS;
import static com.github.fmaiassistent.player.AttributeDefinitions.POTENTIAL_ABILITY_REL;
import static com.github.fmaiassistent.player.AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET;
import static com.github.fmaiassistent.player.AttributeDefinitions.VISIBLE_FIELDS;
import static com.github.fmaiassistent.player.AttributeDefinitions.WORLD_REPUTATION_REL;

public class PlayerExporter {
    private static final int HEIGHT_CM_REL = -0x5A;
    private static final int JOINED_CLUB_DATE_REL = -0x38;
    private static final int INJURY_REFERENCE_REL = -0x190;
    private static final int INJURY_REFERENCE_FLAG_REL = -0x18C;
    private static final int TRANSFER_STATUS_REL = 0x57;
    private static final int TRANSFER_AGREED_MARKER_REL = 0x51;
    private static final int FUTURE_TRANSFER_TABLE_REL = 0xD8;
    private static final int FUTURE_TRANSFER_CLUB_REL = 0x30;
    private static final int FUTURE_TRANSFER_DATE_REL = 0x10C;
    private static final int FUTURE_TRANSFER_ACTIVE_REL = 0x100;
    private static final int FUTURE_TRANSFER_SENTINEL_REL = 0x104;
    private static final int DUAL_PLAYER_STAFF_SHIFT = 0xF8;
    private static final int MAX_SOURCE_OFFSET = Math.max(
            POSITION_FIELDS.stream().mapToInt(FieldDef::offset).max().orElseThrow(),
            VISIBLE_FIELDS.stream().mapToInt(FieldDef::offset).max().orElseThrow())
            - SOURCE_OBJECT_BASE_OFFSET;

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
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
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
                    var playingClubAddress = playingClubAddress(reader, record);
                    if (playingClubAddress.isEmpty()) {
                        playingClubAddress = contractedClubAddress;
                    }
                    String contractedClub = contractedClubAddress.flatMap(value -> FmMemoryStrings.clubDisplayName(reader, value)).orElse("");
                    String playingClub = playingClubAddress.flatMap(value -> FmMemoryStrings.clubDisplayName(reader, value)).orElse(contractedClub);
                    if (contractedClub.isBlank() && !playingClub.isBlank() && playingClubAddress.isPresent()) {
                        contractedClubAddress = playingClubAddress;
                        contractedClub = playingClub;
                    }
                    Map<String, Object> row = decodeRow(reader, (int) index, record, contractedClub, playingClub, null);
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
            LocalDate gameDate = gameDateFinder.find(reader, rows.size()).orElse(null);
            applyGameDate(rows, gameDate);
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));
            return new ExportResult(gameDate == null ? "" : gameDate.toString(), rows);
        }
    }

    public Map<String, Object> decodeRow(ProcessMemoryReader reader, int index, long record, String club, LocalDate gameDate) throws IOException {
        String playingClub = FmMemoryStrings.playingClubName(reader, record).orElse(club);
        return decodeRow(reader, index, record, club, playingClub, gameDate);
    }

    public Map<String, Object> decodeRow(
            ProcessMemoryReader reader,
            int index,
            long record,
            String club,
            String playingClub,
            LocalDate gameDate) throws IOException {
        PlayerMemoryLayout layout = playerMemoryLayout(reader, record);
        byte[] data = reader.readBytes(record + layout.historyCopySourceRel(), MAX_SOURCE_OFFSET + 1);

        String loanClub = !club.isBlank() && !playingClub.equalsIgnoreCase(club) ? playingClub : "";
        LocalDate dob = dateOfBirth(reader, record);
        long displayValue = club.isBlank() ? 0L : reader.readU32(record + DISPLAY_VALUE_REL);
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
        row.put("current_reputation", reader.readU16(record + layout.relative(CURRENT_REPUTATION_REL)));
        row.put("home_reputation", reader.readU16(record + layout.relative(HOME_REPUTATION_REL)));
        row.put("world_reputation", reader.readU16(record + layout.relative(WORLD_REPUTATION_REL)));
        row.put("ca", layout.ca());
        row.put("pa", layout.pa());
        row.put("asking_price", AttributeDefinitions.roundObservedAskingPrice(displayValue));
        row.put("asking_price_raw", displayValue);
        row.put("joined_club_date", joinedClubDate(reader, record, club));
        PlayerStatus status = playerStatus(reader, record, layout, club, playingClub, gameDate);
        row.put("transfer_listed", status.transferListed());
        row.put("listed_for_loan", status.listedForLoan());
        row.put("transfer_agreed", status.transferAgreed());
        row.put("future_transfer_club", status.futureTransferClub());
        row.put("future_transfer_date", status.futureTransferDate());
        row.put("injured", status.injured());
        row.put("contract_end_date", contractEndDate(reader, record));
        row.put("salary_pa", salary.annualRounded());
        row.put("salary_weekly_raw", salary.weeklyRaw());
        row.put("date_of_birth", dob == null ? "" : dob.toString());
        row.put("age", dob == null || gameDate == null ? "" : ageOn(dob, gameDate));
        row.put("age_as_of", gameDate == null ? "" : gameDate.toString());
        row.put("height_cm", reader.readU8(record + layout.relative(HEIGHT_CM_REL)));

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

    private static PlayerMemoryLayout playerMemoryLayout(ProcessMemoryReader reader, long record) throws IOException {
        int ca = reader.readI16(record + CURRENT_ABILITY_REL);
        int pa = reader.readI16(record + POTENTIAL_ABILITY_REL);
        if (validAbility(ca) && validAbility(pa)) {
            return new PlayerMemoryLayout(0, HISTORY_COPY_SOURCE_REL, ca, pa);
        }

        int alternateCa = reader.readI16(record + CURRENT_ABILITY_REL - DUAL_PLAYER_STAFF_SHIFT);
        int alternatePa = reader.readI16(record + POTENTIAL_ABILITY_REL - DUAL_PLAYER_STAFF_SHIFT);
        int alternateSourceRel = HISTORY_COPY_SOURCE_REL - DUAL_PLAYER_STAFF_SHIFT;
        if (validAbility(alternateCa) && validAbility(alternatePa) && plausiblePlayerBlock(reader, record + alternateSourceRel)) {
            return new PlayerMemoryLayout(-DUAL_PLAYER_STAFF_SHIFT, alternateSourceRel, alternateCa, alternatePa);
        }
        return new PlayerMemoryLayout(0, HISTORY_COPY_SOURCE_REL, ca, pa);
    }

    private static boolean validAbility(int value) {
        return value > 0 && value <= 200;
    }

    private static boolean plausiblePlayerBlock(ProcessMemoryReader reader, long sourceAddress) {
        try {
            byte[] data = reader.readBytes(sourceAddress, MAX_SOURCE_OFFSET + 1);
            long plausiblePositions = POSITION_FIELDS.stream()
                    .mapToInt(field -> data[field.offset() - SOURCE_OBJECT_BASE_OFFSET] & 0xff)
                    .filter(value -> value <= 20)
                    .count();
            long plausibleAttributes = VISIBLE_FIELDS.stream()
                    .mapToInt(field -> data[field.offset() - SOURCE_OBJECT_BASE_OFFSET] & 0xff)
                    .filter(value -> value >= 1 && value <= 100)
                    .count();
            return plausiblePositions >= 12 && plausibleAttributes >= 25;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static LocalDate dateOfBirth(ProcessMemoryReader reader, long record) throws IOException {
        DatePair pair = readDatePair(reader, record + 0x88);
        return GameDateFinder.validDayYear(pair.day(), pair.year()) ? GameDateFinder.dayYearToDate(pair.day(), pair.year()) : null;
    }

    private static String joinedClubDate(ProcessMemoryReader reader, long record, String club) throws IOException {
        if (club == null || club.isBlank()) {
            return "";
        }
        var registration = reader.qwordOrNull(record + 0xA8);
        if (registration.isPresent()) {
            String registrationDate = validDate(reader, registration.get() + 0x4C);
            if (!registrationDate.isBlank()) {
                return registrationDate;
            }
        }
        return validDate(reader, record + JOINED_CLUB_DATE_REL);
    }

    private static String validDate(ProcessMemoryReader reader, long address) throws IOException {
        DatePair pair = readDatePair(reader, address);
        return GameDateFinder.validDayYear(pair.day(), pair.year())
                ? GameDateFinder.dayYearToDate(pair.day(), pair.year()).toString()
                : "";
    }

    private static String contractEndDate(ProcessMemoryReader reader, long record) throws IOException {
        var registration = reader.qwordOrNull(record + 0xA8);
        if (registration.isEmpty()) {
            return "";
        }
        DatePair pair = readDatePair(reader, registration.get() + 0x48);
        return GameDateFinder.validDayYear(pair.day(), pair.year())
                ? GameDateFinder.dayYearToDate(pair.day(), pair.year()).toString()
                : "";
    }

    private static PlayerStatus playerStatus(
            ProcessMemoryReader reader,
            long record,
            PlayerMemoryLayout layout,
            String club,
            String playingClub,
            LocalDate gameDate) throws IOException {
        var registrationOpt = reader.qwordOrNull(record + 0xA8);
        int transferStatus = registrationOpt
                .map(registration -> {
                    try {
                        return reader.readU8(registration + TRANSFER_STATUS_REL);
                    } catch (IOException ex) {
                        return 0;
                    }
                })
                .orElse(0);
        FutureTransfer futureTransfer = registrationOpt
                .map(registration -> futureTransfer(reader, registration, club, playingClub, gameDate))
                .orElse(new FutureTransfer(false, "", ""));
        long injuryReference = reader.readU64(record + INJURY_REFERENCE_REL);
        long injuryReferenceFlag = reader.readU32(record + INJURY_REFERENCE_FLAG_REL);
        boolean injured = injuryReference != 0
                && injuryReferenceFlag == 1
                && reader.qwordOrNull(injuryReference).isPresent();
        return new PlayerStatus(
                transferStatus == 1,
                transferStatus == 2,
                futureTransfer.transferAgreed(),
                futureTransfer.club(),
                futureTransfer.date(),
                injured);
    }

    private static FutureTransfer futureTransfer(
            ProcessMemoryReader reader,
            long registration,
            String club,
            String playingClub,
            LocalDate gameDate) {
        try {
            if (reader.readU8(registration + TRANSFER_AGREED_MARKER_REL) == 0
                    || reader.readI32(registration + FUTURE_TRANSFER_ACTIVE_REL) != 0
                    || reader.readI32(registration + FUTURE_TRANSFER_SENTINEL_REL) != -1) {
                return new FutureTransfer(false, "", "");
            }
            String futureClub = reader.qwordOrNull(registration + FUTURE_TRANSFER_TABLE_REL)
                    .flatMap(table -> reader.qwordOrNull(table + FUTURE_TRANSFER_CLUB_REL))
                    .flatMap(futureClubAddress -> FmMemoryStrings.clubDisplayName(reader, futureClubAddress))
                    .orElse("");
            if (futureClub.isBlank()
                    || futureClub.equalsIgnoreCase(club == null ? "" : club)
                    || futureClub.equalsIgnoreCase(playingClub == null ? "" : playingClub)) {
                return new FutureTransfer(false, "", "");
            }
            DatePair pair = readDatePair(reader, registration + FUTURE_TRANSFER_DATE_REL);
            if (!GameDateFinder.validDayYear(pair.day(), pair.year())) {
                return new FutureTransfer(false, "", "");
            }
            LocalDate date = GameDateFinder.dayYearToDate(pair.day(), pair.year());
            boolean agreed = gameDate != null && date.isAfter(gameDate);
            return new FutureTransfer(agreed, futureClub, date.toString());
        } catch (IOException | RuntimeException ex) {
            return new FutureTransfer(false, "", "");
        }
    }

    private static java.util.Optional<Long> currentClubAddress(ProcessMemoryReader reader, long record) {
        return reader.qwordOrNull(record + 0xA8)
                .flatMap(registration -> reader.qwordOrNull(registration + 0x10))
                .flatMap(registrationBody -> reader.qwordOrNull(registrationBody + 0x30));
    }

    private static java.util.Optional<Long> playingClubAddress(ProcessMemoryReader reader, long record) {
        return reader.qwordOrNull(record - 0x158)
                .flatMap(teamBody -> reader.qwordOrNull(teamBody + 0x30));
    }

    private static String playerGender(ProcessMemoryReader reader, long record) throws IOException {
        int value = reader.readU8(record + 0x19);
        return (value & 0x10) != 0 ? "female" : "male";
    }

    private static Salary salaryValues(ProcessMemoryReader reader, long record) throws IOException {
        var registration = reader.qwordOrNull(record + 0xA8);
        if (registration.isEmpty()) {
            return new Salary(0, 0);
        }
        long weeklyRaw = reader.readU32(registration.get() + 0x20);
        long annualRaw = weeklyRaw * 52;
        return new Salary(weeklyRaw, Math.round(annualRaw / 1000.0) * 1000);
    }

    private static DatePair readDatePair(ProcessMemoryReader reader, long address) throws IOException {
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

    private static void applyGameDate(List<Map<String, Object>> rows, LocalDate gameDate) {
        for (Map<String, Object> row : rows) {
            String dobValue = String.valueOf(row.getOrDefault("date_of_birth", ""));
            if (gameDate == null || dobValue.isBlank()) {
                row.put("age", "");
                row.put("age_as_of", "");
            } else {
                try {
                    row.put("age", ageOn(LocalDate.parse(dobValue), gameDate));
                    row.put("age_as_of", gameDate.toString());
                } catch (DateTimeException ex) {
                    row.put("age", "");
                    row.put("age_as_of", "");
                }
            }
            String futureTransferDate = String.valueOf(row.getOrDefault("future_transfer_date", ""));
            if (gameDate == null || futureTransferDate.isBlank()) {
                row.put("transfer_agreed", false);
                continue;
            }
            try {
                row.put("transfer_agreed", LocalDate.parse(futureTransferDate).isAfter(gameDate));
            } catch (DateTimeException ex) {
                row.put("transfer_agreed", false);
            }
        }
    }

    private static List<String> buildFieldNames() {
        List<String> names = new ArrayList<>(List.of(
                "index", "record", "name", "gender", "nationality", "club", "playing_club", "loan_club", "is_loaned_out",
                "current_reputation", "home_reputation", "world_reputation", "ca", "pa",
                "asking_price", "asking_price_raw", "joined_club_date", "transfer_listed", "listed_for_loan",
                "transfer_agreed", "future_transfer_club", "future_transfer_date", "injured", "contract_end_date", "salary_pa",
                "salary_weekly_raw", "date_of_birth", "age", "age_as_of", "height_cm"));
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

    private record PlayerStatus(
            boolean transferListed,
            boolean listedForLoan,
            boolean transferAgreed,
            String futureTransferClub,
            String futureTransferDate,
            boolean injured) {
    }

    private record FutureTransfer(boolean transferAgreed, String club, String date) {
    }

    private record PlayerMemoryLayout(int recordRelShift, int historyCopySourceRel, int ca, int pa) {
        private int relative(int rel) {
            return rel + recordRelShift;
        }
    }
}
