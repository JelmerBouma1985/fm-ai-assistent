package com.github.fmaiassistent.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeAiPromptContextTest {
    @Test
    void combinesProviderNeutralContextIntoOnePrompt() {
        CompositeAiPromptContext context = new CompositeAiPromptContext(List.of(
                key -> "<club>sc Heerenveen</club>",
                key -> "<tactic>4-2-4</tactic>"));

        String prompt = context.enrich("codex:thread", "Assess the squad");

        assertThat(prompt)
                .contains("<club>sc Heerenveen</club>")
                .contains("<tactic>4-2-4</tactic>")
                .contains("User message:\nAssess the squad");
    }

    @Test
    void leavesMessageUntouchedWhenNoContextIsActive() {
        CompositeAiPromptContext context = new CompositeAiPromptContext(List.of(key -> ""));

        assertThat(context.enrich("antigravity:chat", "hello")).isEqualTo("hello");
    }
}
