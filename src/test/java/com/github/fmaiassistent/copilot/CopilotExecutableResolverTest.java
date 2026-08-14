package com.github.fmaiassistent.copilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotExecutableResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsExtensionlessExecutableOnLinuxPath() throws IOException {
        Path bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Path executable = executableFile(bin.resolve("copilot"));

        assertThat(CopilotExecutableResolver.findInDirectories(
                "copilot", List.of(bin), false, List.of()))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void findsExeExecutableOnWindowsPath() throws IOException {
        Path wingetPackage = Files.createDirectory(temporaryDirectory.resolve("winget-package"));
        Path executable = executableFile(wingetPackage.resolve("copilot.exe"));

        assertThat(CopilotExecutableResolver.findInDirectories(
                "copilot", List.of(wingetPackage), true, List.of(".exe", ".cmd")))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void findsWinGetInstallationWhenItIsMissingFromPath() throws IOException {
        Path localAppData = Files.createDirectory(temporaryDirectory.resolve("local-app-data"));
        Path packageDirectory = localAppData.resolve(
                "Microsoft/WinGet/Packages/GitHub.Copilot_Microsoft.Winget.Source_8wekyb3d8bbwe");
        Files.createDirectories(packageDirectory);
        Path executable = executableFile(packageDirectory.resolve("copilot.exe"));

        assertThat(CopilotExecutableResolver.findWindowsInstallation(
                localAppData, temporaryDirectory.resolve("home")))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void missingExplicitExecutableReturnsNull() {
        CopilotProperties properties = new CopilotProperties(
                true, "/definitely/missing/copilot", ".", null, null, null, null, null);

        assertThat(new CopilotExecutableResolver(properties).resolve()).isNull();
    }

    private static Path executableFile(Path path) throws IOException {
        Files.createFile(path);
        assertThat(path.toFile().setExecutable(true)).isTrue();
        return path;
    }
}
