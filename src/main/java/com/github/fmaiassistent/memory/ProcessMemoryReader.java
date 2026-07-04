package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.linux.MemoryRegion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public interface ProcessMemoryReader extends AutoCloseable {
    long MAX_USER_ADDRESS = 0x00007FFFFFFFFFFFL;

    int pid();

    byte[] readBytes(long address, int size) throws IOException;

    List<MemoryRegion> maps() throws IOException;

    default int readU8(long address) throws IOException {
        return readBytes(address, 1)[0] & 0xff;
    }

    default int readU16(long address) throws IOException {
        return le(readBytes(address, 2)).getShort() & 0xffff;
    }

    default short readI16(long address) throws IOException {
        return le(readBytes(address, 2)).getShort();
    }

    default long readU32(long address) throws IOException {
        return le(readBytes(address, 4)).getInt() & 0xffffffffL;
    }

    default int readI32(long address) throws IOException {
        return le(readBytes(address, 4)).getInt();
    }

    default long readU64(long address) throws IOException {
        return le(readBytes(address, 8)).getLong();
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
            int size = le(readBytes(address, 4)).getInt();
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

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    void close() throws IOException;
}
