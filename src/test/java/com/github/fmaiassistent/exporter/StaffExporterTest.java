package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaffExporterTest {
    private static final long PERSON = 0x2000;
    private static final long BASE = PERSON - 0x100;
    private static final long CONTRACT = 0x3000;
    private static final long TEAM = 0x4000;
    private static final long CLUB = 0x5000;
    private static final long COMPETITION = 0x6000;
    private static final long NATION = 0x7000;

    @Test
    void decodesEnglishStaffFieldsAndFivePointAttributes() throws Exception {
        FakeMemory memory = new FakeMemory();
        memory.fill(BASE + 0x10, 0x34, 0);
        memory.putU32(PERSON + 0x0c, 123456);
        memory.putU8(PERSON + 0x19, 0);
        memory.putStringPointer(PERSON + 0x40, 0x8000, "Jane Coach");
        memory.putLong(PERSON + 0x68, NATION);
        memory.putStringPointer(NATION + 0x18, 0x8100, "England");
        memory.putLong(PERSON + 0xa8, CONTRACT);
        memory.putLong(CONTRACT + 0x10, TEAM);
        memory.putLong(TEAM + 0x30, CLUB);
        memory.putStringPointer(CLUB + 0xc8, 0x8200, "Test FC");
        memory.putLong(TEAM + 0x50, COMPETITION);
        memory.putStringPointer(COMPETITION + 0x48, 0x8300, "Premier Division");
        memory.putU8(CONTRACT + 0x26, 2);
        memory.putU32(CONTRACT + 0x20, 12500);
        memory.putDate(PERSON + 0x88, 100, 1990);
        memory.putDate(CONTRACT + 0x48, 200, 2034);
        memory.putU16(BASE + 0xd4, 4000);
        memory.putU16(BASE + 0xd6, 5000);
        memory.putU16(BASE + 0xd8, 3500);
        memory.putU16(BASE + 0xda, 145);
        memory.putU16(BASE + 0xdc, 160);
        memory.putU8(BASE + 0x10 + 0x22, 85);
        memory.putU8(BASE + 0x10 + 0x1c, 90);
        memory.putU8(BASE + 0x10 + 0x04, 16);
        memory.putU8(BASE + 0x10 + 0x0c, 19);
        memory.putU8(BASE + 0x10 + 0x1b, 75);

        Map<String, Object> row = new StaffExporter().decodeRow(memory, 7, PERSON, 0x100);

        assertThat(row).containsEntry("unique_id", 123456L)
                .containsEntry("name", "Jane Coach")
                .containsEntry("nationality", "England")
                .containsEntry("club", "Test FC")
                .containsEntry("division", "Premier Division")
                .containsEntry("job", "Coach")
                .containsEntry("salary_weekly_raw", 12500L)
                .containsEntry("ca", 145)
                .containsEntry("pa", 160)
                .containsEntry("authority", 16)
                .containsEntry("attacking", 17)
                .containsEntry("goalkeeping", 15)
                .containsEntry("working_with_youngsters", 19)
                .containsEntry("judging_player_ability", 18);
    }

    @Test
    void exposesEnglishJobNamesAndSafeFallback() {
        assertThat(StaffExporter.jobName(64)).isEqualTo("Head of Youth Development");
        assertThat(StaffExporter.jobName(999)).isEqualTo("Staff Member");
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();
        void fill(long address, int length, int value) { for (int i = 0; i < length; i++) putU8(address + i, value); }
        void putLong(long address, long value) { put(address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        void putU32(long address, long value) { put(address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array()); }
        void putU16(long address, int value) { put(address, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array()); }
        void putU8(long address, int value) { bytes.put(address, (byte) value); }
        void putDate(long address, int day, int year) { putU16(address, day); putU16(address + 2, year); }
        void putStringPointer(long pointerAddress, long stringAddress, String value) {
            putLong(pointerAddress, stringAddress);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            putU32(stringAddress, text.length);
            put(stringAddress + 4, text);
        }
        private void put(long address, byte[] value) { for (int i = 0; i < value.length; i++) bytes.put(address + i, value[i]); }
        @Override public int pid() { return 1; }
        @Override public byte[] readBytes(long address, int size) throws IOException {
            byte[] out = new byte[size];
            for (int i = 0; i < size; i++) {
                Byte value = bytes.get(address + i);
                if (value == null) throw new IOException("unmapped 0x" + Long.toHexString(address + i));
                out[i] = value;
            }
            return out;
        }
        @Override public List<MemoryRegion> maps() { return List.of(); }
        @Override public void close() { }
    }
}
