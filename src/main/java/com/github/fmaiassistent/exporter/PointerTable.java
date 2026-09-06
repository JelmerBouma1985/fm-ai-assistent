package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;

/** A bounded, single-read snapshot of an FM pointer table. */
final class PointerTable {
    private final byte[] bytes;

    private PointerTable(byte[] bytes) {
        this.bytes = bytes;
    }

    static PointerTable read(ProcessMemoryReader reader, FmOffsets.Bounds bounds, String description)
            throws IOException {
        if (bounds.start() < 0 || bounds.end() < bounds.start()
                || (bounds.end() - bounds.start()) % Long.BYTES != 0) {
            throw new IOException("Invalid " + description + " pointer table bounds");
        }
        long count = bounds.count();
        if (count < 0 || count > Integer.MAX_VALUE / Long.BYTES) {
            throw new IOException(description + " pointer table is too large to export");
        }
        int byteCount;
        try {
            byteCount = Math.multiplyExact(Math.toIntExact(count), Long.BYTES);
        } catch (ArithmeticException exception) {
            throw new IOException(description + " pointer table size overflow", exception);
        }
        return new PointerTable(reader.readBytes(bounds.start(), byteCount));
    }

    static PointerTable wrap(byte[] bytes) throws IOException {
        if (bytes.length % Long.BYTES != 0) {
            throw new IOException("Pointer table size is not aligned to 64-bit slots");
        }
        return new PointerTable(bytes);
    }

    int size() {
        return bytes.length / Long.BYTES;
    }

    byte[] bytes() {
        return bytes;
    }

    long pointerAt(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
        int offset = index * Long.BYTES;
        return (bytes[offset] & 0xffL)
                | (bytes[offset + 1] & 0xffL) << 8
                | (bytes[offset + 2] & 0xffL) << 16
                | (bytes[offset + 3] & 0xffL) << 24
                | (bytes[offset + 4] & 0xffL) << 32
                | (bytes[offset + 5] & 0xffL) << 40
                | (bytes[offset + 6] & 0xffL) << 48
                | (bytes[offset + 7] & 0xffL) << 56;
    }
}
