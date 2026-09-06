package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessMemoryReaderTest {
    @Test
    void decodesAllPrimitiveWidthsAsLittleEndianWithoutChangingUnsignedValues() throws Exception {
        try (ProcessMemoryReader reader = new ByteArrayReader(new byte[] {
                (byte) 0xfe, (byte) 0xdc, (byte) 0xba, (byte) 0x98,
                0x76, 0x54, 0x32, 0x10
        })) {
            assertThat(reader.readU8(0)).isEqualTo(0xfe);
            assertThat(reader.readU16(0)).isEqualTo(0xdcfe);
            assertThat(reader.readI32(0)).isEqualTo(0x98badcfe);
            assertThat(reader.readU64(0)).isEqualTo(0x1032547698badcfeL);
        }
    }

    @Test
    void rejectsInvalidReadBoundsBeforeCallingTheOperatingSystem() {
        byte[] target = new byte[4];

        assertThatThrownBy(() -> ProcessMemoryReader.validateRead(-1, target, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcessMemoryReader.validateRead(0, target, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcessMemoryReader.validateRead(
                ProcessMemoryReader.MAX_USER_ADDRESS, target, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class ByteArrayReader implements ProcessMemoryReader {
        private final byte[] bytes;

        private ByteArrayReader(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] result = new byte[size];
            readInto(address, result, 0, size);
            return result;
        }

        @Override
        public void readInto(long address, byte[] target, int offset, int length) throws IOException {
            ProcessMemoryReader.validateRead(address, target, offset, length);
            if (address + length > bytes.length) {
                throw new IOException("short test read");
            }
            System.arraycopy(bytes, Math.toIntExact(address), target, offset, length);
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
