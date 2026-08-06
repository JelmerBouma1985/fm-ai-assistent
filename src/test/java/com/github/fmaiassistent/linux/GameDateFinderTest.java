package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameDateFinderTest {
    private static final long GAME_PLUGIN_BASE = 0x1000_0000L;
    private static final long BUILD_238BDD_DATE_RVA = 0x4e35f60L;
    private static final long GAME_STATE = 0x7000_0000L;

    @Test
    void readsCurrentDateInsteadOfNextProcessingDate() throws IOException {
        FakeReader reader = new FakeReader();
        putGameStateDates(reader);

        LocalDate date = new GameDateFinder()
                .find(reader, 0, 0x238bdd, GAME_PLUGIN_BASE)
                .orElseThrow();

        assertEquals(LocalDate.of(2027, 12, 19), date);
    }

    @Test
    void triesCurrentDatePointerWhenCallerStillUsesAnOlderBuild() throws IOException {
        FakeReader reader = new FakeReader();
        putGameStateDates(reader);

        LocalDate date = new GameDateFinder()
                .find(reader, 0, 0x235144, GAME_PLUGIN_BASE)
                .orElseThrow();

        assertEquals(LocalDate.of(2027, 12, 19), date);
    }

    private static void putGameStateDates(FakeReader reader) {
        reader.putU64(GAME_PLUGIN_BASE + BUILD_238BDD_DATE_RVA, GAME_STATE);
        reader.putU16(GAME_STATE + 0x70, 0x3400 | 360);
        reader.putU16(GAME_STATE + 0x72, 2027);
        reader.putU16(GAME_STATE + 0x74, 0x3400 | 353);
        reader.putU16(GAME_STATE + 0x76, 2027);
    }

    private static final class FakeReader implements ProcessMemoryReader {
        private final Map<Long, Byte> memory = new HashMap<>();

        void putU16(long address, int value) {
            memory.put(address, (byte) value);
            memory.put(address + 1, (byte) (value >>> 8));
        }

        void putU64(long address, long value) {
            for (int index = 0; index < Long.BYTES; index++) {
                memory.put(address + index, (byte) (value >>> (index * 8)));
            }
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] bytes = new byte[size];
            for (int index = 0; index < size; index++) {
                Byte value = memory.get(address + index);
                if (value == null) {
                    throw new IOException("unmapped test address");
                }
                bytes[index] = value;
            }
            return bytes;
        }

        @Override
        public List<MemoryRegion> maps() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
