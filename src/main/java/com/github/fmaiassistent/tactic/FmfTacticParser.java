package com.github.fmaiassistent.tactic;

import io.airlift.compress.zstd.ZstdInputStream;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

@Component
class FmfTacticParser {
    private static final byte[] AFE_MAGIC = {2, 1, 'a', 'f', 'e', '.'};
    private static final byte[] CATALOG_MAGIC = {2, 1, 'f', 'm', 'f', '.', 8, 0, 0};
    private static final int ARCHIVE_DATA_OFFSET = 26;
    private static final int MAX_RESOURCE_SIZE = 32 * 1024 * 1024;

    private final Fm26TacticDecoder tacticDecoder = new Fm26TacticDecoder();

    FmfMetadata parse(byte[] archive) {
        if (archive.length < ARCHIVE_DATA_OFFSET || !startsWith(archive, AFE_MAGIC)) {
            throw new IllegalArgumentException("This is not a supported Football Manager FMF archive");
        }

        int catalogOffset = catalogOffset(archive);
        byte[] catalog = decompress(
                archive, catalogOffset + CATALOG_MAGIC.length,
                archive.length - catalogOffset - CATALOG_MAGIC.length,
                -1, "FMF archive index");
        CatalogDirectory root = parseCatalog(catalog);
        List<CatalogFile> resources = root.allFiles();
        CatalogFile tacticResource = resources.stream()
                .filter(file -> ".tac".equalsIgnoreCase(file.extension()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The FMF archive contains no tactic resource"));

        byte[] tacticBytes = extractResource(archive, tacticResource);
        Fm26TacticDecoder.DecodedTactic tactic = tacticDecoder.decode(tacticBytes);
        List<String> names = resources.stream().map(CatalogFile::fileName).toList();
        return new FmfMetadata(root.name(), names, tactic);
    }

    private static int catalogOffset(byte[] archive) {
        long relativeOffset = littleEndianLong(archive, 9);
        long offset = 9 + relativeOffset;
        if (offset < ARCHIVE_DATA_OFFSET || offset > archive.length - CATALOG_MAGIC.length
                || !matchesAt(archive, Math.toIntExact(offset), CATALOG_MAGIC)) {
            throw new IllegalArgumentException("The FMF archive index could not be found");
        }
        return Math.toIntExact(offset);
    }

    private static CatalogDirectory parseCatalog(byte[] catalog) {
        try {
            CatalogCursor cursor = new CatalogCursor(catalog);
            String rootName = cursor.string();
            CatalogDirectory root = cursor.directory(rootName, "");
            if (root.allFiles().isEmpty()) {
                throw new IllegalArgumentException("The FMF archive index contains no resources");
            }
            return root;
        } catch (IndexOutOfBoundsException | ArithmeticException exception) {
            throw new IllegalArgumentException("The FMF archive index is damaged or unsupported", exception);
        }
    }

    private static byte[] extractResource(byte[] archive, CatalogFile resource) {
        long absoluteOffset = ARCHIVE_DATA_OFFSET + resource.offset();
        long end = absoluteOffset + resource.storedLength();
        if (resource.storedLength() < 8 || absoluteOffset < ARCHIVE_DATA_OFFSET
                || end > archive.length || resource.rawLength() > MAX_RESOURCE_SIZE) {
            throw new IllegalArgumentException("The tactic resource has invalid archive bounds");
        }

        int offset = Math.toIntExact(absoluteOffset);
        int storedLength = Math.toIntExact(resource.storedLength());
        int keyLength = littleEndianInt(archive, offset);
        int ivLength = littleEndianInt(archive, offset + Integer.BYTES);
        int ciphertextOffset = offset + 2 * Integer.BYTES + keyLength + ivLength;
        int ciphertextLength = storedLength - (ciphertextOffset - offset);
        if (keyLength != 16 || ivLength != 16 || ciphertextLength <= 0) {
            throw new IllegalArgumentException("The tactic resource uses an unsupported encryption header");
        }

        try {
            SecretKeySpec key = new SecretKeySpec(archive, offset + 8, keyLength, "AES");
            IvParameterSpec iv = new IvParameterSpec(archive, offset + 8 + keyLength, ivLength);
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, iv);
            byte[] compressed = cipher.doFinal(archive, ciphertextOffset, ciphertextLength);
            return decompress(compressed, 0, compressed.length, resource.rawLength(), "tactic resource");
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("The tactic resource could not be decrypted", exception);
        }
    }

    private static byte[] decompress(
            byte[] bytes, int offset, int length, long expectedLength, String description) {
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(bytes, offset, length));
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        expectedLength > 0 ? Math.toIntExact(expectedLength) : 1024)) {
            input.transferTo(output);
            byte[] result = output.toByteArray();
            if (result.length > MAX_RESOURCE_SIZE) {
                throw new IllegalArgumentException(description + " is too large");
            }
            if (expectedLength >= 0 && result.length != expectedLength) {
                throw new IllegalArgumentException(description + " has an unexpected uncompressed size");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("The " + description + " is damaged or unsupported", exception);
        }
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length - Integer.BYTES) {
            throw new IndexOutOfBoundsException();
        }
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }

    private static long littleEndianLong(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length - Long.BYTES) {
            throw new IndexOutOfBoundsException();
        }
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result |= (long) Byte.toUnsignedInt(bytes[offset + index]) << index * 8;
        }
        return result;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return matchesAt(bytes, 0, prefix);
    }

    private static boolean matchesAt(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || offset > bytes.length - expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    record FmfMetadata(
            String internalName,
            List<String> resources,
            Fm26TacticDecoder.DecodedTactic tactic) {
    }

    private record CatalogFile(
            String directory,
            String baseName,
            String extension,
            long offset,
            long storedLength,
            long rawLength) {
        String fileName() {
            return directory + baseName + extension;
        }
    }

    private record CatalogDirectory(String name, List<CatalogFile> files, List<CatalogDirectory> directories) {
        List<CatalogFile> allFiles() {
            List<CatalogFile> result = new ArrayList<>(files);
            directories.forEach(directory -> result.addAll(directory.allFiles()));
            return List.copyOf(result);
        }
    }

    private static final class CatalogCursor {
        private final byte[] bytes;
        private int offset;

        private CatalogCursor(byte[] bytes) {
            this.bytes = bytes;
        }

        private CatalogDirectory directory(String name, String path) {
            int fileCount = count();
            List<CatalogFile> files = new ArrayList<>(fileCount);
            for (int index = 0; index < fileCount; index++) {
                String baseName = string();
                String extension = string();
                long dataOffset = longValue();
                long storedLength = longValue();
                long rawLength = longValue();
                skip(16); // Resource timestamps used by FM, not needed for tactic decoding.
                files.add(new CatalogFile(path, baseName, extension, dataOffset, storedLength, rawLength));
            }

            int directoryCount = count();
            List<CatalogDirectory> directories = new ArrayList<>(directoryCount);
            for (int index = 0; index < directoryCount; index++) {
                String childName = string();
                directories.add(directory(childName, path + childName + "/"));
            }
            return new CatalogDirectory(name, List.copyOf(files), List.copyOf(directories));
        }

        private int count() {
            int value = integer();
            if (value < 0 || value > 10_000) {
                throw new IllegalArgumentException("The FMF archive index contains an invalid item count");
            }
            return value;
        }

        private String string() {
            int length = integer();
            if (length < 0 || length > 4096 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            String value = new String(bytes, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        private int integer() {
            int value = littleEndianInt(bytes, offset);
            offset += Integer.BYTES;
            return value;
        }

        private long longValue() {
            long value = littleEndianLong(bytes, offset);
            offset += Long.BYTES;
            return value;
        }

        private void skip(int length) {
            if (length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            offset += length;
        }
    }
}
