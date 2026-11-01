package com.github.fmaiassistent.windows.scouting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Reproducible, import-free Windows x64 helper; no compiler is needed at runtime. */
final class ScoutingBridgeImage {
    private final Properties properties;
    private final byte[] image;

    private ScoutingBridgeImage(Properties properties, byte[] image) {
        this.properties = properties;
        this.image = image;
    }

    static ScoutingBridgeImage load() throws IOException {
        try (InputStream input = ScoutingBridgeImage.class.getResourceAsStream(
                "/native/scouting/scouting-bridge.properties")) {
            if (input == null) throw new IOException("The scouting bridge resource is missing");
            byte[] data = input.readNBytes(65_537);
            if (data.length > 65_536) throw new IOException("The scouting bridge resource exceeds its size limit");
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(data));
            byte[] image = HexFormat.of().parseHex(properties.getProperty("image", ""));
            if (image.length < 100 || image.length > 16_384
                    || !sha256(image).equals(properties.getProperty("imageSha256"))) {
                throw new IOException("The scouting bridge image failed verification");
            }
            return new ScoutingBridgeImage(properties, image);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid scouting bridge resource", exception);
        }
    }

    int size() { return image.length; }

    int offset(String symbol) throws IOException {
        try {
            int offset = Integer.parseInt(properties.getProperty(symbol));
            if (offset < 0 || offset > image.length - 8) throw new NumberFormatException();
            return offset;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid scouting bridge symbol: " + symbol, exception);
        }
    }

    byte[] relocate(long base, long control) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(image.clone()).order(ByteOrder.LITTLE_ENDIAN);
        String relocations = properties.getProperty("relocations", "");
        if (!relocations.isBlank()) {
            for (String value : relocations.split(",")) {
                int offset = Integer.parseInt(value);
                if (offset < 0 || offset > image.length - 8) throw new IOException("Invalid bridge relocation");
                long target = bytes.getLong(offset);
                if (target < 0 || target >= image.length) throw new IOException("Invalid bridge relocation target");
                bytes.putLong(offset, base + target);
            }
        }
        bytes.putLong(offset("control_address"), control);
        return bytes.array();
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
