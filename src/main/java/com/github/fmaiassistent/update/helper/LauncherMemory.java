package com.github.fmaiassistent.update.helper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** JDK-only launcher configuration support, also shipped in the external updater. */
public final class LauncherMemory {
    private static final int MAX_CONFIG_BYTES = 65_536;

    private LauncherMemory() { }

    public static Path config(Path image, String os) {
        return image.resolve(os.equals("windows") ? "app/FM AI Assistent.cfg" : "lib/app/FM AI Assistent.cfg");
    }

    public static String read(Path config) throws IOException {
        try (var in = Files.newInputStream(config)) {
            byte[] bytes = in.readNBytes(MAX_CONFIG_BYTES + 1);
            if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("Oversized desktop launcher configuration");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static OptionalLong heapBytes(String config) throws IOException {
        String value = null;
        for (String line : javaOptions(config)) {
            if (line.startsWith("java-options=-Xmx")) value = line.substring("java-options=-Xmx".length());
            if (line.startsWith("java-options=-XX:MaxHeapSize=")) value = line.substring("java-options=-XX:MaxHeapSize=".length());
        }
        if (value == null) return OptionalLong.empty();
        try {
            char last = Character.toLowerCase(value.charAt(value.length() - 1));
            long multiplier = switch (last) {
                case 'k' -> 1024L;
                case 'm' -> 1024L * 1024;
                case 'g' -> 1024L * 1024 * 1024;
                default -> 1L;
            };
            String number = multiplier == 1 ? value : value.substring(0, value.length() - 1);
            long bytes = Math.multiplyExact(Long.parseLong(number), multiplier);
            if (bytes <= 0) throw new NumberFormatException();
            return OptionalLong.of(bytes);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid desktop maximum memory option", invalid);
        }
    }

    private static List<String> javaOptions(String config) throws IOException {
        List<String> options = new ArrayList<>();
        int sections = 0;
        boolean inJavaOptions = false;
        for (String line : config.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inJavaOptions = trimmed.equals("[JavaOptions]");
                if (inJavaOptions) sections++;
            } else if (inJavaOptions) {
                options.add(trimmed);
            }
        }
        if (sections != 1) throw new IOException("Expected one desktop JavaOptions section");
        return options;
    }

    public static String withHeapBytes(String config, long bytes) throws IOException {
        if (bytes <= 0) throw new IllegalArgumentException("Maximum memory must be positive");
        javaOptions(config);
        List<String> lines = new ArrayList<>();
        boolean inJavaOptions = false;
        for (String line : config.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) inJavaOptions = trimmed.equals("[JavaOptions]");
            if (inJavaOptions && (trimmed.startsWith("java-options=-Xmx")
                    || trimmed.startsWith("java-options=-XX:MaxHeapSize="))) continue;
            lines.add(line);
            if (trimmed.equals("[JavaOptions]")) lines.add("java-options=-Xmx" + bytes);
        }
        return String.join(config.contains("\r\n") ? "\r\n" : "\n", lines);
    }

    public static void write(Path config, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("Oversized desktop launcher configuration");
        Path temporary = Files.createTempFile(config.getParent(), ".memory-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void preserve(Path installed, Path staged, String os) throws IOException {
        var memory = heapBytes(read(config(installed, os)));
        if (memory.isPresent()) {
            Path destination = config(staged, os);
            write(destination, withHeapBytes(read(destination), memory.getAsLong()));
        }
    }
}
