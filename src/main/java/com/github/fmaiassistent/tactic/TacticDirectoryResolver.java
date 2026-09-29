package com.github.fmaiassistent.tactic;

import com.github.fmaiassistent.steam.SteamLibraries;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves Football Manager 26 tactics folders on disk and lists {@code .fmf} files.
 * Besides the native documents folder, every Steam library listed in
 * {@code libraryfolders.vdf} is probed for the FM26 (appid 3551340) Proton prefix,
 * so custom library locations such as {@code /mnt/.../Steam} are found automatically.
 * Read-only: never writes to the FM26 folders.
 */
@Component
public final class TacticDirectoryResolver {
    static final String DIRECTORY_PROPERTY = "fmaiassistent.tactics.directory";
    private static final int MAX_LISTED = 50;

    private final List<Path> directories;

    @Autowired
    public TacticDirectoryResolver() {
        this(directoriesForHome(Path.of(System.getProperty("user.home"))));
    }

    public TacticDirectoryResolver(Path directory) {
        this(directory == null ? List.of() : List.of(directory.toAbsolutePath().normalize()));
    }

    TacticDirectoryResolver(List<Path> directories) {
        this.directories = directories == null ? List.of() : directories.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    /** Primary tactics folder for display: first existing candidate, else the native default. */
    public Path directory() {
        return directories.stream().filter(Files::isDirectory).findFirst()
                .orElseGet(() -> directories.isEmpty()
                        ? Path.of(System.getProperty("user.home"),
                                "Documents/Sports Interactive/Football Manager 26/tactics")
                                .toAbsolutePath().normalize()
                        : directories.getFirst());
    }

    /** All known tactics folders. */
    public List<Path> candidateDirectories() {
        return directories;
    }

    /** True when the path lies inside any known tactics folder. Used to guard disk loads. */
    public boolean contains(Path path) {
        if (path == null) {
            return false;
        }
        Path resolved = path.toAbsolutePath().normalize();
        return directories.stream().anyMatch(resolved::startsWith);
    }

    /** Merges {@code .fmf} files from every existing tactics folder, newest first. */
    public List<DiskTactic> listTactics() {
        List<DiskTactic> merged = new ArrayList<>();
        for (Path candidate : directories) {
            merged.addAll(listTactics(candidate));
        }
        merged.sort(Comparator.comparing(DiskTactic::lastModified).reversed()
                .thenComparing(DiskTactic::fileName, String.CASE_INSENSITIVE_ORDER));
        return merged.size() > MAX_LISTED ? List.copyOf(merged.subList(0, MAX_LISTED)) : List.copyOf(merged);
    }

    public static List<DiskTactic> listTactics(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<DiskTactic> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)
                        || path.getFileName() == null
                        || !isFmfFileName(path.getFileName().toString())) {
                    continue;
                }
                try {
                    found.add(new DiskTactic(
                            path.getFileName().toString(),
                            path.toAbsolutePath().normalize(),
                            Files.getLastModifiedTime(path).toInstant(),
                            Files.size(path)));
                } catch (IOException ignored) {
                    // Skip unreadable entries; listing must never fail the UI.
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        found.sort(Comparator.comparing(DiskTactic::lastModified).reversed()
                .thenComparing(DiskTactic::fileName, String.CASE_INSENSITIVE_ORDER));
        return found.size() > MAX_LISTED ? List.copyOf(found.subList(0, MAX_LISTED)) : List.copyOf(found);
    }

    static Path resolveDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        List<Path> candidates = candidates(Path.of(System.getProperty("user.home")));
        return candidates.stream().filter(Files::isDirectory).findFirst().orElse(candidates.getFirst());
    }

    static List<Path> candidates(Path home) {
        return directoriesForHome(home);
    }

    private static List<Path> directoriesForHome(Path home) {
        String suffix = "Sports Interactive/Football Manager 26/tactics";
        List<Path> candidates = new ArrayList<>();
        candidates.add(home.resolve("Documents/" + suffix));
        for (Path userDir : SteamLibraries.fmProtonUserDirectories(home)) {
            candidates.add(userDir.resolve("Documents/" + suffix));
            candidates.add(userDir.resolve("AppData/Local/" + suffix.replace("tactics", "cloud/tactics")));
        }
        return candidates.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    }

    /** Steam libraries from {@code libraryfolders.vdf}, plus the default location as fallback. */
    static List<Path> steamLibraries(Path home) {
        return SteamLibraries.libraries(home);
    }

    static List<Path> parseLibraryFolders(Path vdf) {
        return SteamLibraries.parseLibraryFolders(vdf);
    }

    static boolean isFmfFileName(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".fmf");
    }

    public record DiskTactic(String fileName, Path path, Instant lastModified, long size) {
    }
}
