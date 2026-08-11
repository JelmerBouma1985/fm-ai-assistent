package com.github.fmaiassistent.codex;

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
class CodexExecutableResolver {
    private static final Logger log = LoggerFactory.getLogger(CodexExecutableResolver.class);

    private final CodexProperties properties;

    CodexExecutableResolver(CodexProperties properties) {
        this.properties = properties;
    }

    String resolve() {
        String configured = properties.executable();
        if (configured.contains(File.separator) || Path.of(configured).isAbsolute()) {
            return configured;
        }

        for (String directory : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!directory.isBlank()) {
                Path candidate = Path.of(directory, configured);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }

        if (!"codex".equals(configured)) {
            return configured;
        }
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>(List.of(
                home.resolve(".local/bin/codex"),
                home.resolve(".npm-global/bin/codex"),
                home.resolve(".nvm/current/bin/codex")));
        Path nvmVersions = home.resolve(".nvm/versions/node");
        if (Files.isDirectory(nvmVersions)) {
            try (var versions = Files.list(nvmVersions)) {
                versions.map(version -> version.resolve("bin/codex"))
                        .filter(Files::isExecutable)
                        .sorted(Comparator.comparingLong(CodexExecutableResolver::modified).reversed())
                        .forEach(candidates::add);
            } catch (IOException ex) {
                log.debug("Could not inspect nvm installations for Codex", ex);
            }
        }
        return candidates.stream()
                .filter(Files::isExecutable)
                .findFirst()
                .map(Path::toString)
                .orElse(configured);
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
