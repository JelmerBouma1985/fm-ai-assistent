package com.github.fmaiassistent.tactic;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TacticContextServiceTest {
    @Test
    void uploadedFmfIsDecodedAndEnrichesAgentPrompt() {
        TacticContextService service = service();

        TacticContext context = service.loadUploads(
                Map.of("tactic.fmf", FmfTacticParserTest.fmf("4-2-4-press")));

        assertThat(context.title()).isEqualTo("4-2-4-press");
        assertThat(context.importedFiles()).containsExactly("tactic.fmf");
        assertThat(context.warnings()).isEmpty();
        assertThat(context.markdown())
                .contains("4-2-4-press.tac")
                .contains("Ball-Playing Goalkeeper (Support)")
                .contains("Sweeper Keeper (Attack)");
        assertThat(service.enrich("codex:thread-1", "How can I improve it?"))
                .contains("<fm26_tactic_context>")
                .contains("How can I improve it?");
        assertThat(service.enrich("codex:thread-1", "And defensively?"))
                .isEqualTo("And defensively?");
        assertThat(service.enrich("antigravity:conversation-1", "Review this"))
                .contains("<fm26_tactic_context>");
    }

    @Test
    void rejectsAnythingOtherThanOneFmfUpload() {
        TacticContextService service = service();

        assertThatThrownBy(() -> service.loadUploads(Map.of(
                "tactic.xml", "<tactic/>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a Football Manager .fmf tactic file can be uploaded");
        assertThatThrownBy(() -> service.loadUploads(Map.of(
                "one.fmf", new byte[]{1}, "two.fmf", new byte[]{2})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Upload exactly one Football Manager .fmf tactic file");
    }

    @Test
    void uploadedContextCanBeCleared() {
        TacticContextService service = service();
        service.loadUploads(Map.of("tactic.fmf", FmfTacticParserTest.fmf("press")));

        assertThat(service.clear().active()).isFalse();
        assertThat(service.enrich("copilot:session", "hello")).isEqualTo("hello");
    }

    private static TacticContextService service() {
        TacticContextProperties properties = new TacticContextProperties(
                DataSize.ofMegabytes(20), 16_000);
        return new TacticContextService(new FmfTacticParser(), properties);
    }
}
