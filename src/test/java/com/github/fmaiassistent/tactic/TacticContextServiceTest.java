package com.github.fmaiassistent.tactic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TacticContextServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pathImportDecodesFmfWithoutScreenshotsAndEnrichesAgentPrompt() throws Exception {
        Path fmf = temporaryDirectory.resolve("tactic.fmf");
        Files.write(fmf, FmfTacticParserTest.fmf("4-2-4-press"));
        TacticContextService service = service((path, kind) -> {
            throw new AssertionError("Screenshot OCR must not be needed for an FMF import");
        });

        TacticContext context = service.loadPath(fmf.toString());

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
    void uploadedTextFromResourceArchiverBecomesContextAndCanBeCleared() {
        TacticContextService service = service((path, kind) -> "unused");

        TacticContext context = service.loadUploads(Map.of(
                "tactic.xml", "<tactic><tempo>higher</tempo></tactic>".getBytes()));

        assertThat(context.markdown()).contains("tempo", "higher");
        assertThat(service.clear().active()).isFalse();
        assertThat(service.enrich("copilot:session", "hello")).isEqualTo("hello");
    }

    private static TacticContextService service(TacticImageTextExtractor extractor) {
        TacticContextProperties properties = new TacticContextProperties(
                "tesseract", Duration.ofSeconds(5), DataSize.ofMegabytes(20), 16_000);
        return new TacticContextService(new FmfTacticParser(), extractor, properties);
    }
}
