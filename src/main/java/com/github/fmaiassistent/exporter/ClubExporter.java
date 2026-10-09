package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ClubRecordLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClubExporter {
    public static final List<String> FIELD_NAMES = List.of(
            "sourceAddress", "name", "gender", "competition", "reputation", "nation", "balance", "transferBudget", "payrollBudget",
            "trainingFacilities", "youthFacilities", "youthCoaching", "youthRecruitment", "corporateFacilities");

    private static final List<Integer> CLUB_FINANCE_MARKERS = List.of(0xB318, 0xD2E8);

    private ClubRecordLayout recordLayout = ClubRecordLayout.current();

    public ClubExporter() {
    }

    public ClubExporter(ClubRecordLayout recordLayout) {
        this.recordLayout = recordLayout;
    }

    /**
     * Record layout for the next export. Set once per load; safe because only
     * one refresh runs at a time.
     */
    public void setRecordLayout(ClubRecordLayout recordLayout) {
        this.recordLayout = recordLayout;
    }

    public ExportResult exportAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            FmOffsets.Bounds bounds = FmOffsets.tableBounds(reader, build, gamePluginBase, "TeamOffset");
            PointerTable teams = PointerTable.read(reader, bounds, "Team");
            Map<String, Map<String, Object>> byClub = new LinkedHashMap<>();
            for (int index = 0; index < teams.size(); index++) {
                long team = teams.pointerAt(index);
                if (team <= 0 || team > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                try {
                    Map<String, Object> row = decodeTeamClub(reader, team);
                    if (row.isEmpty()) {
                        continue;
                    }
                    long club = ((Number) row.get("sourceAddress")).longValue();
                    String key = club + ":" + row.get("gender") + ":" + row.get("competition");
                    Map<String, Object> previous = byClub.get(key);
                    if (previous == null || score(row) > score(previous)) {
                        byClub.put(key, row);
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>(byClub.values());
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));
            return new ExportResult(rows);
        }
    }

    private Map<String, Object> decodeTeamClub(ProcessMemoryReader reader, long team) throws IOException {
        var clubOpt = reader.qwordOrNull(team + recordLayout.teamClubRel());
        if (clubOpt.isEmpty()) {
            return Map.of();
        }
        long club = clubOpt.get();
        String name = FmMemoryStrings.clubDisplayName(reader, club).orElse("");
        if (name.isBlank()) {
            return Map.of();
        }
        int reputation = reader.readU16(team + recordLayout.teamReputationRel());
        if (reputation <= 0 || reputation > 10000) {
            return Map.of();
        }
        var competitionOpt = reader.qwordOrNull(team + recordLayout.teamCompetitionRel());
        if (competitionOpt.isEmpty()) {
            return Map.of();
        }
        long competitionAddress = competitionOpt.get();
        String competition = FmMemoryStrings.competitionDisplayName(reader, competitionAddress).orElse("");
        if (competition.isBlank()) {
            return Map.of();
        }
        String nation = FmMemoryStrings.clubNation(reader, club).orElse("");
        String gender = reader.readU8(competitionAddress + recordLayout.competitionGenderFlagRel()) == 1 ? "female" : "male";
        if ("female".equals(gender) && !name.endsWith(" (W)")) {
            name += " (W)";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceAddress", club);
        row.put("_competition_address", competitionAddress);
        row.put("name", name);
        row.put("gender", gender);
        row.put("competition", competition);
        row.put("reputation", reputation);
        row.put("nation", nation);
        Finance finance = readFinance(reader, club);
        row.put("balance", finance.balance());
        row.put("transferBudget", finance.transferBudget());
        row.put("payrollBudget", finance.payrollBudget());
        Facilities facilities = readFacilities(reader, club);
        row.put("trainingFacilities", facilities.training());
        row.put("youthFacilities", facilities.youth());
        row.put("youthCoaching", facilities.coaching());
        row.put("youthRecruitment", facilities.recruitment());
        row.put("corporateFacilities", facilities.corporate());
        return row;
    }

    private static int score(Map<String, Object> row) {
        int score = ((Number) row.getOrDefault("reputation", 0)).intValue();
        if (!String.valueOf(row.getOrDefault("competition", "")).isBlank()) {
            score += 10000;
        }
        return score;
    }

    Finance readFinance(ProcessMemoryReader reader, long club) throws IOException {
        var extraOpt = reader.qwordOrNull(club + recordLayout.clubFinanceBlockRel());
        if (extraOpt.isEmpty()) {
            return new Finance(0L, 0L, 0L);
        }
        long extra = extraOpt.get();
        if (!CLUB_FINANCE_MARKERS.contains(reader.readU16(extra))) {
            return new Finance(0L, 0L, 0L);
        }
        long balanceRaw = reader.readI32(extra + recordLayout.clubBalanceRel());
        long balance = roundToNearest(balanceRaw, balanceRoundingStep(balanceRaw));
        long transferBudgetRaw = reader.readI32(extra + recordLayout.clubTransferBudgetRel());
        long transferBudget = roundToNearest(transferBudgetRaw, transferRoundingStep(transferBudgetRaw));
        long payrollRaw = reader.readI32(extra + recordLayout.clubPayrollBudgetRel());
        long payrollBudget = roundToNearest(payrollRaw, payrollRoundingStep(payrollRaw));
        return new Finance(balance, transferBudget, payrollBudget);
    }

    Facilities readFacilities(ProcessMemoryReader reader, long club) throws IOException {
        int training = 0;
        int youth = 0;
        int coaching = 0;
        int recruitment = 0;
        var facilitiesOpt = reader.qwordOrNull(club + recordLayout.clubFacilitiesBlockRel());
        if (facilitiesOpt.isPresent()) {
            long facilities = facilitiesOpt.get();
            training = facilityRating(reader, facilities + recordLayout.clubTrainingFacilitiesRel());
            youth = facilityRating(reader, facilities + recordLayout.clubYouthFacilitiesRel());
            coaching = facilityRating(reader, facilities + recordLayout.clubYouthCoachingRel());
            recruitment = facilityRating(reader, facilities + recordLayout.clubYouthRecruitmentRel());
        }

        int corporate = 0;
        var extraOpt = reader.qwordOrNull(club + recordLayout.clubFinanceBlockRel());
        if (extraOpt.isPresent()) {
            long extra = extraOpt.get();
            if (CLUB_FINANCE_MARKERS.contains(reader.readU16(extra))) {
                corporate = facilityRating(reader, extra + recordLayout.clubCorporateFacilitiesRel());
            }
        }
        return new Facilities(training, youth, coaching, recruitment, corporate);
    }

    private static int facilityRating(ProcessMemoryReader reader, long address) throws IOException {
        int rating = reader.readU8(address);
        return rating <= 20 ? rating : 0;
    }

    private static long balanceRoundingStep(long value) {
        long abs = Math.abs(value);
        if (abs >= 10_000_000L) {
            return 1_000_000L;
        }
        if (abs >= 1_000_000L) {
            return 100_000L;
        }
        if (abs >= 100_000L) {
            return 25_000L;
        }
        return 1_000L;
    }

    private static long transferRoundingStep(long value) {
        long abs = Math.abs(value);
        if (abs >= 50_000_000L) {
            return 1_000_000L;
        }
        if (abs >= 1_000_000L) {
            return 250_000L;
        }
        if (abs >= 500_000L) {
            return 1_000L;
        }
        if (abs >= 100_000L) {
            return 25_000L;
        }
        return 250L;
    }

    private static long payrollRoundingStep(long value) {
        long abs = Math.abs(value);
        if (abs >= 5_000_000L) {
            return 250_000L;
        }
        if (abs >= 1_000_000L) {
            return 100_000L;
        }
        if (abs >= 100_000L) {
            return 5_000L;
        }
        if (abs >= 50_000L) {
            return 1_000L;
        }
        return 250L;
    }

    private static long roundToNearest(long value, long step) {
        if (value == 0 || step <= 0) {
            return value;
        }
        return Math.round(value / (double) step) * step;
    }

    public record ExportResult(List<Map<String, Object>> rows) {
    }

    record Finance(long balance, long transferBudget, long payrollBudget) {
    }

    record Facilities(int training, int youth, int coaching, int recruitment, int corporate) {
    }
}
