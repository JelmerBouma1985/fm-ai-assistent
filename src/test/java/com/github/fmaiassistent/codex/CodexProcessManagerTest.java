package com.github.fmaiassistent.codex;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexProcessManagerTest {
    @Test
    void reportsMissingExecutableWithoutCrashingSpring() {
        CodexProperties properties = properties("missing-codex");
        CodexProcessManager manager = new CodexProcessManager(properties, (command, cwd) -> {
            throw new IOException("not found");
        }, new CodexWorkspaceResolver(properties), new CodexExecutableResolver(properties));

        CodexException error = assertThrows(CodexException.class, manager::start);

        assertTrue(error.getMessage().contains("not installed"));
        assertTrue(error.getMessage().contains("missing-codex"));
    }

    @Test
    void launchesAppServerWithConfiguredExecutableAndWorkingDirectory() {
        CodexProperties properties = properties("/opt/codex");
        var command = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>();
        var cwd = new java.util.concurrent.atomic.AtomicReference<Path>();
        CodexProcessManager manager = new CodexProcessManager(properties, (value, directory) -> {
            command.set(value);
            cwd.set(directory);
            throw new IOException("stop after capture");
        }, new CodexWorkspaceResolver(properties), new CodexExecutableResolver(properties));

        assertThrows(CodexException.class, manager::start);
        assertEquals(java.util.List.of("/opt/codex", "app-server", "--stdio"), command.get());
        assertEquals(new CodexWorkspaceResolver(properties).workingDirectory(), cwd.get());
    }

    private static CodexProperties properties(String executable) {
        return new CodexProperties(
                true, executable, ".",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50));
    }
}
