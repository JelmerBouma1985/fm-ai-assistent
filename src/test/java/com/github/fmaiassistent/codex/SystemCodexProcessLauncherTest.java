package com.github.fmaiassistent.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemCodexProcessLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addsNvmBinDirectorySoEnvCanFindNode() {
        Path nvmBin = temporaryDirectory.resolve("nvm/versions/node/v25/bin");
        Path systemBin = temporaryDirectory.resolve("system-bin");
        Path userBin = temporaryDirectory.resolve("user-bin");
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("PATH", String.join(
                File.pathSeparator, systemBin.toString(), userBin.toString()));

        SystemCodexProcessLauncher.prependExecutableDirectoryToPath(
                builder, nvmBin.resolve("codex").toString());

        assertEquals(String.join(File.pathSeparator, List.of(
                nvmBin.toString(), systemBin.toString(), userBin.toString())),
                builder.environment().get("PATH"));
    }

    @Test
    void leavesPathAloneForPathResolvedCommands() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("PATH", "/usr/bin");

        SystemCodexProcessLauncher.prependExecutableDirectoryToPath(builder, "codex");

        assertEquals("/usr/bin", builder.environment().get("PATH"));
    }
}
