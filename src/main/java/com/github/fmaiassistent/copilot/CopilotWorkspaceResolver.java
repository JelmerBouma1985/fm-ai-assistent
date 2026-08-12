package com.github.fmaiassistent.copilot;

import com.github.fmaiassistent.FmAiAssistentApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
class CopilotWorkspaceResolver {
    private final Path workingDirectory;

    CopilotWorkspaceResolver(CopilotProperties properties) {
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
            Path home = new ApplicationHome(FmAiAssistentApplication.class).getDir().toPath()
                    .toAbsolutePath().normalize();
            Path root = findProjectRoot(home);
            return root == null ? home : root;
        } catch (RuntimeException ignored) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
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
