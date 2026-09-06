package com.github.fmaiassistent.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public interface ProcessMemoryReader extends AutoCloseable {
    long MAX_USER_ADDRESS = 0x00007FFFFFFFFFFFL;

    int pid();

    default Platform platform() {
        return Platform.UNKNOWN;
    }

    byte[] readBytes(long address, int size) throws IOException;

    default void readInto(long address, byte[] target, int offset, int length) throws IOException {
        validateRead(address, target, offset, length);
        byte[] bytes = readBytes(address, length);
        System.arraycopy(bytes, 0, target, offset, length);
    }

    List<MemoryRegion> maps() throws IOException;

    default int readU8(long address) throws IOException {
        byte[] bytes = PrimitiveBuffer.BYTES.get();
        readInto(address, bytes, 0, 1);
        return Byte.toUnsignedInt(bytes[0]);
    }

    default int readU16(long address) throws IOException {
        byte[] bytes = PrimitiveBuffer.BYTES.get();
        readInto(address, bytes, 0, 2);
        return Byte.toUnsignedInt(bytes[0]) | Byte.toUnsignedInt(bytes[1]) << 8;
    }

    default short readI16(long address) throws IOException {
        return (short) readU16(address);
    }

    default long readU32(long address) throws IOException {
        return Integer.toUnsignedLong(readI32(address));
    }

    default int readI32(long address) throws IOException {
        byte[] bytes = PrimitiveBuffer.BYTES.get();
        readInto(address, bytes, 0, 4);
        return Byte.toUnsignedInt(bytes[0])
                | Byte.toUnsignedInt(bytes[1]) << 8
                | Byte.toUnsignedInt(bytes[2]) << 16
                | bytes[3] << 24;
    }

    default long readU64(long address) throws IOException {
        byte[] bytes = PrimitiveBuffer.BYTES.get();
        readInto(address, bytes, 0, 8);
        return Integer.toUnsignedLong(littleEndianInt(bytes, 0))
                | (long) littleEndianInt(bytes, 4) << 32;
    }

    default Optional<Long> qwordOrNull(long address) {
        if (address <= 0 || address > MAX_USER_ADDRESS) {
            return Optional.empty();
        }
        try {
            long value = readU64(address);
            if (value <= 0 || value > MAX_USER_ADDRESS) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    default Optional<String> readFmLenString(long address, int maxLen) {
        if (address <= 0 || address > MAX_USER_ADDRESS) {
            return Optional.empty();
        }
        try {
            int size = readI32(address);
            if (size <= 0 || size > maxLen) {
                return Optional.empty();
            }
            byte[] data = readBytes(address + 4, size);
            for (byte b : data) {
                int ch = b & 0xff;
                if (ch != 9 && ch != 10 && ch != 13 && (ch < 32 || ch >= 0xf5)) {
                    return Optional.empty();
                }
            }
            return Optional.of(new String(data, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    default Optional<String> readFmStringObject(long address, int maxLen) {
        return qwordOrNull(address).flatMap(target -> readFmLenString(target, maxLen));
    }

    static void validateRead(long address, byte[] target, int offset, int length) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (address < 0 || length < 0 || offset < 0 || offset > target.length - length
                || address > MAX_USER_ADDRESS - length) {
            throw new IllegalArgumentException("invalid process-memory read bounds");
        }
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | bytes[offset + 3] << 24;
    }

    final class PrimitiveBuffer {
        private static final ThreadLocal<byte[]> BYTES = ThreadLocal.withInitial(() -> new byte[Long.BYTES]);

        private PrimitiveBuffer() {
        }
    }

    @Override
    void close() throws IOException;

    enum Platform {
        LINUX,
        WINDOWS,
        UNKNOWN
    }
}
