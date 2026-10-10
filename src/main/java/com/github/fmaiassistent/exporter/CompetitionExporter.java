package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.CompetitionRecordLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompetitionExporter {
    public static final List<String> FIELD_NAMES = List.of("sourceAddress", "name", "nation", "reputation", "gender");

    private CompetitionRecordLayout recordLayout = CompetitionRecordLayout.current();

    public CompetitionExporter() {
    }

    public CompetitionExporter(CompetitionRecordLayout recordLayout) {
        this.recordLayout = recordLayout;
    }

    /**
     * Record layout for the next export. Set once per load; safe because only
     * one refresh runs at a time.
     */
    public void setRecordLayout(CompetitionRecordLayout recordLayout) {
        this.recordLayout = recordLayout;
    }

    public ExportResult exportAllCompetitions(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
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

    Map<String, Object> decodeCompetition(ProcessMemoryReader reader, long competition) throws IOException {
        String name = FmMemoryStrings.objectStringAt(reader, competition, recordLayout.nameRel())
                .or(() -> FmMemoryStrings.competitionDisplayName(reader, competition))
                .orElse("");
        if (name.isBlank()) {
            return Map.of();
        }
        int reputation = reader.readU16(competition + recordLayout.reputationRel());
        if (reputation <= 0 || reputation > 200) {
            return Map.of();
        }
        String nation = reader.qwordOrNull(competition + recordLayout.nationRel())
                .flatMap(value -> FmMemoryStrings.objectStringAt(reader, value, recordLayout.nationNameRelA())
                        .or(() -> FmMemoryStrings.objectStringAt(reader, value, recordLayout.nationNameRelB())))
                .orElse("");
        if (nation.isBlank()) {
            return Map.of();
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceAddress", competition);
        row.put("name", name);
        row.put("nation", nation);
        row.put("reputation", reputation);
        row.put("gender", reader.readU8(competition + recordLayout.genderFlagRel()) == 1 ? "female" : "male");
        return row;
    }

    public record ExportResult(List<Map<String, Object>> rows) {
    }
}
