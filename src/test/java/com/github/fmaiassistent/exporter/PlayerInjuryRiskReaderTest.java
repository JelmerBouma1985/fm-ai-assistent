package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerInjuryRiskLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerInjuryRiskReaderTest {
    @Test
    void replaysCapturedNativeCategoryTablesAndCatalogueMaximum() throws Exception {
        byte[] input;
        try (var source = getClass().getResourceAsStream("/fm27/player-injury-risk-captured.json")) {
            input = source.readNBytes(512 * 1024 + 1);
        }
        assertThat(input.length).isLessThanOrEqualTo(512 * 1024);
        var capture = JsonMapper.builder().build().readTree(input);
        var bytes = new HashMap<Long, Byte>();
        for (var entry : capture.path("memory").properties()) {
            long address = Long.parseLong(entry.getKey().split(":")[0]);
            byte[] value = HexFormat.of().parseHex(entry.getValue().asString());
            for (int i = 0; i < value.length; i++) bytes.put(address + i, value[i]);
        }
        var raw = capture.path("trainingInputs");
        var training = new PlayerTrainingLoadReader.RiskTrainingInputs(raw.path("manager").asLong(),
                raw.path("team").asLong(), raw.path("schedule").asLong(), raw.path("configuration").asLong(),
                raw.path("roleMedicalScore").asInt(), raw.path("generalMedicalScore").asInt(),
                raw.path("individualIntensity").asInt(), raw.path("modifiedBaseline").asInt(),
                Base64.getDecoder().decode(raw.path("focus").asString()));
        var ids = capture.path("selected").path("nativeAttributeIDs");
        int[] attributes = new int[ids.size()];
        for (int i = 0; i < attributes.length; i++) attributes[i] = ids.get(i).asInt();
        long player = capture.path("player").asLong(), module = capture.path("module").asLong();
        var memory = sparseMemory(bytes, true);
        var reader = new PlayerInjuryRiskReader();
        int[] categories = reader.readTrainingCategories(memory, player, module, training,
                capture.path("trainingRole").asInt(), attributes);
        assertThat(categories).containsExactly(3, 0, 5, 5, 5, 5, 5, 5, 5);
        int condition = memory.readU16(player + 0x258);
        int sharpness = memory.readU16(player + 0x25C);
        int fatigue = memory.readI16(player + 0x25A);
        var score = reader.readRisk(memory, player, module, LocalDate.of(2026,8,3),
                condition, sharpness, fatigue, 0, training, categories, false, 1f);
        assertThat(score.score()).isEqualTo(0.79859865f);
        assertThat(score.label()).isEqualTo("Low");
        for (Object shifted : shiftedRecords(PlayerInjuryRiskLayout.TrainingCategories.current()))
            assertThatThrownBy(() -> reader.readTrainingCategories(memory, player, module, training,
                    2, attributes, (PlayerInjuryRiskLayout.TrainingCategories) shifted))
                    .isInstanceOf(IOException.class).hasMessageContaining("range anchor");
        for (Object shifted : shiftedRecords(PlayerInjuryRiskLayout.Calculation.current()))
            assertThatThrownBy(() -> reader.readRisk(memory, player, module, LocalDate.of(2026,8,3),
                    condition, sharpness, fatigue, 0, training, categories, false, 1f,
                    (PlayerInjuryRiskLayout.Calculation) shifted))
                    .isInstanceOf(IOException.class).hasMessageContaining("range anchor");
    }
    @Test
    void nativeSusceptibilityCurveMatchesCapturedUninjuredPlayers() {
        // FM27 Feyenoord: Kastenmeier, Ernst, Gardenier, Targhalline, St. Juste.
        int[] prone = {30, 40, 55, 65, 90};
        int[] condition = {9175, 9052, 9001, 9430, 8744};
        int[] scores = {37, 50, 62, 70, 91};
        for (int i = 0; i < prone.length; i++) {
            assertThat(PlayerInjuryRiskReader.baseSusceptibility(inputs(prone[i], condition[i], -125)))
                    .isEqualTo(scores[i]);
        }
        assertThat(PlayerInjuryRiskReader.assessedSusceptibility(62, 0, 0x2000)).isEqualTo(72);
        assertThat(PlayerInjuryRiskReader.susceptibilityLabel(72)).isEqualTo("High");
    }

    @Test
    void fatigueAndRecurringInjuriesFollowNativeOverrides() {
        assertThat(PlayerInjuryRiskReader.baseSusceptibility(inputs(40, 10000, 415))).isEqualTo(50);
        assertThat(PlayerInjuryRiskReader.baseSusceptibility(inputs(40, 10000, 430))).isEqualTo(54);
        var recurrent = new PlayerInjuryRiskReader.SusceptibilityInputs(40, true, 10000,
                -125, 12, 0, 0, -1, 12, -1);
        assertThat(PlayerInjuryRiskReader.baseSusceptibility(recurrent)).isEqualTo(100);
        var fixedType = new PlayerInjuryRiskReader.SusceptibilityInputs(90, true, 5000,
                800, 12, 0x30, 0, 12, -1, -1);
        assertThat(PlayerInjuryRiskReader.baseSusceptibility(fixedType)).isEqualTo(50);
    }

    @Test
    void physicianFlagPriorityAndSignedByteArithmeticArePreserved() {
        assertThat(PlayerInjuryRiskReader.assessedSusceptibility(30, 40, 0)).isEqualTo(40);
        assertThat(PlayerInjuryRiskReader.assessedSusceptibility(40, 0, 0x102 | 0x281)).isEqualTo(85);
        assertThat(PlayerInjuryRiskReader.assessedSusceptibility(40, 0, 0x200)).isEqualTo(70);
        assertThat(PlayerInjuryRiskReader.assessedSusceptibility(100, 0, 0x100)).isEqualTo(85);
    }

    @Test
    void physicalRiskUsesExactNativeBoundaries() {
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 10000, 0)).isZero();
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 6999, 0)).isEqualTo(3.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 7000, 0)).isEqualTo(2.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 7500, 0)).isEqualTo(1.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 8500, 0)).isZero();
        assertThat(PlayerInjuryRiskReader.physicalRisk(7000, 9500, 0)).isEqualTo(1.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(7000, 9501, 0)).isZero();
        assertThat(PlayerInjuryRiskReader.physicalRisk(5000, 10000, 0)).isEqualTo(2.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(5001, 10000, 0)).isEqualTo(1.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 10000, 2)).isEqualTo(2.5f);
        assertThat(PlayerInjuryRiskReader.physicalRisk(10000, 10000, 3)).isEqualTo(1.5f);
    }

    @Test
    void injuryMasksSelectTheirOwnTrainingFactorsAndRecurringSeverity() {
        int[] training = {30, 50, 20, 20, 20, 20, 20, 20, 0};
        float[] factors = PlayerInjuryRiskReader.trainingFactors(training, false);
        var neutral = new PlayerInjuryRiskReader.RiskModifiers(0, false, 1, 1, 1, 1, 1);
        assertThat(PlayerInjuryRiskReader.injuryRiskScore(0, factors, neutral, -1)).isZero();
        assertThat(PlayerInjuryRiskReader.injuryRiskScore(4, factors, neutral, -1)).isEqualTo(2.5f);
        assertThat(PlayerInjuryRiskReader.injuryRiskScore(4, factors, neutral, 6)).isEqualTo(3f);
        var noTraining = new PlayerInjuryRiskReader.RiskModifiers(0, true, 1, 1, 1, 1, 1);
        assertThat(PlayerInjuryRiskReader.injuryRiskScore(4, factors, noTraining, -1)).isEqualTo(0.75f);
        assertThat(PlayerInjuryRiskReader.trainingFactors(training, true)[1]).isEqualTo(50f);
    }

    @Test
    void samePhysicalFixtureRejectsPronenessOffsetDriftBeforePublishing() throws Exception {
        byte[] data = new byte[0x300];
        Arrays.fill(data, (byte) 0xFF);
        data[0x18F] = 30;
        data[0x278] = (byte) 0x80;
        ProcessMemoryReader memory = memory(data);
        var reader = new PlayerInjuryRiskReader();
        assertThat(PlayerInjuryRiskReader.baseSusceptibility(
                reader.readSusceptibilityInputs(memory, 0x1000, 9175, -125, 0))).isEqualTo(37);
        var l = PlayerInjuryRiskLayout.current();
        var shifted = new PlayerInjuryRiskLayout(l.injuryPronenessRel() + 1, l.attributeEncodingRel(),
                l.recurringInjuriesRel(), l.recurringInjuryStride(), l.injuryReferenceRel(),
                l.activeInjuryTypeRel(), l.injuryTypeIndexRel(), l.injuryTypeKindRel(), l.injuryTypeOccurrenceRel());
        assertThatThrownBy(() -> new PlayerInjuryRiskReader(shifted)
                .readSusceptibilityInputs(memory, 0x1000, 9175, -125, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("range anchor");
    }

    @Test
    void everyMedicalOffsetDriftRejectsTheSameCapturedFixture() throws Exception {
        byte[] fixture = new byte[0x300];
        fixture[0x18F] = 30;
        fixture[0x278] = (byte) 0x80;
        var components = PlayerInjuryRiskLayout.class.getRecordComponents();
        var signature = new Class<?>[components.length];
        Arrays.fill(signature, int.class);
        var constructor = PlayerInjuryRiskLayout.class.getDeclaredConstructor(signature);
        for (int shifted = 0; shifted < components.length; shifted++) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                int value = (int) components[i].getAccessor().invoke(PlayerInjuryRiskLayout.current());
                values[i] = value + (i == shifted ? 1 : 0);
            }
            var drift = constructor.newInstance(values);
            assertThatThrownBy(() -> new PlayerInjuryRiskReader(drift)
                    .readSusceptibilityInputs(memory(fixture), 0x1000, 9175, -125, 0))
                    .as(components[shifted].getName()).isInstanceOf(IOException.class)
                    .hasMessageContaining("range anchor");
        }
    }

    @Test
    void nativeCategoryWorkloadsRespectRoleIntensityAndPersonalityMinimums() {
        var sessions = new PlayerInjuryRiskReader.TrainingSession[21];
        sessions[0] = new PlayerInjuryRiskReader.TrainingSession(100, false, true, false,
                new float[]{4, 6, 2, 3, 4, 5, 6, 7, 8});
        sessions[1] = new PlayerInjuryRiskReader.TrainingSession(200, false, false, false, new float[9]);
        int[] sizes = {10, 10, 10, 10, 10, 10, 10, 10, 10};
        assertThat(PlayerInjuryRiskReader.trainingCategories(sessions, 1, new int[]{2}, sizes,
                1, false, 10, 10)).containsExactly(4, 0, 6, 3, 4, 5, 6, 7, 8);
        assertThat(PlayerInjuryRiskReader.trainingCategories(sessions, 0, new int[]{1}, sizes,
                2, true, 10, 10)).containsExactly(5, 13, 0, 0, 0, 0, 0, 0, 0);
        sessions[0] = null;
        sessions[1] = null;
        assertThat(PlayerInjuryRiskReader.trainingCategories(sessions, 1, new int[0], sizes,
                1, false, 20, 20)).containsExactly(6, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void allCategoryAndCalculationLayoutMembersRejectShiftedFixtures() throws Exception {
        byte[] fixture = new byte[0x300];
        fixture[0x1EE] = 2;
        var memory = memory(fixture);
        var reader = new PlayerInjuryRiskReader();
        assertThat(reader.readRisk(memory, 0x1000, 0, LocalDate.of(2026, 8, 3),
                9000, 9000, -125, 0, null, null, null, null).score()).isZero();
        assertThat(reader.readTrainingCategories(memory, 0x1000, 0, null, 1, null)).isNull();
        for (Object changed : shiftedRecords(PlayerInjuryRiskLayout.Calculation.current())) {
            assertThatThrownBy(() -> reader.readRisk(memory, 0x1000, 0, LocalDate.of(2026, 8, 3),
                    9000, 9000, -125, 0, null, null, null, null,
                    (PlayerInjuryRiskLayout.Calculation) changed))
                    .isInstanceOf(IOException.class).hasMessageContaining("range anchor");
        }
        for (Object changed : shiftedRecords(PlayerInjuryRiskLayout.TrainingCategories.current())) {
            assertThatThrownBy(() -> reader.readTrainingCategories(memory, 0x1000, 0, null, 1, null,
                    (PlayerInjuryRiskLayout.TrainingCategories) changed))
                    .isInstanceOf(IOException.class).hasMessageContaining("range anchor");
        }
    }

    @Test
    void catalogueOrchestrationCombinesTrainingPhysicalAndTacticalRisk() throws Exception {
        long player = 0x1000, module = 0x100000000L;
        var bytes = new HashMap<Long, Byte>();
        put(bytes, module + 0x56F5D08, 0x2000, 8);
        put(bytes, module + 0x5703710, 0x3000, 8);
        put(bytes, 0x3080, 0x4000, 8);
        put(bytes, 0x4000, 0x5000, 8);
        put(bytes, 0x4008, 0x5008, 8);
        put(bytes, 0x5000, 0x6000, 8);
        put(bytes, 0x6008, 12, 4);
        put(bytes, 0x6066, 4, 2);
        put(bytes, player + 8, 0x7000, 8);
        put(bytes, 0x7004, 0x288, 4);
        var memory = sparseMemory(bytes);
        var training = new PlayerTrainingLoadReader.RiskTrainingInputs(0, 0, 0, 0,
                85, 85, 1, 100, new byte[6]);
        var reader = new PlayerInjuryRiskReader();
        var fromTraining = reader.readRisk(memory, player, module, LocalDate.of(2026, 8, 3),
                10000, 10000, 0, 0, training, new int[]{25, 0, 0, 0, 0, 0, 0, 0, 0}, 1f);
        assertThat(fromTraining.score()).isEqualTo(1.9f);
        assertThat(fromTraining.label()).isEqualTo("Increased");
        var fromSharpness = reader.readRisk(memory, player, module, LocalDate.of(2026, 8, 3),
                10000, 6000, 0, 0, training, new int[]{25, 0, 0, 0, 0, 0, 0, 0, 0}, 1.2f);
        assertThat(fromSharpness.score()).isEqualTo(3.5f * 1.2f);
        assertThat(fromSharpness.label()).isEqualTo("Very High");
        assertThat(reader.readRisk(memory, player, module, LocalDate.of(2026, 8, 3),
                10000, 10000, 0, 0, training, new int[9], null, 1f)).isNull();
    }

    @Test
    void personalRestDaysSkipOnlyTheNativeClassifiedSessionTypes() throws Exception {
        var bytes = new HashMap<Long, Byte>();
        long table = PlayerInjuryRiskLayout.TrainingCategories.current().personalDayExclusionsRva();
        put(bytes, table + 200 * 4L, 1400, 4);
        put(bytes, table + 300 * 4L, 1402, 4);
        put(bytes, table + 1400, 0x01B0, 2);
        put(bytes, table + 1402, 0xC3, 1);
        var memory = sparseMemory(bytes, true);
        assertThat(PlayerInjuryRiskReader.isExcludedOnPersonalDay(memory, 0, 200)).isTrue();
        assertThat(PlayerInjuryRiskReader.isExcludedOnPersonalDay(memory, 0, 300)).isFalse();
        assertThat(PlayerInjuryRiskReader.isExcludedOnPersonalDay(memory, 0, 500)).isFalse();
        put(bytes, table + 1400, 0x9090, 2);
        assertThatThrownBy(() -> PlayerInjuryRiskReader.isExcludedOnPersonalDay(memory, 0, 200))
                .isInstanceOf(IOException.class).hasMessageContaining("code anchor");
    }

    private static void put(Map<Long, Byte> bytes, long address, long value, int length) {
        for (int i = 0; i < length; i++) bytes.put(address + i, (byte) (value >>> (8 * i)));
    }

    private static ProcessMemoryReader sparseMemory(Map<Long, Byte> bytes) {
        return sparseMemory(bytes, false);
    }

    private static ProcessMemoryReader sparseMemory(Map<Long, Byte> bytes, boolean strict) {
        return new ProcessMemoryReader() {
            public int pid() { return 1; }
            public List<MemoryRegion> maps() { return List.of(); }
            public void close() { }
            public byte[] readBytes(long address, int size) throws IOException {
                byte[] result = new byte[size];
                for (int i = 0; i < size; i++) {
                    if (strict && !bytes.containsKey(address + i)) throw new IOException("Uncaptured fixture byte");
                    result[i] = bytes.getOrDefault(address + i, (byte) 0);
                }
                return result;
            }
        };
    }

    private static List<Object> shiftedRecords(Object original) throws Exception {
        var type = original.getClass();
        var components = type.getRecordComponents();
        Class<?>[] signature = new Class<?>[components.length];
        Arrays.fill(signature, int.class);
        var constructor = type.getDeclaredConstructor(signature);
        var result = new java.util.ArrayList<Object>();
        for (int shifted = 0; shifted < components.length; shifted++) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++)
                values[i] = (int) components[i].getAccessor().invoke(original) + (i == shifted ? 1 : 0);
            result.add(constructor.newInstance(values));
        }
        return result;
    }

    private static PlayerInjuryRiskReader.SusceptibilityInputs inputs(int prone, int condition, int fatigue) {
        return new PlayerInjuryRiskReader.SusceptibilityInputs(prone, true, condition, fatigue,
                null, 0, 0, -1, -1, -1);
    }

    private static ProcessMemoryReader memory(byte[] data) {
        return new ProcessMemoryReader() {
            @Override public int pid() { return 1; }
            @Override public List<MemoryRegion> maps() { return List.of(); }
            @Override public void close() { }
            @Override public byte[] readBytes(long address, int size) throws IOException {
                int at = Math.toIntExact(address - 0x1000);
                if (at < 0 || size < 0 || at + size > data.length) throw new IOException("Unreadable fixture");
                return Arrays.copyOfRange(data, at, at + size);
            }
        };
    }
}
