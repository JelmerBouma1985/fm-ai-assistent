package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerMedicalAssessmentLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlayerMedicalAssessmentReaderTest {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);
    @TempDir Path temporary;

    @Test void decodesCapturedBosInjuryAndNativeReturnWindow() throws Exception {
        var m = fixture();
        var injury = new PlayerMedicalAssessmentReader().activeInjury(m, 0x1000, DATE);
        assertThat(injury.type()).isEqualTo(0x5000);
        assertThat(injury.start()).isEqualTo(LocalDate.ofYearDay(2026, 185));
        assertThat(injury.fullDaysRemaining(DATE)).isEqualTo(150);
        assertThat(injury.lightDaysRemaining(DATE)).isEqualTo(120);
        assertThat(injury.susceptibilityRecoveryComplete(DATE)).isFalse();
        assertThat(injury.susceptibilityRecoveryComplete(injury.start().plusDays(150))).isTrue();
        assertThat(injury.lightDaysRemaining(LocalDate.of(1900, 1, 1))).isEqualTo(150);
        m.one(0x4033, 2);
        injury = new PlayerMedicalAssessmentReader().activeInjury(m, 0x1000, DATE);
        assertThat(injury.lightDaysRemaining(DATE)).isEqualTo(14);
        assertThat(new PlayerMedicalAssessmentReader().activeInjury(m, 0x1000,
                LocalDate.of(2027, 8, 3))).isNull();
    }

    @Test void rejectsShiftedOffsetsOnSameCapturedFixtureAndBoundsVector() throws Exception {
        var m = fixture();
        var current = PlayerMedicalAssessmentLayout.current();
        var components = PlayerMedicalAssessmentLayout.class.getRecordComponents();
        var constructor = PlayerMedicalAssessmentLayout.class.getDeclaredConstructors()[0];
        for (int index = 0; index < components.length; index++) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < values.length; i++) {
                Object value = components[i].getAccessor().invoke(current);
                if (i == index) {
                    if (value instanceof Long number) value = number + 0x100;
                    else value = (Integer) value + 0x100;
                }
                values[i] = value;
            }
            var shifted = (PlayerMedicalAssessmentLayout) constructor.newInstance(values);
            assertThatThrownBy(() -> new PlayerMedicalAssessmentReader(shifted)
                    .activeInjury(m, 0x1000, DATE)).isInstanceOf(IOException.class);
        }
        m.quad(0x2010, 0x3000 + 129 * 8);
        assertThatThrownBy(() -> new PlayerMedicalAssessmentReader().activeInjury(m, 0x1000, DATE))
                .isInstanceOf(IOException.class).hasMessageContaining("vector anchor");
    }

    @Test void historyClassificationIncludesOngoingInjuriesAndSeparatesRepeatedTypes() {
        var older = new PlayerMedicalAssessmentReader.HistoricalInjury(1, DATE.minusDays(50),
                10, 3, 3, 5, true);
        var ongoing = new PlayerMedicalAssessmentReader.HistoricalInjury(2, DATE.minusDays(30),
                180, 3, 2, 5, true);
        assertThat(PlayerMedicalAssessmentReader.recent(List.of(older, ongoing), DATE, 20, 2))
                .isEqualTo(ongoing);
        assertThat(PlayerMedicalAssessmentReader.repeated(List.of(older, older, older), DATE, true))
                .isEqualTo(older);
        assertThat(PlayerMedicalAssessmentReader.repeated(List.of(older, ongoing, older), DATE, true))
                .isNull();
        assertThat(PlayerMedicalAssessmentReader.repeated(List.of(older, ongoing, older), DATE, false))
                .isEqualTo(older);
        var otherBody = new PlayerMedicalAssessmentReader.HistoricalInjury(3, DATE.minusDays(30),
                20, 3, 3, 6, true);
        assertThat(PlayerMedicalAssessmentReader.repeated(List.of(older, otherBody, older), DATE, false)
                .type()).isZero();
    }

    @Test void assessmentReadsEmptyNativeHistoryAndKeepsConditionalUnknownsExplicit() throws Exception {
        var m = assessmentFixture();
        var reader = new PlayerMedicalAssessmentReader();
        var result = reader.read(m, 0x1000, 0x10000000, DATE, null);
        assertThat(result.unresolvedReason()).isNull();
        assertThat(result.flags()).isEqualTo(4);
        assertThat(result.primaryType()).isZero();
        m.word(0x120C, 1);
        assertThat(reader.read(m, 0x1000, 0x10000000, DATE, null).unresolvedReason())
                .contains("RNG");
    }

    @Test void readsRamSelectedTemporaryArchiveAndReproducesBosHighSusceptibility() throws Exception {
        var m = assessmentFixture();
        String[] records = {
                "5200ffffffff0003ea00e7071c000000", "6000ffffffff00035100e8071c000000",
                "6000ffffffff0003d200e8072c000000", "5900ffffffff00032501e8070c000000",
                "7b00ffffffff00032800e9078d000000", "5900ffffffff00033001e90717000000",
                "1c00ffffffff00036201e9070d000000", "5800ffffffff00034400ea0703000000",
                "5900ffffffff00036500ea070d000000"};
        byte[] data = new byte[8 + records.length * 16];
        System.arraycopy(HexFormat.of().parseHex("0301746d632e0200"), 0, data, 0, 8);
        for (int i = 0; i < records.length; i++) {
            System.arraycopy(HexFormat.of().parseHex(records[i]), 0, data, 8 + i * 16, 16);
            m.number(0xC200 + i * 4, i * 16);
        }
        Path archive = temporary.resolve("injury_histories_dt.cmt");
        Files.write(archive, data);
        m.quad(0x7008, 0);
        m.quad(0x7000, 0xB000);
        m.word(0xB0A0, 2);
        m.quad(0xB078, 0xC100);
        m.quad(0xB080, 0xC118);
        m.quad(0xB088, 0xC118);
        m.quad(0xC100, 0xC200);
        m.quad(0xC108, 0xC200 + records.length * 4);
        m.quad(0xC110, 0xC200 + records.length * 4);
        m.quad(0xB000, 0x11000);
        m.quad(0x11000, 0x10000000 + 0x5131588);
        m.quad(0x11010, 0x13000);
        m.string(0x13000, temporary.toString());
        m.quad(0xB010, 0x12000);
        m.string(0x12000, "injury_histories_dt");
        m.quad(0x10000000L + 0x5703710, 0xD000);
        m.one(0x10000000L + 0x5703DE0, 1);
        m.quad(0xD080, 0xE000);
        m.quad(0xE000, 0xF000);
        m.quad(0xE008, 0xF400);
        m.quad(0xE010, 0xF400);
        m.put(0xF000, new byte[1024]);
        int[][] types = {{82, 3, 6, 1}, {96, 3, 9, 1}, {89, 4, 8, 1},
                {123, 2, 9, 0}, {28, 4, 9, 1}, {88, 4, 8, 1}};
        for (int[] type : types) {
            long address = 0x20000 + type[0] * 256;
            m.quad(0xF000 + type[0] * 8, address);
            m.one(address + 0x6C, type[1]);
            m.one(address + 0x64, type[2]);
            m.one(address + 0x71, type[3]);
        }
        var reader = new PlayerMedicalAssessmentReader();
        var result = reader.read(m, 0x1000, 0x10000000, DATE, null);
        assertThat(result.unresolvedReason()).isNull();
        assertThat(result.flags()).isEqualTo(0x204);
        assertThat(result.primaryType()).isZero();
        assertThat(PlayerInjuryRiskReader.assessedSusceptibility(62, 0, result.flags())).isEqualTo(82);
        assertThat(PlayerInjuryRiskReader.susceptibilityLabel(82)).isEqualTo("High");
        m.number(0xC200, 1);
        assertThatThrownBy(() -> reader.read(m, 0x1000, 0x10000000, DATE, null))
                .isInstanceOf(IOException.class).hasMessageContaining("record offset range anchor");
        m.number(0xC200, 0);
        Files.write(archive, new byte[8]);
        assertThatThrownBy(() -> reader.read(m, 0x1000, 0x10000000, DATE, null))
                .isInstanceOf(IOException.class).hasMessageContaining("format anchor");
        try (var channel = java.nio.channels.FileChannel.open(archive, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(32L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThatThrownBy(() -> reader.read(m, 0x1000, 0x10000000, DATE, null))
                .isInstanceOf(IOException.class).hasMessageContaining("size anchor");
        Files.delete(archive);
        assertThat(reader.read(m, 0x1000, 0x10000000, DATE, null).unresolvedReason())
                .contains("unavailable");
    }

    private static Memory assessmentFixture() {
        var m = fixture();
        m.one(0x118F, 55);
        m.one(0x1191, 55);
        m.one(0x1278, 128);
        m.word(0x1262, 5500);
        for (int i = 0; i < 3; i++) m.word(0x120C + i * 4, 0xFFFF);
        m.number(0x1208, 0);
        m.quad(0x10000000L + 0x56F5D08, 0x6000);
        m.quad(0x6050, 0x7000);
        m.quad(0x7008, 0x8000);
        m.quad(0x8000, 0x9000);
        m.quad(0x8008, 0x9008);
        m.quad(0x8010, 0x9008);
        m.quad(0x9000, 0xA000);
        m.quad(0xA000, 0);
        m.quad(0xA008, 0);
        m.quad(0xA010, 0);
        m.word(0x1258, 9500);
        m.word(0x125C, 10000);
        m.word(0x125A, -125);
        return m;
    }

    @Test void recoveryAvailabilityPreservesNativeSharpnessConditionAndFatigueBoundaries() {
        assertThat(PlayerMedicalAssessmentReader.recoveryAvailability(9500, 5000, -125)).isZero();
        assertThat(PlayerMedicalAssessmentReader.recoveryAvailability(6999, 6500, -125)).isEqualTo(4);
        assertThat(PlayerMedicalAssessmentReader.recoveryAvailability(6999, 6499, -125)).isEqualTo(128);
        assertThat(PlayerMedicalAssessmentReader.recoveryAvailability(8000, 10000, -251)).isEqualTo(8);
        assertThat(PlayerMedicalAssessmentReader.recoveryAvailability(8000, 10000, -501)).isEqualTo(4);
    }

    @Test void capturedStJusteReputationAndPronenessGuaranteeVeryHighForEveryRandomInjurySelection() throws Exception {
        var m = assessmentFixture();
        m.one(0x118F, 90);
        m.word(0x1262, 5400);
        m.word(0x120C, 57);
        m.word(0x1210, 11);
        var result = new PlayerMedicalAssessmentReader().read(m, 0x1000, 0x10000000, DATE, null);
        assertThat(result.resolved()).isFalse();
        assertThat(result.guaranteedSusceptibilityLabel()).isEqualTo("Very High");
        for (int primary = 0; primary <= 100; primary++) {
            for (int secondary = 0; secondary <= 100; secondary++) {
                assertThat(PlayerInjuryRiskReader.susceptibilityLabel(
                        PlayerInjuryRiskReader.assessedSusceptibility(primary, secondary, result.flags())))
                        .isEqualTo("Very High");
            }
        }
        m.one(0x118F, 55);
        assertThat(new PlayerMedicalAssessmentReader().read(m, 0x1000, 0x10000000, DATE, null)
                .guaranteedSusceptibilityLabel()).isNull();
    }

    @Test void unavailableSelectsNewestValidStatusBeforeApplyingNativeKindsAndExclusiveEnd() throws Exception {
        var m = fixture();
        m.quad(0x2018, 0);
        m.quad(0x2020, 0);
        m.quad(0x2028, 0);
        var reader = new PlayerMedicalAssessmentReader();
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE)).isFalse();
        m.quad(0x2018, 0xB000);
        m.quad(0x2020, 0xB008);
        m.quad(0x2028, 0xB010);
        m.quad(0xB000, 0xC000);
        m.one(0xC005, 27);
        m.word(0xC010, DATE.getDayOfYear());
        m.word(0xC012, DATE.getYear());
        m.word(0xC016, 7);
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE)).isTrue();
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE.plusDays(7))).isFalse();
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE.minusDays(1))).isFalse();
        m.quad(0xB008, 0xC040);
        m.quad(0x2020, 0xB010);
        m.one(0xC045, 34);
        m.word(0xC050, DATE.getDayOfYear());
        m.word(0xC052, DATE.getYear());
        m.word(0xC056, 7);
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE)).isFalse();
        m.one(0xC045, 31);
        m.one(0x10000000L + 0x56FF347, 0);
        m.quad(0x10000000L + 0x5709280, 0xD000);
        m.word(0xD164, DATE.getDayOfYear());
        m.word(0xD166, DATE.getYear());
        // A short kind31 is excluded by the90-day cutoff, exposing the previous kind27.
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE)).isTrue();
        m.one(0xC005, 34);
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE)).isFalse();
        m.word(0xC056, 91);
        assertThat(reader.unavailable(m, 0x1000, 0x10000000, DATE)).isTrue();
        m.word(0xC052, 1);
        assertThatThrownBy(() -> reader.unavailable(m, 0x1000, 0x10000000, DATE))
                .isInstanceOf(IOException.class).hasMessageContaining("status year");
    }

    private static Memory fixture() {
        var m = new Memory();
        m.quad(0x10F8, 0x2000);
        m.quad(0x2000, 0x3000);
        m.quad(0x2008, 0x3008);
        m.quad(0x2010, 0x3008);
        m.quad(0x3000, 0x4000);
        // Captured Bos INJURY with pointers relocated; native dates and durations preserved.
        m.put(0x4000, HexFormat.of().parseHex("0000000000000000e80667af920200000000000000000000e8ef918c92020000b900ea07ffffffffb400960000000003020000ff00000000d83c519292020000"));
        m.quad(0x4008, 0x5000);
        return m;
    }

    private static final class Memory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();
        void one(long at, int value) { bytes.put(at, (byte) value); }
        void word(long at, int value) {
            put(at, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
        }
        void number(long at, int value) {
            put(at, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        }
        void string(long at, String value) {
            byte[] data = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            number(at, data.length);
            put(at + 4, data);
        }
        void put(long at, byte[] data) { for (int i = 0; i < data.length; i++) bytes.put(at + i, data[i]); }
        void quad(long at, long value) {
            put(at, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }
        public int pid() { return 1; }
        public byte[] readBytes(long at, int size) throws IOException {
            byte[] result = new byte[size];
            for (int i = 0; i < size; i++) {
                Byte value = bytes.get(at + i);
                if (value == null) throw new IOException("Unmapped fixture byte");
                result[i] = value;
            }
            return result;
        }
        public List<MemoryRegion> maps() { return List.of(); }
        public void close() { }
    }
}
