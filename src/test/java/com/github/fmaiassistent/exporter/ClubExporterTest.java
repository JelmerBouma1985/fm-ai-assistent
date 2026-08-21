package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClubExporterTest {
    private static final long CLUB = 0x1000_0000L;
    private static final long FACILITIES = 0x2000_0000L;
    private static final long EXTRA = 0x3000_0000L;

    @Test
    void readsFm26FacilityRatingsFromTheClubSubstructures() throws Exception {
        FakeMemory memory = new FakeMemory();
        memory.putLong(CLUB + 0x100, FACILITIES);
        memory.putU8(FACILITIES + 0x118, 18);
        memory.putU8(FACILITIES + 0x123, 13);
        memory.putU8(FACILITIES + 0x124, 20);
        memory.putU8(FACILITIES + 0x125, 17);
        memory.putLong(CLUB + 0x150, EXTRA);
        memory.putU16(EXTRA, 0xB318);
        memory.putU8(EXTRA + 0x8B5, 9);

        ClubExporter.Facilities facilities = ClubExporter.readFacilities(memory, CLUB);

        assertThat(facilities.training()).isEqualTo(18);
        assertThat(facilities.youth()).isEqualTo(13);
        assertThat(facilities.coaching()).isEqualTo(20);
        assertThat(facilities.recruitment()).isEqualTo(17);
        assertThat(facilities.corporate()).isEqualTo(9);
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();

        void putLong(long address, long value) {
            put(address, ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        void putU16(long address, int value) {
            put(address, ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
        }

        void putU8(long address, int value) {
            bytes.put(address, (byte) value);
        }

        private void put(long address, byte[] value) {
            for (int index = 0; index < value.length; index++) {
                bytes.put(address + index, value[index]);
            }
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] value = new byte[size];
            for (int index = 0; index < size; index++) {
                Byte next = bytes.get(address + index);
                if (next == null) {
                    throw new IOException("unmapped test address 0x" + Long.toHexString(address + index));
                }
                value[index] = next;
            }
            return value;
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
