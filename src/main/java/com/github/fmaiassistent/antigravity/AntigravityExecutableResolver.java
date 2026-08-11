package com.github.fmaiassistent.antigravity;

import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
class AntigravityExecutableResolver {
    private final AntigravityProperties properties;

    AntigravityExecutableResolver(AntigravityProperties properties) {
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
        if ("agy".equals(configured)) {
            Path local = Path.of(System.getProperty("user.home"), ".local", "bin", "agy");
            if (Files.isExecutable(local)) {
                return local.toString();
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
}
