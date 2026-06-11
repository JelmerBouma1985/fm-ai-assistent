package com.example.fmgenie26.club;

import com.example.fmgenie26.fm.FmMemoryStrings;
import com.example.fmgenie26.fm.FmOffsets;
import com.example.fmgenie26.linux.LinuxProcessReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClubExporter {
    public static final List<String> FIELD_NAMES = List.of(
            "sourceAddress", "name", "gender", "competition", "reputation", "nation", "balance", "transferBudget", "payrollBudget");

    private static final long TEAM_CLUB_REL = 0x30;
    private static final long TEAM_COMPETITION_REL = 0x50;
    private static final long TEAM_REPUTATION_REL = 0xA8;
    private static final long COMPETITION_GENDER_FLAG_REL = 0xF9;
    private static final long CLUB_FINANCE_BLOCK_REL = 0x150;
    private static final List<Integer> CLUB_FINANCE_MARKERS = List.of(0xB318, 0xD2E8);
    private static final long CLUB_BALANCE_REL = 0x14;
    private static final long CLUB_TRANSFER_BUDGET_REL = 0x7CC;
    private static final long CLUB_PAYROLL_BUDGET_REL = 0x810;

    public ExportResult exportAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        try (LinuxProcessReader reader = new LinuxProcessReader(pid)) {
            FmOffsets.Bounds bounds = FmOffsets.tableBounds(reader, build, gamePluginBase, "TeamOffset");
            Map<String, Map<String, Object>> byClub = new LinkedHashMap<>();
            for (long index = 0; index < bounds.count(); index++) {
                long slotAddress = bounds.start() + index * 8;
                var teamOpt = reader.qwordOrNull(slotAddress);
                if (teamOpt.isEmpty()) {
                    continue;
                }
                long team = teamOpt.get();
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

    private Map<String, Object> decodeTeamClub(LinuxProcessReader reader, long team) throws IOException {
        var clubOpt = reader.qwordOrNull(team + TEAM_CLUB_REL);
        if (clubOpt.isEmpty()) {
            return Map.of();
        }
        long club = clubOpt.get();
        String name = FmMemoryStrings.clubDisplayName(reader, club).orElse("");
        if (name.isBlank()) {
            return Map.of();
        }
        int reputation = reader.readU16(team + TEAM_REPUTATION_REL);
        if (reputation <= 0 || reputation > 10000) {
            return Map.of();
        }
        var competitionOpt = reader.qwordOrNull(team + TEAM_COMPETITION_REL);
        if (competitionOpt.isEmpty()) {
            return Map.of();
        }
        long competitionAddress = competitionOpt.get();
        String competition = FmMemoryStrings.competitionDisplayName(reader, competitionAddress).orElse("");
        if (competition.isBlank()) {
            return Map.of();
        }
        String nation = FmMemoryStrings.clubNation(reader, club).orElse("");
        String gender = reader.readU8(competitionAddress + COMPETITION_GENDER_FLAG_REL) == 1 ? "female" : "male";
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
        return row;
    }

    private static int score(Map<String, Object> row) {
        int score = ((Number) row.getOrDefault("reputation", 0)).intValue();
        if (!String.valueOf(row.getOrDefault("competition", "")).isBlank()) {
            score += 10000;
        }
        return score;
    }

    private static Finance readFinance(LinuxProcessReader reader, long club) throws IOException {
        var extraOpt = reader.qwordOrNull(club + CLUB_FINANCE_BLOCK_REL);
        if (extraOpt.isEmpty()) {
            return new Finance(0L, 0L, 0L);
        }
        long extra = extraOpt.get();
        if (!CLUB_FINANCE_MARKERS.contains(reader.readU16(extra))) {
            return new Finance(0L, 0L, 0L);
        }
        long balanceRaw = (long) (int) reader.readU32(extra + CLUB_BALANCE_REL);
        long balance = roundToNearest(balanceRaw, balanceRoundingStep(balanceRaw));
        long transferBudgetRaw = reader.readU32(extra + CLUB_TRANSFER_BUDGET_REL);
        long transferBudget = roundToNearest(transferBudgetRaw, transferRoundingStep(transferBudgetRaw));
        long payrollRaw = reader.readU32(extra + CLUB_PAYROLL_BUDGET_REL);
        long payrollBudget = roundToNearest(payrollRaw, payrollRoundingStep(payrollRaw));
        return new Finance(balance, transferBudget, payrollBudget);
    }

    private static long balanceRoundingStep(long value) {
        return Math.abs(value) >= 10_000_000L ? 1_000_000L : 100_000L;
    }

    private static long transferRoundingStep(long value) {
        if (value >= 50_000_000L) {
            return 1_000_000L;
        }
        if (value >= 1_000_000L) {
            return 250_000L;
        }
        return 50_000L;
    }

    private static long payrollRoundingStep(long value) {
        if (value >= 5_000_000L) {
            return 250_000L;
        }
        if (value >= 1_000_000L) {
            return 100_000L;
        }
        return 5_000L;
    }

    private static long roundToNearest(long value, long step) {
        if (value <= 0 || step <= 0) {
            return value;
        }
        return Math.round(value / (double) step) * step;
    }

    public record ExportResult(List<Map<String, Object>> rows) {
    }

    private record Finance(long balance, long transferBudget, long payrollBudget) {
    }
}
