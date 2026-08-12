package com.github.fmaiassistent.tactic;

import io.airlift.compress.zstd.ZstdOutputStream;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FmfTacticParserTest {
    private final FmfTacticParser parser = new FmfTacticParser();

    @Test
    void decryptsAndDecodesTacticResourceFromFmfArchive() {
        var metadata = parser.parse(fmf("4-2-4-press"));

        assertThat(metadata.internalName()).isEqualTo("4-2-4-press");
        assertThat(metadata.resources()).containsExactly("4-2-4-press.tac");
        assertThat(metadata.tactic().name()).isEqualTo("4-2-4-press");
        assertThat(metadata.tactic().tacticalStyle()).isEqualTo("Custom Wing Play");
        assertThat(metadata.tactic().mentality()).isEqualTo("Positive");
        assertThat(metadata.tactic().passingDirectness()).isEqualTo("Shorter");
        assertThat(metadata.tactic().attackingTransition()).isEqualTo("Standard");
        assertThat(metadata.tactic().attackingWidth()).isEqualTo("Wider");
        assertThat(metadata.tactic().creativeFreedom()).isEqualTo("Balanced");
        assertThat(metadata.tactic().timeWasting()).isEqualTo("Standard");
        assertThat(metadata.tactic().inPossession().getFirst().description())
                .isEqualTo("Ball-Playing Goalkeeper (Support)");
        assertThat(metadata.tactic().outOfPossession().getFirst().description())
                .isEqualTo("Sweeper Keeper (Attack)");
    }

    @Test
    void rejectsNonFmfData() {
        assertThatThrownBy(() -> parser.parse("not-an-fmf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a supported");
    }

    static byte[] fmf(String name) {
        byte[] tactic = tactic(name);
        byte[] compressedTactic = compress(tactic);
        byte[] key = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16};
        byte[] iv = new byte[]{
                16, 15, 14, 13, 12, 11, 10, 9,
                8, 7, 6, 5, 4, 3, 2, 1};
        byte[] ciphertext = encrypt(compressedTactic, key, iv);
        ByteArrayOutputStream resource = new ByteArrayOutputStream();
        integer(resource, key.length);
        integer(resource, iv.length);
        resource.writeBytes(key);
        resource.writeBytes(iv);
        resource.writeBytes(ciphertext);

        ByteArrayOutputStream catalog = new ByteArrayOutputStream();
        string(catalog, name);
        integer(catalog, 1);
        string(catalog, name);
        string(catalog, ".tac");
        longValue(catalog, 0);
        longValue(catalog, resource.size());
        longValue(catalog, tactic.length);
        catalog.writeBytes(new byte[16]);
        integer(catalog, 0);
        byte[] compressedCatalog = compress(catalog.toByteArray());

        int catalogOffset = 26 + resource.size();
        byte[] header = new byte[26];
        System.arraycopy(new byte[]{2, 1, 'a', 'f', 'e', '.', 8, 0, 0}, 0, header, 0, 9);
        putLong(header, 9, catalogOffset - 9L);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.writeBytes(header);
        archive.writeBytes(resource.toByteArray());
        archive.writeBytes(new byte[]{2, 1, 'f', 'm', 'f', '.', 8, 0, 0});
        archive.writeBytes(compressedCatalog);
        return archive.toByteArray();
    }

    static byte[] tactic(String name) {
        ByteArrayOutputStream tactic = new ByteArrayOutputStream();
        tactic.writeBytes(new byte[]{
                3, 1, 'c', 'a', 't', '.', 0x22, 0, 0x22, 'B', 0, 0x1a, 3, 0, 1, 2});
        string(tactic, name);
        tactic.writeBytes(new byte[12]);
        tactic.writeBytes(new byte[]{4, 2, 5, 6, 2, 3});
        tactic.writeBytes(new byte[12]);
        tactic.write(0xff);
        string(tactic, "Custom Wing Play");
        tactic.writeBytes(new byte[]{'G', 'N', 'I', 'W'});
        role(tactic, 1, 4096L | 0x400000L);
        role(tactic, 1, 2L | 0x800000L);
        return tactic.toByteArray();
    }

    private static void role(ByteArrayOutputStream output, int position, long selection) {
        output.writeBytes(new byte[]{'B', 0, 2});
        integer(output, position);
        output.writeBytes(new byte[]{(byte) 0xff, 0, 1, 1});
        integer(output, 0);
        integer(output, 0);
        longValue(output, selection);
    }

    private static byte[] compress(byte[] bytes) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream output = new ZstdOutputStream(compressed)) {
            output.write(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return compressed.toByteArray();
    }

    private static byte[] encrypt(byte[] bytes, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void string(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void integer(ByteArrayOutputStream output, int value) {
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array());
    }

    private static void longValue(ByteArrayOutputStream output, long value) {
        output.writeBytes(ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value)
                .array());
    }

    private static void putLong(byte[] target, int offset, long value) {
        ByteBuffer.wrap(target, offset, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value);
    }
}
