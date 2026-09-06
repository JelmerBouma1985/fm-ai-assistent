package com.github.fmaiassistent.fmf;

import io.airlift.compress.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Bounded Zstandard decompression shared by all Football Manager archive readers. */
public final class BoundedZstd {
    private static final int BUFFER_SIZE = 8 * 1024;

    private BoundedZstd() {
    }

    public static byte[] decompress(
            byte[] compressed,
            int offset,
            int length,
            long expectedLength,
            int maximumLength,
            String description) {
        if (compressed == null || offset < 0 || length < 0 || offset > compressed.length - length) {
            throw new IllegalArgumentException("The " + description + " has invalid compressed bounds");
        }
        if (maximumLength < 0 || expectedLength > maximumLength || expectedLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The " + description + " is too large");
        }

        int initialCapacity = expectedLength > 0
                ? Math.toIntExact(expectedLength)
                : Math.min(BUFFER_SIZE, maximumLength);
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(compressed, offset, length));
                ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            while (true) {
                int read = input.read(buffer, 0, Math.min(buffer.length, maximumLength - total + 1));
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maximumLength) {
                    throw new IllegalArgumentException("The " + description + " is too large");
                }
                output.write(buffer, 0, read);
            }
            if (expectedLength >= 0 && total != expectedLength) {
                throw new IllegalArgumentException(
                        "The " + description + " has an unexpected uncompressed size");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "The " + description + " is damaged or unsupported", exception);
        }
    }
}
