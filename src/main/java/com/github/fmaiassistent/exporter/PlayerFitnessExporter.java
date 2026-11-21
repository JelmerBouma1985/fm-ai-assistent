package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enriches the same atomic player snapshot with FM's medical calculations. */
final class PlayerFitnessExporter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlayerFitnessExporter.class);
    private static final List<String> CALCULATED_FIELDS = List.of(
            "match_load", "training_load", "injury_susceptibility", "overall_risk");
    private PlayerFitnessExporter() { }

    static void capture(ProcessMemoryReader reader, Long moduleBase, List<Map<String, Object>> rows,
            LocalDate gameDate) throws IOException {
        if (!reader.platform().supportsGameMemory() || rows.isEmpty()) return;
        long resolvedBase;
        try {
            resolvedBase = moduleBase == null
                    ? com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader) : moduleBase;
        } catch (IOException | RuntimeException unavailable) {
            if (Thread.currentThread().isInterrupted() || unavailable instanceof java.util.concurrent.CancellationException)
                throw unavailable;
            discardCalculatedFields(rows, unavailable);
            return;
        }
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
        try {
            String profile = com.github.fmaiassistent.linux.FmOffsets.moduleProfile(reader, resolvedBase);
            if (!com.github.fmaiassistent.memory.NativeModuleProfile.supportsFitness(profile)) {
                log.warn("FM fitness calculations are unavailable: the loaded game image has no verified medical/training layout");
                return;
            }
            try (var cached = new com.github.fmaiassistent.memory.PagedSnapshotMemoryReader(reader, 8192)) {
                captureRows(cached, resolvedBase, rows, gameDate, profile);
            }
        } catch (PlayerSnapshotValidator.PersonIdentityChangedException changed) {
            throw changed;
        } catch (IOException | RuntimeException unavailable) {
            if (Thread.currentThread().isInterrupted() || unavailable instanceof java.util.concurrent.CancellationException)
                throw unavailable;
            discardCalculatedFields(rows, unavailable);
        }
        // Even after an optional calculation failure, reject a mixed or changed snapshot.
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

    private static void discardCalculatedFields(List<Map<String, Object>> rows, Exception failure) {
        for (var row : rows) for (String field : CALCULATED_FIELDS) row.put(field, null);
        log.warn("FM calculated fitness extraction failed ({}); calculated fields were left empty and data loading will continue",
                failure.getClass().getSimpleName());
    }

    private static void captureRows(ProcessMemoryReader reader, long moduleBase,
            List<Map<String, Object>> rows, LocalDate gameDate, String profile) throws IOException {
        var categoriesLayout = com.github.fmaiassistent.memory.PlayerInjuryRiskLayout.TrainingCategories.forModule(profile);
        var matchLoads = new PlayerMatchLoadReader(com.github.fmaiassistent.memory.PlayerMatchLoadLayout.forModule(profile));
        var trainingLoads = new PlayerTrainingLoadReader(
                com.github.fmaiassistent.memory.PlayerTrainingLoadLayout.forModule(profile), categoriesLayout);
        var injuryRisk = new PlayerInjuryRiskReader(com.github.fmaiassistent.memory.PlayerInjuryRiskLayout.current(),
                com.github.fmaiassistent.memory.PlayerInjuryRiskLayout.Calculation.forModule(profile), categoriesLayout,
                com.github.fmaiassistent.memory.PlayerMedicalAssessmentLayout.forModule(profile));
        var trainingSelection = new PlayerTrainingSelectionReader(
                com.github.fmaiassistent.memory.PlayerTrainingSelectionLayout.forModule(profile), profile);
        var medicalStaff = new PlayerMedicalStaffReader(com.github.fmaiassistent.memory.PlayerMedicalStaffLayout.forModule(profile));
        var displacements = new HashMap<Long, Integer>();
        var failures = new HashMap<String, Integer>();
        for (var row : rows) for (String field : CALCULATED_FIELDS) row.put(field, null);
        for (var row : rows) {
            long person = Long.decode(String.valueOf(row.get("record")));
            Long playerAddress = optionalCalculation("player context", failures, () -> {
                long vtable = reader.readU64(person);
                Integer displacement = displacements.get(vtable);
                if (displacement == null) {
                    long metadata = reader.readU64(vtable - Long.BYTES);
                    displacement = reader.readI32(metadata + Integer.BYTES);
                    if (displacement != 0x288 && displacement != 0x380)
                        throw new IOException("Player fitness PERSON subtype anchor failed");
                    displacements.put(vtable, displacement);
                }
                return person - displacement;
            });
            if (playerAddress == null) continue;
            long player = playerAddress;
            var load = optionalCalculation("match_load", failures,
                    () -> matchLoads.read(reader, player, moduleBase, gameDate));
            row.put("match_load", load == null ? null : load.label());
            var training = optionalCalculation("training_load", failures,
                    () -> trainingLoads.read(reader, player, moduleBase, gameDate));
            row.put("training_load", training == null ? null : training.label());
            if (gameDate == null || !(row.get("condition") instanceof Number condition)
                    || !(row.get("sharpness") instanceof Number sharpness)
                    || !(row.get("fatigue") instanceof Number fatigue)) continue;
            var susceptibilityLabel = optionalCalculation("injury_susceptibility", failures, () -> {
                var susceptibility = injuryRisk.readSusceptibility(reader, player, moduleBase, gameDate,
                        condition.intValue(), fatigue.intValue(), null);
                if (susceptibility.label() == null
                        && "Physician context unavailable".equals(susceptibility.unresolvedReason())) {
                    var staff = medicalStaff.defaultStaff(reader, player, moduleBase, gameDate);
                    if (staff != null) susceptibility = injuryRisk.readSusceptibility(reader, player, moduleBase,
                            gameDate, condition.intValue(), fatigue.intValue(), staff);
                }
                return susceptibility.label();
            });
            row.put("injury_susceptibility", susceptibilityLabel);
            if (load == null) continue;
            var overallLabel = optionalCalculation("overall_risk", failures, () -> {
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
                return overall == null ? null : overall.label();
            });
            row.put("overall_risk", overallLabel);
        }
        if (!failures.isEmpty()) log.warn("FM calculated fitness incomplete: {}; affected player fields are empty and data loading will continue", failures);
    }

    private static <T> T optionalCalculation(String field, Map<String, Integer> failures, Calculation<T> calculation)
            throws IOException {
        try {
            return calculation.run();
        } catch (PlayerSnapshotValidator.PersonIdentityChangedException changed) {
            throw changed;
        } catch (IOException | RuntimeException unavailable) {
            if (Thread.currentThread().isInterrupted() || unavailable instanceof java.util.concurrent.CancellationException)
                throw unavailable;
            if (failures.merge(field, 1, Integer::sum) == 1)
                log.warn("FM {} calculation unavailable for a player: {}: {}", field,
                        unavailable.getClass().getSimpleName(), unavailable.getMessage());
            return null;
        }
    }

    @FunctionalInterface private interface Calculation<T> { T run() throws IOException; }
}
