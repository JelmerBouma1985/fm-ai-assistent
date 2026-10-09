package com.github.fmaiassistent.steam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates Steam libraries on disk by reading {@code libraryfolders.vdf}.
 * FM26 (Steam appid 3551340) runs under Proton, so per-game user files live below
 * {@code steamapps/compatdata/3551340/pfx/...} inside whichever library holds the game.
 * Read-only: never writes to the Steam folders.
 */
public final class SteamLibraries {
    public static final int FM26_APP_ID = 3551340;
    static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
    static final Pattern MANIFEST_BUILD_ID = Pattern.compile("\"buildid\"\\s+\"(\\d+)\"");
    /** Size cap for Steam app manifests (normally about 1 KiB). */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private SteamLibraries() {
    }

    /**
     * Every Steam library root for the given home directory: entries from
     * {@code libraryfolders.vdf} plus the default location as fallback.
     */
    public static List<Path> libraries(Path home) {
        Set<Path> libraries = new LinkedHashSet<>();
        List<Path> roots = List.of(
                home.resolve(".local/share/Steam"),
                home.resolve(".steam/steam"));
        for (Path root : roots) {
            libraries.addAll(parseLibraryFolders(root.resolve("steamapps/libraryfolders.vdf")));
        }
        libraries.add(home.resolve(".local/share/Steam"));
        return libraries.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    }

    /**
     * The Proton user directories ({@code .../pfx/drive_c/users/steamuser}) holding
     * FM26 per library, in library order.
     */
    public static List<Path> fmProtonUserDirectories(Path home) {
        String prefix = "steamapps/compatdata/" + FM26_APP_ID + "/pfx/drive_c/users/steamuser";
        return libraries(home).stream()
                .map(library -> library.resolve(prefix).toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    /**
     * The library holding the given Steam app, detected via its
     * {@code appmanifest_*.acf}; empty when the game is not found in any library.
     */
    public static java.util.Optional<Path> libraryWithApp(Path home, int appId) {
        return libraries(home).stream()
                .filter(library -> Files.isRegularFile(
                        library.resolve("steamapps/appmanifest_" + appId + ".acf")))
                .findFirst();
    }

    /**
     * The installed Steam build id for the given app, read from its
     * {@code appmanifest_*.acf}; empty when the game or manifest is missing,
     * unreadable, oversized or lacks a numeric build id. Read-only.
     */
    public static OptionalLong installedBuildId(Path home, int appId) {
        var manifest = libraryWithApp(home, appId)
                .map(library -> library.resolve("steamapps/appmanifest_" + appId + ".acf"));
        if (manifest.isEmpty()) {
            return OptionalLong.empty();
        }
        String text = readBounded(manifest.get(), MAX_MANIFEST_BYTES);
        if (text == null) {
            return OptionalLong.empty();
        }
        Matcher matcher = MANIFEST_BUILD_ID.matcher(text);
        if (!matcher.find()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException notNumeric) {
            return OptionalLong.empty();
        }
    }

    private static String readBounded(Path file, int limit) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    public static List<Path> parseLibraryFolders(Path vdf) {
        String text;
        try {
            text = Files.readString(vdf);
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
        List<Path> libraries = new ArrayList<>();
        Matcher matcher = VDF_PATH.matcher(text);
        while (matcher.find()) {
            try {
                libraries.add(Path.of(matcher.group(1)).toAbsolutePath().normalize());
            } catch (RuntimeException ignored) {
                // Skip malformed entries.
            }
        }
        return List.copyOf(libraries);
    }
}
