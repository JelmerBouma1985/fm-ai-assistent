package com.github.fmaiassistent.antigravity;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
class AntigravityExecutableResolver {
    private final AntigravityProperties properties;

    AntigravityExecutableResolver(AntigravityProperties properties) {
        this.properties = properties;
    }

    String resolve() {
        String configured = properties.executable();
        if (configured.contains(File.separator) || Path.of(configured).isAbsolute()) {
            return executableCandidates(Path.of(configured), isWindows(), windowsExecutableExtensions()).stream()
                    .filter(AntigravityExecutableResolver::isExecutableFile)
                    .findFirst()
                    .map(AntigravityExecutableResolver::absolute)
                    .orElse(configured);
        }
        List<Path> pathDirectories = Arrays.stream(
                        System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(directory -> !directory.isBlank())
                .map(Path::of)
                .toList();
        String fromPath = findInDirectories(
                configured, pathDirectories, isWindows(), windowsExecutableExtensions());
        if (fromPath != null) {
            return fromPath;
        }
        if ("agy".equals(configured)) {
            if (isWindows()) {
                String windowsInstall = findWindowsInstallation(environmentPath("LOCALAPPDATA"));
                if (windowsInstall != null) {
                    return windowsInstall;
                }
            }
            Path local = Path.of(System.getProperty("user.home"), ".local", "bin", "agy");
            if (isExecutableFile(local)) {
                return absolute(local);
            }
        }
        return configured;
    }

    boolean isAvailable() {
        String resolved = resolve();
        if (resolved.contains(File.separator) || Path.of(resolved).isAbsolute()) {
            return Files.isExecutable(Path.of(resolved));
        }
        return false;
    }

    static String findInDirectories(
            String executable, List<Path> directories, boolean windows, List<String> extensions) {
        for (Path directory : directories) {
            for (Path candidate : executableCandidates(directory.resolve(executable), windows, extensions)) {
                if (isExecutableFile(candidate)) {
                    return absolute(candidate);
                }
            }
        }
        return null;
    }

    static String findWindowsInstallation(Path localAppData) {
        if (localAppData == null) {
            return null;
        }
        return List.of(
                        localAppData.resolve("agy/bin/agy.exe"),
                        localAppData.resolve("Programs/Antigravity/bin/agy.exe"),
                        localAppData.resolve("Microsoft/WindowsApps/agy.exe"))
                .stream()
                .filter(AntigravityExecutableResolver::isExecutableFile)
                .findFirst()
                .map(AntigravityExecutableResolver::absolute)
                .orElse(null);
    }

    private static List<Path> executableCandidates(
            Path path, boolean windows, List<String> windowsExtensions) {
        if (!windows || hasWindowsExecutableExtension(path, windowsExtensions)) {
            return List.of(path);
        }
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(path);
        for (String extension : windowsExtensions) {
            candidates.add(path.resolveSibling(path.getFileName() + extension));
        }
        return new ArrayList<>(candidates);
    }

    private static List<String> windowsExecutableExtensions() {
        return Arrays.stream(System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";"))
                .map(String::strip)
                .filter(extension -> !extension.isBlank())
                .map(extension -> extension.startsWith(".") ? extension : "." + extension)
                .toList();
    }

    private static boolean hasWindowsExecutableExtension(Path path, List<String> extensions) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream()
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .anyMatch(filename::endsWith);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isExecutableFile(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static Path environmentPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
