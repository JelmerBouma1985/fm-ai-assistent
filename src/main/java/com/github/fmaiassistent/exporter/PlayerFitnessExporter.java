package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enriches the same atomic player snapshot with FM's medical calculations. */
final class PlayerFitnessExporter {
    private PlayerFitnessExporter() { }

    static void capture(ProcessMemoryReader reader, Long moduleBase, List<Map<String, Object>> rows,
            LocalDate gameDate) throws IOException {
        if (reader.platform() != ProcessMemoryReader.Platform.WINDOWS) return;
        long resolvedBase = moduleBase == null
                ? com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader) : moduleBase;
        var identity = com.github.fmaiassistent.memory.PersonIdentityLayout.current();
        var identities = new java.util.ArrayList<byte[]>(rows.size());
        for (var row : rows) {
            long person = Long.decode(String.valueOf(row.get("record")));
            if (!(row.get("index") instanceof Number index) || !(row.get("unique_id") instanceof Number uid))
                throw new PlayerSnapshotValidator.PersonIdentityChangedException("Player fitness identity is absent");
            byte[] prefix = reader.readBytes(person, identity.prefixBytes());
            if (PlayerSnapshotValidator.validatePersonIdentity(index.intValue(), prefix, identity) != uid.longValue())
                throw new PlayerSnapshotValidator.PersonIdentityChangedException("Player fitness identity changed before capture");
            identities.add(prefix);
        }
        try (var cached = new com.github.fmaiassistent.memory.PagedSnapshotMemoryReader(reader, 8192)) {
            captureRows(cached, resolvedBase, rows, gameDate);
        }
        for (int i = 0; i < rows.size(); i++) {
            long person = Long.decode(String.valueOf(rows.get(i).get("record")));
            if (!java.util.Arrays.equals(identities.get(i), reader.readBytes(person, identity.prefixBytes())))
                throw new PlayerSnapshotValidator.PersonIdentityChangedException("Player fitness identity changed during capture");
        }
        if (gameDate != null && !new com.github.fmaiassistent.linux.GameDateFinder()
                .find(reader, 0, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, resolvedBase)
                .filter(gameDate::equals).isPresent())
            throw new PlayerSnapshotValidator.PersonIdentityChangedException("Game date changed during fitness capture");
    }

    private static void captureRows(ProcessMemoryReader reader, long moduleBase,
            List<Map<String, Object>> rows, LocalDate gameDate) throws IOException {
        var matchLoads = new PlayerMatchLoadReader();
        var trainingLoads = new PlayerTrainingLoadReader();
        var injuryRisk = new PlayerInjuryRiskReader();
        var trainingSelection = new PlayerTrainingSelectionReader();
        var medicalStaff = new PlayerMedicalStaffReader();
        var displacements = new HashMap<Long, Integer>();
        for (var row : rows) {
            long person = Long.decode(String.valueOf(row.get("record")));
            long vtable = reader.readU64(person);
            Integer displacement = displacements.get(vtable);
            if (displacement == null) {
                long metadata = reader.readU64(vtable - Long.BYTES);
                displacement = reader.readI32(metadata + Integer.BYTES);
                if (displacement != 0x288 && displacement != 0x380)
                    throw new IOException("Player fitness PERSON subtype anchor failed");
                displacements.put(vtable, displacement);
            }
            long player = person - displacement;
            var load = matchLoads.read(reader, player, moduleBase, gameDate);
            row.put("match_load", load == null ? null : load.label());
            var training = trainingLoads.read(reader, player, moduleBase, gameDate);
            row.put("training_load", training == null ? null : training.label());
            if (gameDate == null || !(row.get("condition") instanceof Number condition)
                    || !(row.get("sharpness") instanceof Number sharpness)
                    || !(row.get("fatigue") instanceof Number fatigue)) continue;
            var susceptibility = injuryRisk.readSusceptibility(reader, player, moduleBase, gameDate,
                    condition.intValue(), fatigue.intValue(), null);
            if (susceptibility.label() == null
                    && "Physician context unavailable".equals(susceptibility.unresolvedReason())) {
                var staff = medicalStaff.defaultStaff(reader, player, moduleBase, gameDate);
                if (staff != null) susceptibility = injuryRisk.readSusceptibility(reader, player, moduleBase,
                        gameDate, condition.intValue(), fatigue.intValue(), staff);
            }
            row.put("injury_susceptibility", susceptibility.label());
            if (load == null) continue;
            Float multiplier = trainingLoads.teamRiskMultiplier(reader, player, moduleBase);
            var overall = injuryRisk.readRisk(reader, player, moduleBase, gameDate, condition.intValue(),
                    sharpness.intValue(), fatigue.intValue(), load.category(), null, null, multiplier);
            if (overall == null) {
                var inputs = trainingLoads.riskInputs(reader, player, moduleBase, gameDate);
                Integer role = inputs == null ? null : trainingLoads.trainingRole(reader, player, moduleBase);
                int[] categories = null;
                if (role != null) {
                    int position = trainingLoads.preferredPositionMask(reader, player, moduleBase, gameDate);
                    Long explicitRole = trainingLoads.preferredAttributeRoleOverride(reader, player, moduleBase, gameDate);
                    var selected = trainingSelection.read(reader, player, moduleBase, position, explicitRole);
                    categories = injuryRisk.readTrainingCategories(reader, player, moduleBase, inputs,
                            role, selected.nativeAttributeIDs());
                }
                overall = injuryRisk.readRisk(reader, player, moduleBase, gameDate, condition.intValue(),
                        sharpness.intValue(), fatigue.intValue(), load.category(), inputs, categories, multiplier);
            }
            row.put("overall_risk", overall == null ? null : overall.label());
        }
    }
}
