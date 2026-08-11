package com.github.fmaiassistent.codex;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemCodexProcessLauncherTest {
    @Test
    void addsNvmBinDirectorySoEnvCanFindNode() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("PATH", "/usr/local/bin:/usr/bin");

        SystemCodexProcessLauncher.prependExecutableDirectoryToPath(
                builder, "/home/user/.nvm/versions/node/v25/bin/codex");

        assertEquals(String.join(File.pathSeparator, List.of(
                "/home/user/.nvm/versions/node/v25/bin",
                "/usr/local/bin",
                "/usr/bin")), builder.environment().get("PATH"));
    }

    @Test
    void leavesPathAloneForPathResolvedCommands() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("PATH", "/usr/bin");

        SystemCodexProcessLauncher.prependExecutableDirectoryToPath(builder, "codex");

        assertEquals("/usr/bin", builder.environment().get("PATH"));
    }
}
