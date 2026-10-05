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
    void decodesAllTeamInstructionsFromWinnerBytePatternWithoutStyleInference() {
        byte[] teamSettings = {
                4, 2, 6, 6, 2, 3,
                0, 0x30, 0x01, 0, 0x0a, 0x50, 0x50, 0x21, 0x42, (byte) 0x81, 0, 0};

        var tactic = decoder.decode(FmfTacticParserTest.tactic(
                "4222 WINNER! FM26", "Custom", teamSettings));

        assertThat(tactic.markdown()).contains(
                "Passing directness: Standard",
                "Tempo: Much Higher",
                "Time wasting: Less Often",
                "Attacking transition: Counter Attack",
                "Attacking width: Narrower",
                "Set-piece approach: Keep Ball in Play",
                "Creative freedom: Balanced",
                "Build-up strategy: Balanced",
                "Goal kicks: Short",
                "GK distribution: Center Backs",
                "Supporting runs: Balanced",
                "Dribbling: Encourage",
                "Progress through: Balanced",
                "Pass reception: Balanced",
                "Patience: Standard",
                "Shots from distance: Discourage",
                "Crossing style: Low Crosses",
                "GK distribution speed: Balanced",
                "Line of engagement: High Press",
                "Defensive line: Much Higher",
                "Trigger press: Much More Often",
                "Defensive transition: Counter-Press",
                "Tackling: Get Stuck In",
                "Cross engagement: Balanced",
                "Pressing trap: Balanced",
                "Short goalkeeping distribution: No",
                "Defensive line behaviour: Balanced");
    }

    @Test
    void rejectsUnsupportedTacticPayload() {
        assertThatThrownBy(() -> decoder.decode("plain text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a supported FM26 tactic");
    }
}
