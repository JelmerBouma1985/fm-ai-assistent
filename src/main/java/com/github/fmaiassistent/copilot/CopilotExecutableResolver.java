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
import java.util.List;

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
            return Files.isExecutable(Path.of(configured))
                    ? Path.of(configured).toAbsolutePath().normalize().toString()
                    : null;
        }

        for (String directory : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!directory.isBlank()) {
                Path candidate = Path.of(directory, configured);
                if (Files.isExecutable(candidate) && Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }

        if (!"copilot".equals(configured)) {
            return null;
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

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
