package com.github.fmaiassistent.tactic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "tactic.integration", matches = "true")
class TacticContextLocalIntegrationTest {
    @Test
    void importsRealFmfWithoutCompanionScreenshots() {
        String path = System.getProperty("tactic.path");
        assertThat(path).as("-Dtactic.path must point to the FMF file").isNotBlank();
        TacticContextProperties properties = new TacticContextProperties(
                "tesseract", Duration.ofSeconds(30), DataSize.ofMegabytes(20), 16_000);
        TacticContextService service = new TacticContextService(
                new FmfTacticParser(), new TacticOcrService(properties), properties);

        TacticContext context = service.loadPath(path);

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
