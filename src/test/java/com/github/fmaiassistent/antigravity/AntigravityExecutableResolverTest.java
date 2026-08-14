package com.github.fmaiassistent.antigravity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AntigravityExecutableResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsExtensionlessExecutableOnLinuxPath() throws IOException {
        Path bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Path executable = executableFile(bin.resolve("agy"));

        assertThat(AntigravityExecutableResolver.findInDirectories(
                "agy", List.of(bin), false, List.of()))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void findsExeExecutableOnWindowsPath() throws IOException {
        Path bin = Files.createDirectory(temporaryDirectory.resolve("windows-bin"));
        Path executable = executableFile(bin.resolve("agy.exe"));

        assertThat(AntigravityExecutableResolver.findInDirectories(
                "agy", List.of(bin), true, List.of(".exe", ".cmd")))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void findsStandardWindowsInstallationWhenItIsMissingFromPath() throws IOException {
        Path localAppData = Files.createDirectory(temporaryDirectory.resolve("local-app-data"));
        Path executable = executableFile(localAppData.resolve("agy/bin/agy.exe"));

        assertThat(AntigravityExecutableResolver.findWindowsInstallation(localAppData))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    private static Path executableFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.createFile(path);
        assertThat(path.toFile().setExecutable(true)).isTrue();
        return path;
    }
}
