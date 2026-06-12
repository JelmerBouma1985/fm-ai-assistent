package com.github.fmaiscout.exporter;

import com.github.fmaiscout.linux.FmMemoryStrings;
import com.github.fmaiscout.linux.FmOffsets;
import com.github.fmaiscout.linux.LinuxProcessReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompetitionExporter {
    public static final List<String> FIELD_NAMES = List.of("sourceAddress", "name", "nation", "reputation", "gender");

    private static final long NAME_REL = 0x40;
    private static final long NATION_REL = 0x60;
    private static final long GENDER_FLAG_REL = 0xF9;
    private static final long REPUTATION_REL = 0x188;

    public ExportResult exportAllCompetitions(int pid, int build, Long gamePluginBase) throws IOException {
        try (LinuxProcessReader reader = new LinuxProcessReader(pid)) {
            FmOffsets.Bounds bounds = FmOffsets.tableBounds(reader, build, gamePluginBase, "CompetitionOffset");
            Map<Long, Map<String, Object>> byCompetition = new LinkedHashMap<>();
            for (long index = 0; index < bounds.count(); index++) {
                long slotAddress = bounds.start() + index * 8;
                var competitionOpt = reader.qwordOrNull(slotAddress);
                if (competitionOpt.isEmpty()) {
                    continue;
                }
                long competition = competitionOpt.get();
                try {
                    Map<String, Object> row = decodeCompetition(reader, competition);
                    if (!row.isEmpty()) {
                        byCompetition.put(competition, row);
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>(byCompetition.values());
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));
            return new ExportResult(rows);
        }
    }

    private Map<String, Object> decodeCompetition(LinuxProcessReader reader, long competition) throws IOException {
        String name = FmMemoryStrings.objectStringAt(reader, competition, NAME_REL)
                .or(() -> FmMemoryStrings.competitionDisplayName(reader, competition))
                .orElse("");
        if (name.isBlank()) {
            return Map.of();
        }
        int reputation = reader.readU16(competition + REPUTATION_REL);
        if (reputation <= 0 || reputation > 200) {
            return Map.of();
        }
        String nation = reader.qwordOrNull(competition + NATION_REL)
                .flatMap(value -> FmMemoryStrings.objectStringAt(reader, value, 0x18)
                        .or(() -> FmMemoryStrings.objectStringAt(reader, value, 0x20)))
                .orElse("");
        if (nation.isBlank()) {
            return Map.of();
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceAddress", competition);
        row.put("name", name);
        row.put("nation", nation);
        row.put("reputation", reputation);
        row.put("gender", reader.readU8(competition + GENDER_FLAG_REL) == 1 ? "female" : "male");
        return row;
    }

    public record ExportResult(List<Map<String, Object>> rows) {
    }
}
