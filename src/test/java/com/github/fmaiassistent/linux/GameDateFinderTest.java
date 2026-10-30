package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.MemoryRegion;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDateFinderTest {
    private static final long GAME_PLUGIN_BASE = 0x1000_0000L;
    private static final long CURRENT_DATE_RVA = 0x56ad378L;

    @Test
    void readsCapturedFm27ClockAndRejectsUnknownBuild() {
        FakeReader reader = new FakeReader(ProcessMemoryReader.Platform.WINDOWS);
        // FULL_CONTRACT vE8 in 27.0.0+2486695 independently references this packed clock.
        reader.putU16(GAME_PLUGIN_BASE + 0x56ad378L, 0x1ad7);
        reader.putU16(GAME_PLUGIN_BASE + 0x56ad37aL, 2026);
        assertEquals(LocalDate.of(2026, 8, 3), new GameDateFinder()
                .find(reader, 0, FmOffsets.DEFAULT_BUILD, GAME_PLUGIN_BASE).orElseThrow());
        assertTrue(new GameDateFinder().find(reader, 0, FmOffsets.DEFAULT_BUILD + 1, GAME_PLUGIN_BASE).isEmpty());
    }

    @Test
    void readsCurrentDateFromLinuxProtonLayout() {
        FakeReader reader = new FakeReader(ProcessMemoryReader.Platform.LINUX);
        putCurrentDate(reader, 0x7E00 | 8, 2028);

        LocalDate date = new GameDateFinder()
                .find(reader, 0, FmOffsets.DEFAULT_BUILD, GAME_PLUGIN_BASE)
                .orElseThrow();

        assertEquals(LocalDate.of(2028, 1, 8), date);
    }

    @Test
    void readsCurrentDateFromSameLayoutOnWindows() {
        FakeReader reader = new FakeReader(ProcessMemoryReader.Platform.WINDOWS);
        putCurrentDate(reader, 0x1A00 | 159, 2026);

        LocalDate date = new GameDateFinder()
                .find(reader, 0, FmOffsets.DEFAULT_BUILD, GAME_PLUGIN_BASE)
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 6, 8), date);
    }

    @Test
    void doesNotFallBackToAnotherBuild() {
        FakeReader reader = new FakeReader(ProcessMemoryReader.Platform.LINUX);
        putCurrentDate(reader, 8, 2028);

        assertTrue(new GameDateFinder()
                .find(reader, 0, 0x235144, GAME_PLUGIN_BASE)
                .isEmpty());
    }

    private static void putCurrentDate(FakeReader reader, int day, int year) {
        reader.putU16(GAME_PLUGIN_BASE + CURRENT_DATE_RVA, day);
        reader.putU16(GAME_PLUGIN_BASE + CURRENT_DATE_RVA + Short.BYTES, year);
    }

    private static final class FakeReader implements ProcessMemoryReader {
        private final Map<Long, Byte> memory = new HashMap<>();
        private final Platform platform;

        private FakeReader(Platform platform) {
            this.platform = platform;
        }

        void putU16(long address, int value) {
            memory.put(address, (byte) value);
            memory.put(address + 1, (byte) (value >>> 8));
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public Platform platform() {
            return platform;
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
