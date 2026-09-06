package com.github.fmaiassistent.ai;

import com.github.fmaiassistent.FmAiAssistentApplication;
import org.springframework.boot.system.ApplicationHome;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves provider workspaces consistently for IDE runs and packaged applications. */
public final class WorkspaceResolver {
    private static final int MAX_PARENT_DEPTH = 8;

    private WorkspaceResolver() {
    }

    public static Path resolve(String configuredDirectory) {
        String value = configuredDirectory == null || configuredDirectory.isBlank() ? "." : configuredDirectory;
        Path configured = Path.of(value);
        return configured.isAbsolute()
                ? configured.normalize()
                : applicationDirectory().resolve(configured).normalize();
    }

    static Path findProjectRoot(Path start) {
        Path candidate = start;
        for (int depth = 0; candidate != null && depth < MAX_PARENT_DEPTH; depth++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) || Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return null;
    }

    private static Path applicationDirectory() {
        try {
            Path home = new ApplicationHome(FmAiAssistentApplication.class)
                    .getDir()
                    .toPath()
                    .toAbsolutePath()
                    .normalize();
            Path root = findProjectRoot(home);
            return root == null ? home : root;
        } catch (RuntimeException ignored) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
    }
}
