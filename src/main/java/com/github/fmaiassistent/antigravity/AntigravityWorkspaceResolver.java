package com.github.fmaiassistent.antigravity;

import com.github.fmaiassistent.FmAiAssistentApplication;
import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
class AntigravityWorkspaceResolver {
    private final Path workingDirectory;

    AntigravityWorkspaceResolver(AntigravityProperties properties) {
        Path configured = Path.of(properties.workingDirectory());
        workingDirectory = configured.isAbsolute()
                ? configured.normalize()
                : applicationDirectory().resolve(configured).normalize();
    }

    Path workingDirectory() {
        return workingDirectory;
    }

    private static Path applicationDirectory() {
        try {
            var source = FmAiAssistentApplication.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(location)) {
                    return location.getParent();
                }
                Path project = findProjectRoot(location);
                return project == null ? location : project;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Path findProjectRoot(Path start) {
        Path candidate = start;
        for (int depth = 0; candidate != null && depth < 8; depth++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) || Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return null;
    }
}
