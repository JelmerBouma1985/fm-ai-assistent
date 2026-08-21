package com.github.fmaiassistent.shortlist;

import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Component
public class FmfShortlistFile {
    private static final byte[] AFE_MAGIC = {2, 1, 'a', 'f', 'e', '.', 8, 0, 0};
    private static final byte[] CATALOG_MAGIC = {2, 1, 'f', 'm', 'f', '.', 8, 0, 0};
    private static final byte[] SHORTLIST_MAGIC = {3, 1, 'f', 'l', 's', '.'};
    private static final byte[] DETAILS_MAGIC = {3, 1, 'm', 'o', 'a', '.', 4, 0};
    private static final byte[] IMAGE = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte ARCHIVE_FORMAT = 3;
    private static final byte[] DETAILS_SUFFIX = {
            2, 0, 1, 0, 2, (byte) 0xcd, (byte) 0xc7, (byte) 0xd3, 0x11, 1, 0,
            0x10, 1, 0x0b, 0x6d, 7, 0x2e, 1, (byte) 0xb6, 0x64, 0x0b, 0x5e};
    private static final int ARCHIVE_DATA_OFFSET = 26;
    private static final int MAX_RESOURCE_SIZE = 32 * 1024 * 1024;
    private static final int MAX_PLAYERS = 10_000;
    private static final long MAX_UNIQUE_ID = 0xffff_ffffL;
    private static final long EMPTY_RESOURCE_TIMESTAMP = -62_135_596_800L;
    private static final int FM26_DATABASE_VERSION = 0x006281a3;

    private final SecureRandom random = new SecureRandom();

    public Shortlist read(byte[] archive) {
        CatalogDirectory root = catalog(archive);
        List<CatalogFile> resources = root.allFiles();
        CatalogFile shortlistResource = resources.stream()
                .filter(file -> ".slf".equalsIgnoreCase(file.extension()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The FMF archive contains no player shortlist resource"));
        byte[] bytes = extractResource(archive, shortlistResource);
        return decodeShortlist(bytes, resources.stream().map(CatalogFile::fileName).toList());
    }

    public byte[] write(String shortlistName, List<Long> playerUniqueIds) {
        String name = requireName(shortlistName);
        List<Long> uniqueIds = requireUniqueIds(playerUniqueIds);

        Resource details = resource("_data/", "details", ".aom", details(name));
        Resource image = resource("", "image", ".img", IMAGE);
        Resource shortlist = resource("", name, ".slf", shortlist(name, uniqueIds));
        List<Resource> storedResources = List.of(details, image, shortlist);

        long offset = 0;
        List<Resource> positioned = new ArrayList<>(storedResources.size());
        for (Resource resource : storedResources) {
            positioned.add(resource.at(offset));
            offset += resource.storedBytes().length;
        }

        byte[] catalog = compress(catalog(name, positioned));
        int catalogOffset = Math.toIntExact(ARCHIVE_DATA_OFFSET + offset);
        byte[] header = new byte[ARCHIVE_DATA_OFFSET];
        System.arraycopy(AFE_MAGIC, 0, header, 0, AFE_MAGIC.length);
        putLong(header, 9, catalogOffset - 9L);
        header[17] = 0x11;
        header[25] = ARCHIVE_FORMAT;

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.writeBytes(header);
        positioned.forEach(resource -> archive.writeBytes(resource.storedBytes()));
        archive.writeBytes(CATALOG_MAGIC);
        archive.writeBytes(catalog);
        return archive.toByteArray();
    }

    private Shortlist decodeShortlist(byte[] bytes, List<String> resources) {
        if (!matchesAt(bytes, 0, SHORTLIST_MAGIC) || bytes.length < 18) {
            throw new IllegalArgumentException("The embedded data is not a supported FM26 player shortlist");
        }
        long databaseVersion = Integer.toUnsignedLong(littleEndianInt(bytes, 9));
        Cursor cursor = new Cursor(bytes, 13);
        String name = cursor.string();
        int count = cursor.integer();
        if (count < 0 || count > MAX_PLAYERS || cursor.remaining() < (long) count * Integer.BYTES) {
            throw new IllegalArgumentException("The embedded player shortlist is damaged");
        }
        List<Long> uniqueIds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            uniqueIds.add(Integer.toUnsignedLong(cursor.integer()));
        }
        return new Shortlist(name, databaseVersion, List.copyOf(uniqueIds), resources);
    }

    private Resource resource(String directory, String baseName, String extension, byte[] rawBytes) {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        random.nextBytes(key);
        random.nextBytes(iv);
        byte[] compressed = compress(rawBytes);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, compressed, key, iv);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        integer(stored, key.length);
        integer(stored, iv.length);
        stored.writeBytes(key);
        stored.writeBytes(iv);
        stored.writeBytes(ciphertext);
        return new Resource(directory, baseName, extension, rawBytes.length, stored.toByteArray(), 0);
    }

    private static byte[] shortlist(String name, List<Long> uniqueIds) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(SHORTLIST_MAGIC);
        output.writeBytes(new byte[]{2, 0, 1});
        integer(output, FM26_DATABASE_VERSION);
        string(output, name);
        integer(output, uniqueIds.size());
        uniqueIds.forEach(id -> integer(output, (int) (long) id));
        output.write(0);
        return output.toByteArray();
    }

    private static byte[] details(String name) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(DETAILS_MAGIC);
        string(output, "PLAYER_SHORTLIST_TYPE_HANDLER");
        string(output, name);
        output.writeBytes(new byte[10]);
        output.writeBytes(new byte[16]);
        byte[] bytes = output.toByteArray();
        for (int index = bytes.length - 16; index < bytes.length; index++) {
            bytes[index] = (byte) 0xff;
        }
        output.reset();
        output.writeBytes(bytes);
        string(output, "FM AI Assistent");
        output.writeBytes(DETAILS_SUFFIX);
        return output.toByteArray();
    }

    private static byte[] catalog(String name, List<Resource> resources) {
        Resource image = find(resources, "image.img");
        Resource shortlist = resources.stream().filter(resource -> ".slf".equals(resource.extension())).findFirst().orElseThrow();
        Resource details = find(resources, "_data/details.aom");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        string(output, name);
        integer(output, 2);
        catalogFile(output, image);
        catalogFile(output, shortlist);
        integer(output, 1);
        string(output, "_data");
        integer(output, 1);
        catalogFile(output, details);
        integer(output, 0);
        return output.toByteArray();
    }

    private static Resource find(List<Resource> resources, String fileName) {
        return resources.stream().filter(resource -> resource.fileName().equals(fileName)).findFirst().orElseThrow();
    }

    private static void catalogFile(ByteArrayOutputStream output, Resource resource) {
        string(output, resource.baseName());
        string(output, resource.extension());
        longValue(output, resource.offset());
        longValue(output, resource.storedBytes().length);
        longValue(output, resource.rawLength());
        longValue(output, EMPTY_RESOURCE_TIMESTAMP);
        longValue(output, EMPTY_RESOURCE_TIMESTAMP);
    }

    private static CatalogDirectory catalog(byte[] archive) {
        if (archive == null || archive.length < ARCHIVE_DATA_OFFSET || !matchesAt(archive, 0, AFE_MAGIC)
                || archive[25] != ARCHIVE_FORMAT) {
            throw new IllegalArgumentException("This is not a supported Football Manager FMF archive");
        }
        long relativeOffset = littleEndianLong(archive, 9);
        long absoluteOffset = 9 + relativeOffset;
        if (absoluteOffset < ARCHIVE_DATA_OFFSET || absoluteOffset > archive.length - CATALOG_MAGIC.length
                || !matchesAt(archive, Math.toIntExact(absoluteOffset), CATALOG_MAGIC)) {
            throw new IllegalArgumentException("The FMF archive index could not be found");
        }
        byte[] catalog = decompress(
                archive, Math.toIntExact(absoluteOffset) + CATALOG_MAGIC.length,
                archive.length - Math.toIntExact(absoluteOffset) - CATALOG_MAGIC.length,
                -1, "FMF archive index");
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
            throw new IllegalArgumentException("The shortlist resource has invalid archive bounds");
        }
        int offset = Math.toIntExact(absoluteOffset);
        int storedLength = Math.toIntExact(resource.storedLength());
        int keyLength = littleEndianInt(archive, offset);
        int ivLength = littleEndianInt(archive, offset + Integer.BYTES);
        int ciphertextOffset = offset + 2 * Integer.BYTES + keyLength + ivLength;
        int ciphertextLength = storedLength - (ciphertextOffset - offset);
        if (keyLength != 16 || ivLength != 16 || ciphertextLength <= 0) {
            throw new IllegalArgumentException("The shortlist resource uses an unsupported encryption header");
        }
        byte[] key = java.util.Arrays.copyOfRange(archive, offset + 8, offset + 8 + keyLength);
        byte[] iv = java.util.Arrays.copyOfRange(archive, offset + 8 + keyLength, ciphertextOffset);
        byte[] compressed = crypt(
                Cipher.DECRYPT_MODE,
                java.util.Arrays.copyOfRange(archive, ciphertextOffset, ciphertextOffset + ciphertextLength),
                key,
                iv);
        return decompress(compressed, 0, compressed.length, resource.rawLength(), "shortlist resource");
    }

    private static byte[] crypt(int mode, byte[] bytes, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not process the FMF encryption", exception);
        }
    }

    private static byte[] compress(byte[] bytes) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream output = new ZstdOutputStream(compressed)) {
            output.write(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not compress the FMF resource", exception);
        }
        return compressed.toByteArray();
    }

    private static byte[] decompress(byte[] bytes, int offset, int length, long expectedLength, String description) {
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(bytes, offset, length));
                ByteArrayOutputStream output = new ByteArrayOutputStream(expectedLength > 0 ? Math.toIntExact(expectedLength) : 1024)) {
            input.transferTo(output);
            byte[] result = output.toByteArray();
            if (result.length > MAX_RESOURCE_SIZE || (expectedLength >= 0 && result.length != expectedLength)) {
                throw new IllegalArgumentException("The " + description + " has an unexpected uncompressed size");
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("The " + description + " is damaged or unsupported", exception);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("shortlistName is required");
        }
        String stripped = name.strip();
        if (stripped.getBytes(StandardCharsets.UTF_8).length > 200) {
            throw new IllegalArgumentException("shortlistName is too long");
        }
        return stripped;
    }

    private static List<Long> requireUniqueIds(List<Long> uniqueIds) {
        if (uniqueIds == null || uniqueIds.isEmpty()) {
            throw new IllegalArgumentException("At least one player unique ID is required");
        }
        if (uniqueIds.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("A shortlist may contain at most " + MAX_PLAYERS + " players");
        }
        List<Long> result = uniqueIds.stream().distinct().toList();
        if (result.stream().anyMatch(id -> id == null || id <= 0 || id > MAX_UNIQUE_ID)) {
            throw new IllegalArgumentException("Player unique IDs must be unsigned 32-bit values");
        }
        return result;
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
        return ByteBuffer.wrap(bytes, offset, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).getLong();
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

    private static void string(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void integer(ByteArrayOutputStream output, int value) {
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static void longValue(ByteArrayOutputStream output, long value) {
        output.writeBytes(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    private static void putLong(byte[] target, int offset, long value) {
        ByteBuffer.wrap(target, offset, Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
    }

    public record Shortlist(String name, long databaseVersion, List<Long> playerUniqueIds, List<String> resources) {
    }

    private record Resource(String directory, String baseName, String extension, long rawLength, byte[] storedBytes, long offset) {
        Resource at(long newOffset) {
            return new Resource(directory, baseName, extension, rawLength, storedBytes, newOffset);
        }

        String fileName() {
            return directory + baseName + extension;
        }
    }

    private record CatalogFile(String directory, String baseName, String extension, long offset, long storedLength, long rawLength) {
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
                skip(16);
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
            if (value < 0 || value > MAX_PLAYERS) {
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

    private static final class Cursor {
        private final byte[] bytes;
        private int offset;

        private Cursor(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.offset = offset;
        }

        private String string() {
            int length = integer();
            if (length < 0 || length > 4096 || remaining() < length) {
                throw new IllegalArgumentException("The embedded player shortlist is damaged");
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

        private int remaining() {
            return bytes.length - offset;
        }
    }
}
