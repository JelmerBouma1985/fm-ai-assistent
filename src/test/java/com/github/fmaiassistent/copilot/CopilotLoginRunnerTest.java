package com.github.fmaiassistent.copilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotLoginRunnerTest {
    @TempDir
    Path workingDirectory;

    @Test
    void startsBrowserLoginWithTheResolvedCliAndWorkingDirectory() {
        ProcessBuilder command = CopilotLoginRunner.command(
                "C:/Copilot/copilot.cmd", workingDirectory, Map.of("COPILOT_TEST_MARKER", "present"));

        assertThat(command.command()).containsExactly("C:/Copilot/copilot.cmd", "login", "--web-flow");
        assertThat(command.directory()).isEqualTo(workingDirectory.toFile());
        assertThat(command.environment()).containsEntry("COPILOT_TEST_MARKER", "present");
        assertThat(command.redirectOutput()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
        assertThat(command.redirectError()).isEqualTo(ProcessBuilder.Redirect.DISCARD);
    }
}
