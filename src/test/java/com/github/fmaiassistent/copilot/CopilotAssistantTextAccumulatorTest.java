package com.github.fmaiassistent.copilot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotAssistantTextAccumulatorTest {
    @Test
    void doesNotDuplicateFullMessageAfterStreamingDeltas() {
        var accumulator = new CopilotAssistantTextAccumulator();

        assertThat(accumulator.appendDelta("message-1", "Hello ")).isEqualTo("Hello ");
        assertThat(accumulator.appendDelta("message-1", "world")).isEqualTo("world");
        assertThat(accumulator.appendFinal("message-1", "Hello world")).isEmpty();
        assertThat(accumulator.text()).isEqualTo("Hello world");
    }

    @Test
    void retainsFinalAnswerAfterToolActivityMessages() {
        var accumulator = new CopilotAssistantTextAccumulator();

        accumulator.appendFinal("message-1", "I'll inspect the club first.");
        String addition = accumulator.appendFinal("message-2", "Use a 4-2-3-1 formation.");

        assertThat(addition).isEqualTo("\n\nUse a 4-2-3-1 formation.");
        assertThat(accumulator.text())
                .isEqualTo("I'll inspect the club first.\n\nUse a 4-2-3-1 formation.");
    }
}
