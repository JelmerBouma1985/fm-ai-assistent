package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerMatchLoadLayout;
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
import static org.assertj.core.api.Assertions.*;

class PlayerMatchLoadReaderTest {
    private static final long BASE = 0x10000000, PLAYER = 0x1000, MANAGER = 0x2000,
            MEDICAL = 0x3000, HISTORY = 0x4000, BUCKETS = 0x5000, BUCKET = 0x6000,
            ITEM = 0x7000, DETAIL = 0x8000;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);

    @Test void readsCapturedCharlesVanhoutteFormWithNativeDateFlagsAndRatingScale() throws Exception {
        var memory = fixture();
        var result = new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE);
        assertThat(result.label()).isEqualTo("Light");
        assertThat(result.appearances()).isEqualTo(1);
        assertThat(result.minutes()).isEqualTo(54);
        assertThat(result.averageRating()).isEqualTo(8.1f);
        // A match on the native exclusive cutoff is excluded, including the year rollover.
        memory.word(ITEM + 0x10, DATE.minusDays(15).getDayOfYear());
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE).appearances()).isZero();
        memory.word(ITEM + 0x10, 365);
        memory.word(ITEM + 0x12, 2025);
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, LocalDate.of(2026, 1, 3))
                .appearances()).isEqualTo(1);
    }

    @Test void usesRingHeadAndDoesNotMistakeUnsupportedHistoryForLight() throws Exception {
        var memory = fixture();
        memory.one(BUCKET + 0xB8, 19);
        memory.quad(BUCKET + 0x18 + 19 * 8, ITEM);
        memory.quad(BUCKET + 0x18, 0);
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE).appearances()).isEqualTo(1);
        memory.quad(BUCKET, BASE + 0x1234);
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE)).isNull();
        memory.quad(PLAYER + 0xF8, 0);
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE).label()).isEqualTo("Light");
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, null)).isNull();
    }

    @Test void honorsLookbackOverrideOnlyForSelectedPlayerIndex() throws Exception {
        var memory = fixture();
        memory.one(MANAGER + 0x31, 2);
        memory.quad(MANAGER + 0x18, 0x9000);
        memory.quad(MANAGER + 0x20, 0x9004);
        memory.number(0x9000, 42);
        memory.quad(PLAYER + 8, 0xA000);
        memory.number(0xA004, 0x280);
        memory.number(PLAYER + 0x290, 42);
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE).days()).isEqualTo(2);
        memory.number(PLAYER + 0x290, 43);
        assertThat(new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE).days()).isEqualTo(14);
    }

    @Test void rejectsEveryShiftedLayoutMemberOnTheSameCapturedFixtureAndBoundsNativeRing() throws Exception {
        var memory = fixture();
        var current = PlayerMatchLoadLayout.current();
        var components = PlayerMatchLoadLayout.class.getRecordComponents();
        var constructor = PlayerMatchLoadLayout.class.getDeclaredConstructors()[0];
        for (int shiftedIndex = 0; shiftedIndex < components.length; shiftedIndex++) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < values.length; i++) {
                Object value = components[i].getAccessor().invoke(current);
                if (i == shiftedIndex) {
                    if (value instanceof Long number) value = number + 1L;
                    else value = ((Integer) value) + 1;
                }
                values[i] = value;
            }
            var shifted = (PlayerMatchLoadLayout) constructor.newInstance(values);
            assertThatThrownBy(() -> new PlayerMatchLoadReader(shifted).read(memory, PLAYER, BASE, DATE))
                    .isInstanceOf(IOException.class).hasMessageContaining("range/displacement anchor");
        }
        memory.one(BUCKET + 0x10, 21);
        assertThatThrownBy(() -> new PlayerMatchLoadReader().read(memory, PLAYER, BASE, DATE))
                .isInstanceOf(IOException.class).hasMessageContaining("ring range anchor");
    }

    @Test void nativeClassificationKeepsFrequencyMinutesRatingAndGoalkeeperBoundaries() {
        assertThat(PlayerMatchLoadReader.classify(0, 0, 0, 14, false)).isZero();
        assertThat(PlayerMatchLoadReader.classify(1, 90, 10, 14, false)).isZero();
        assertThat(PlayerMatchLoadReader.classify(2, 90, 6, 14, false)).isEqualTo(1);
        assertThat(PlayerMatchLoadReader.classify(3, 270, 6, 14, false)).isEqualTo(2);
        assertThat(PlayerMatchLoadReader.classify(5, 450, 8, 14, false)).isEqualTo(3);
        assertThat(PlayerMatchLoadReader.classify(5, 450, 8, 14, true)).isEqualTo(1);
        assertThat(PlayerMatchLoadReader.classify(5, 450, 7.9f, 14, false)).isEqualTo(2);
    }

    private static Memory fixture() {
        var memory = new Memory();
        memory.quad(BASE + 0x56F5D08, MANAGER);
        memory.one(MANAGER + 0x31, 255);
        memory.quad(PLAYER + 0xF8, MEDICAL);
        memory.one(PLAYER + 0x150, 1);
        memory.quad(MEDICAL + 0x58, HISTORY);
        memory.quad(HISTORY, BUCKETS);
        memory.quad(HISTORY + 8, BUCKETS + 8);
        memory.quad(BUCKETS, BUCKET);
        memory.quad(BUCKET, BASE + 0x4ED8A58);
        memory.one(BUCKET + 0x10, 1);
        memory.one(BUCKET + 0xB8, 0);
        memory.quad(BUCKET + 0x18, ITEM);
        // Captured UID18103350 native entry and detail, with pointers relocated for this fixture.
        memory.put(ITEM, HexFormat.of().parseHex("9084938c92020000a87312b192020000d570ea0783000000"));
        memory.quad(ITEM + 8, DETAIL);
        memory.put(DETAIL, HexFormat.of().parseHex("80002000000010000000000000010001000036000036401811010001000001010051015700000000"));
        return memory;
    }

    private static class Memory implements ProcessMemoryReader {
        final Map<Long, Byte> bytes = new HashMap<>();
        void one(long at, int value) { bytes.put(at, (byte) value); }
        void put(long at, byte[] data) { for (int i = 0; i < data.length; i++) bytes.put(at + i, data[i]); }
        void word(long at, int value) { put(at, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array()); }
        void number(long at, int value) { put(at, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()); }
        void quad(long at, long value) { put(at, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        public int pid() { return 1; }
        public byte[] readBytes(long at, int length) throws IOException {
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) {
                Byte value = bytes.get(at + i);
                if (value == null) throw new IOException("Unmapped fixture byte");
                out[i] = value;
            }
            return out;
        }
        public List<MemoryRegion> maps() { return List.of(); }
        public void close() { }
    }
}
