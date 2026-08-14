package com.github.fmaiassistent.tactic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "tactic.integration", matches = "true")
class TacticContextLocalIntegrationTest {
    @Test
    void importsRealUploadedFmf() throws Exception {
        String path = System.getProperty("tactic.path");
        assertThat(path).as("-Dtactic.path must point to the FMF test fixture").isNotBlank();
        TacticContextProperties properties = new TacticContextProperties(
                DataSize.ofMegabytes(20), 16_000);
        TacticContextService service = new TacticContextService(new FmfTacticParser(), properties);
        Path fixture = Path.of(path);

        TacticContext context = service.loadUploads(Map.of(
                fixture.getFileName().toString(), Files.readAllBytes(fixture)));

        assertThat(context.active()).isTrue();
        assertThat(context.importedFiles()).containsExactly("tactic.fmf");
        assertThat(context.warnings()).isEmpty();
        assertThat(context.markdown())
                .contains("FMF archive metadata")
                .contains("Decoded FM26 tactic")
                .contains("Mentality: Positive")
                .contains("Inverted Wing-Back")
                .contains("Ball-Playing Centre-Back")
                .contains("Wide Forward")
                .contains("Shadow Striker")
                .contains("Box-to-Box Playmaker")
                .contains("Tracking Wide Midfielder")
                .contains("Screening Defensive Midfielder")
                .contains("Splitting Attacking Midfielder")
                .doesNotContain("Unknown role", "Position 0x");
    }
}
