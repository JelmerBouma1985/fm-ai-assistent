package com.github.fmaiassistent.tactic;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fm26TacticDecoderTest {
    private final Fm26TacticDecoder decoder = new Fm26TacticDecoder();

    @Test
    void mapsPhaseSpecificPositionsRolesAndDuties() {
        var tactic = decoder.decode(FmfTacticParserTest.tactic("test tactic"));

        assertThat(tactic.markdown())
                .contains("Tactical style: Custom Wing Play")
                .contains("Mentality: Positive")
                .contains("Passing directness: Shorter")
                .contains("Attacking width: Wider")
                .contains("### In possession")
                .contains("GK: Ball-Playing Goalkeeper (Support)")
                .contains("### Out of possession")
                .contains("GK: Sweeper Keeper (Attack)");
    }

    @Test
    void rejectsUnsupportedTacticPayload() {
        assertThatThrownBy(() -> decoder.decode("plain text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a supported FM26 tactic");
    }
}
