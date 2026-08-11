package com.github.fmaiassistent.antigravity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AntigravityCommandFactoryTest {
    private final AntigravityExecutableResolver executable = mock(AntigravityExecutableResolver.class);
    private final AntigravityProperties properties = new AntigravityProperties(
            true, "agy", ".", Duration.ofMinutes(15), Duration.ofSeconds(5),
            null, null, null, false);
    private final AntigravityCommandFactory factory = new AntigravityCommandFactory(properties, executable);

    @Test
    void createsNewConversationWithoutResumeFlagAndKeepsPromptLiteral() {
        when(executable.resolve()).thenReturn("/opt/antigravity/agy");
        String prompt = "quotes \" and ' new\nline $HOME; rm && echo | `whoami` — Unicode";

        List<String> command = factory.command(null, prompt);

        assertEquals(List.of(
                "/opt/antigravity/agy",
                "-p",
                prompt,
                "--output-format",
                "stream-json",
                "--print-timeout",
                "900s"), command);
    }

    @Test
    void continuesOnlyTheExplicitConversation() {
        when(executable.resolve()).thenReturn("agy");

        List<String> command = factory.command("conversation-A", "Follow up");

        assertEquals("conversation-A", command.get(command.indexOf("--conversation") + 1));
    }
}
