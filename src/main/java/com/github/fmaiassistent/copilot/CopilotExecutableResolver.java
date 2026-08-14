package com.github.fmaiassistent.copilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
class CopilotExecutableResolver {
    private static final Logger log = LoggerFactory.getLogger(CopilotExecutableResolver.class);

    private final CopilotProperties properties;

    CopilotExecutableResolver(CopilotProperties properties) {
        this.properties = properties;
    }

    String resolve() {
        String configured = properties.executable();
        if (configured.contains(File.separator) || Path.of(configured).isAbsolute()) {
            return executableCandidates(Path.of(configured)).stream()
                    .filter(CopilotExecutableResolver::isExecutableFile)
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .orElse(null);
        }

        List<Path> pathDirectories = java.util.Arrays.stream(
                        System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(directory -> !directory.isBlank())
                .map(Path::of)
                .toList();
        String fromPath = findInDirectories(
                configured, pathDirectories, isWindows(), windowsExecutableExtensions());
        if (fromPath != null) {
            return fromPath;
        }

        if (!"copilot".equals(configured)) {
            return null;
        }
        if (isWindows()) {
            String windowsInstall = findWindowsInstallation(
                    environmentPath("LOCALAPPDATA"), Path.of(System.getProperty("user.home")));
            if (windowsInstall != null) {
                return windowsInstall;
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>(List.of(
                home.resolve(".local/bin/copilot"),
                home.resolve(".npm-global/bin/copilot"),
                home.resolve(".nvm/current/bin/copilot")));
        Path nvmVersions = home.resolve(".nvm/versions/node");
        if (Files.isDirectory(nvmVersions)) {
            try (var versions = Files.list(nvmVersions)) {
                versions.map(version -> version.resolve("bin/copilot"))
                        .filter(Files::isExecutable)
                        .sorted(Comparator.comparingLong(CopilotExecutableResolver::modified).reversed())
                        .forEach(candidates::add);
            } catch (IOException ex) {
                log.debug("Could not inspect nvm installations for GitHub Copilot", ex);
            }
        }
        return candidates.stream()
                .filter(Files::isExecutable)
                .filter(Files::isRegularFile)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .orElse(null);
    }

    static String findInDirectories(
            String executable, List<Path> directories, boolean windows, List<String> extensions) {
        for (Path directory : directories) {
            for (Path candidate : executableCandidates(directory.resolve(executable), windows, extensions)) {
                if (isExecutableFile(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }
        return null;
    }

    static String findWindowsInstallation(Path localAppData, Path userHome) {
        List<Path> candidates = new ArrayList<>();
        if (localAppData != null) {
            Path winGet = localAppData.resolve("Microsoft/WinGet");
            candidates.add(winGet.resolve("Links/copilot.exe"));
            Path packages = winGet.resolve("Packages");
            if (Files.isDirectory(packages)) {
                try (var entries = Files.list(packages)) {
                    entries.filter(Files::isDirectory)
                            .filter(path -> path.getFileName().toString().startsWith("GitHub.Copilot_"))
                            .map(path -> path.resolve("copilot.exe"))
                            .sorted(Comparator.comparingLong(CopilotExecutableResolver::modified).reversed())
                            .forEach(candidates::add);
                } catch (IOException ex) {
                    log.debug("Could not inspect WinGet installations for GitHub Copilot", ex);
                }
            }
            candidates.add(localAppData.resolve("Programs/GitHub Copilot CLI/copilot.exe"));
            candidates.add(localAppData.resolve("Microsoft/WindowsApps/copilot.exe"));
        }
        if (userHome != null) {
            candidates.add(userHome.resolve("scoop/shims/copilot.exe"));
        }
        return candidates.stream()
                .filter(CopilotExecutableResolver::isExecutableFile)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .orElse(null);
    }

    private static List<Path> executableCandidates(Path path) {
        return executableCandidates(path, isWindows(), windowsExecutableExtensions());
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
        return List.copyOf(candidates);
    }

    private static List<String> windowsExecutableExtensions() {
        String pathExt = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        return java.util.Arrays.stream(pathExt.split(";"))
                .map(String::strip)
                .filter(extension -> !extension.isBlank())
                .map(extension -> extension.startsWith(".") ? extension : "." + extension)
                .toList();
    }

    private static boolean hasWindowsExecutableExtension(Path path, List<String> windowsExtensions) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return windowsExtensions.stream()
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .anyMatch(filename::endsWith);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isExecutableFile(Path path) {
        return Files.isExecutable(path) && Files.isRegularFile(path);
    }

    private static Path environmentPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
