package com.github.fmaiassistent.steam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
