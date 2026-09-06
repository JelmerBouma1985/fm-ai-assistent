package com.github.fmaiassistent.fmf;

import io.airlift.compress.zstd.ZstdOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedZstdTest {
    @Test
    void decompressesWithinTheDeclaredLimit() throws IOException {
        byte[] raw = "football-manager".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed = compress(raw);

        assertThat(BoundedZstd.decompress(
                compressed, 0, compressed.length, raw.length, raw.length, "fixture"))
                .isEqualTo(raw);
    }

    @Test
    void abortsBeforeWritingPastTheLimit() throws IOException {
        byte[] compressed = compress(new byte[4_097]);

        assertThatThrownBy(() -> BoundedZstd.decompress(
                compressed, 0, compressed.length, -1, 4_096, "fixture"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The fixture is too large");
    }

    @Test
    void rejectsAnImpossibleExpectedLengthBeforeAllocating() {
        assertThatThrownBy(() -> BoundedZstd.decompress(
                new byte[0], 0, 0, 4_097, 4_096, "fixture"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The fixture is too large");
    }

    private static byte[] compress(byte[] bytes) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream output = new ZstdOutputStream(compressed)) {
            output.write(bytes);
        }
        return compressed.toByteArray();
    }
}
