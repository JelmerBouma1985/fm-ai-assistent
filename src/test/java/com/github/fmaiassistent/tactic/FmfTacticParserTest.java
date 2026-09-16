package com.github.fmaiassistent.tactic;

import io.airlift.compress.zstd.ZstdOutputStream;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FmfTacticParserTest {
    private final FmfTacticParser parser = new FmfTacticParser();

    @Test
    void decryptsAndDecodesTacticResourceFromFmfArchive() {
        var metadata = parser.parse(fmf("4-2-4-press"));

        assertThat(metadata.internalName()).isEqualTo("4-2-4-press");
        assertThat(metadata.resources()).containsExactly("4-2-4-press.tac");
        assertThat(metadata.tactic().name()).isEqualTo("4-2-4-press");
        assertThat(metadata.tactic().tacticalStyle()).isEqualTo("Custom Wing Play");
        assertThat(metadata.tactic().mentality()).isEqualTo("Positive");
        assertThat(metadata.tactic().passingDirectness()).isEqualTo("Shorter");
        assertThat(metadata.tactic().tempo()).isEqualTo("Standard");
        assertThat(metadata.tactic().attackingTransition()).isEqualTo("Standard");
        assertThat(metadata.tactic().attackingWidth()).isEqualTo("Wider");
        assertThat(metadata.tactic().creativeFreedom()).isEqualTo("Balanced");
        assertThat(metadata.tactic().timeWasting()).isEqualTo("Standard");
        assertThat(metadata.tactic().setPieceApproach()).isEqualTo("Keep Ball in Play");
        assertThat(metadata.tactic().inPossession().getFirst().description())
                .isEqualTo("Ball-Playing Goalkeeper (Support)");
        assertThat(metadata.tactic().outOfPossession().getFirst().description())
                .isEqualTo("Sweeper Keeper (Attack)");
    }

    @Test
    void rejectsNonFmfData() {
        assertThatThrownBy(() -> parser.parse("not-an-fmf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a supported");
    }

    @Test
    void decodesPassingDirectnessAndTempoFromRealFm26Tactics() throws IOException {
        Map<String, String> examples = Map.of(
                "4-2-4-attacking-standard-lower.fmf", "Standard",
                "4-2-4-balanced-much-shorter-standard.fmf", "Much Shorter",
                "4-2-4-cautious-much-more-direct-much-higher.fmf", "Much More Direct",
                "4-2-4-very-attacking-more-direct-much-lower.fmf", "More Direct",
                "4-2-4-sam-twm-press.fmf", "Shorter");
        Map<String, String> tempos = Map.of(
                "4-2-4-attacking-standard-lower.fmf", "Lower",
                "4-2-4-balanced-much-shorter-standard.fmf", "Standard",
                "4-2-4-cautious-much-more-direct-much-higher.fmf", "Much Higher",
                "4-2-4-very-attacking-more-direct-much-lower.fmf", "Much Lower",
                "4-2-4-sam-twm-press.fmf", "Higher");

        for (Map.Entry<String, String> example : examples.entrySet()) {
            String resource = "/tactics/" + example.getKey();
            try (var stream = getClass().getResourceAsStream(resource)) {
                assertThat(stream).as(resource).isNotNull();
                var tactic = parser.parse(stream.readAllBytes()).tactic();
                assertThat(tactic.passingDirectness()).as(example.getKey()).isEqualTo(example.getValue());
                assertThat(tactic.markdown()).contains("Passing directness: " + example.getValue());
                assertThat(tactic.tempo()).as(example.getKey()).isEqualTo(tempos.get(example.getKey()));
                assertThat(tactic.markdown()).contains("Tempo: " + tempos.get(example.getKey()));
            }
        }
    }

    @Test
    void decodesCombinedTeamInstructionsFromRealFm26Tactics() throws IOException {
        assertTeamInstructions(
                "4-2-4-wider-counter-attack-more-expressive.fmf",
                "Much Lower", "More Expressive", "Counter Attack", "Wider");
        assertTeamInstructions(
                "4-2-4-narrower-hold-shape-more-disciplined.fmf",
                "Much Lower", "More Disciplined", "Hold Shape", "Narrower");
    }

    @Test
    void decodesExtremeWidthsAndTimeWastingFromRealFm26Tactics() throws IOException {
        assertWidthAndTimeWasting("4-2-4-much-narrower-less-often.fmf", "Much Narrower", "Less Often");
        assertWidthAndTimeWasting("4-2-4-much-wider-more-often.fmf", "Much Wider", "More Often");
    }

    @Test
    void decodesSetPieceApproachFromRealFm26Tactics() throws IOException {
        assertSetPieceApproach("4-2-4-kip.fmf", "Play for Set Pieces");
        assertSetPieceApproach("4-2-4-kip-2.fmf", "Keep Ball in Play");
        assertSetPieceApproach("4-2-4-sam-twm-press.fmf", "Keep Ball in Play");
    }

    @Test
    void decodesOutOfPossessionInstructionsFromRealFm26Tactics() throws IOException {
        assertInstruction("loe", Map.of(
                "high-press", "High Press",
                "mid-block", "Mid Block",
                "low", "Low Block"),
                Fm26TacticDecoder.DecodedTactic::lineOfEngagement, "Line of engagement");
        assertInstruction("dl", Map.of(
                "much-higher", "Much Higher",
                "higher", "Higher",
                "standard", "Standard",
                "lower", "Lower",
                "much-lower", "Much Lower"),
                Fm26TacticDecoder.DecodedTactic::defensiveLine, "Defensive line");
        assertInstruction("dlb", Map.of(
                "balanced", "Balanced",
                "drop-off-more", "Drop Off More",
                "step-up-more", "Step Up More"),
                Fm26TacticDecoder.DecodedTactic::defensiveLineBehaviour, "Defensive line behaviour");
        assertInstruction("tp", Map.of(
                "much-less-often", "Much Less Often",
                "less-often", "Less Often",
                "standard", "Standard",
                "more-often", "More Often",
                "much-more-often", "Much More Often"),
                Fm26TacticDecoder.DecodedTactic::triggerPress, "Trigger press");
        assertInstruction("dt", Map.of(
                "counter-press", "Counter-Press",
                "standard", "Standard",
                "regroup", "Regroup"),
                Fm26TacticDecoder.DecodedTactic::defensiveTransition, "Defensive transition");
        assertInstruction("t", Map.of(
                "get-stuck-in", "Get Stuck In",
                "standard", "Standard",
                "stay-on-feet", "Stay On Feet"),
                Fm26TacticDecoder.DecodedTactic::tackling, "Tackling");
        assertInstruction("ce", Map.of(
                "balanced", "Balanced",
                "invite-crosses", "Invite Crosses",
                "stop-crosses", "Stop Crosses"),
                Fm26TacticDecoder.DecodedTactic::crossEngagement, "Cross engagement");
        assertInstruction("pt", Map.of(
                "balanced", "Balanced",
                "trap-inside", "Trap Inside",
                "trap-outside", "Trap Outside"),
                Fm26TacticDecoder.DecodedTactic::pressingTrap, "Pressing trap");
        assertInstruction("sgk", Map.of(
                "no", "No",
                "yes", "Yes"),
                Fm26TacticDecoder.DecodedTactic::shortGoalkeepingDistribution,
                "Short goalkeeping distribution");
    }

    @Test
    void decodesInPossessionInstructionsFromRealFm26Tactics() throws IOException {
        assertInstruction("d", Map.of(
                "balanced", "Balanced",
                "discourage", "Discourage",
                "encourage", "Encourage"),
                Fm26TacticDecoder.DecodedTactic::dribbling, "Dribbling");
        assertInstruction("p", Map.of(
                "standard", "Standard",
                "hit-early-crosses", "Hit Early Crosses",
                "work=ball-into-box", "Work Ball Into Box"),
                Fm26TacticDecoder.DecodedTactic::patience, "Patience");
        assertInstruction("sfd", Map.of(
                "balanced", "Balanced",
                "discourage", "Discourage",
                "encourage", "Encourage"),
                Fm26TacticDecoder.DecodedTactic::shotsFromDistance, "Shots from distance");
        assertInstruction("cs", Map.of(
                "balanced", "Balanced",
                "floated-crosses", "Floated Crosses",
                "low-crosses", "Low Crosses",
                "whipped-crosses", "Whipped Crosses"),
                Fm26TacticDecoder.DecodedTactic::crossingStyle, "Crossing style");
        assertInstruction("sr", Map.of(
                "balanced", "Balanced",
                "both-flanks", "Both Flanks",
                "left", "Left",
                "right", "Right"),
                Fm26TacticDecoder.DecodedTactic::supportingRuns, "Supporting runs");
        assertInstruction("prt", Map.of(
                "balanced", "Balanced",
                "both-flanks", "Both Flanks",
                "left", "Left",
                "middle", "Middle",
                "right", "Right"),
                Fm26TacticDecoder.DecodedTactic::progressThrough, "Progress through");
        assertInstruction("pr", Map.of(
                "balanced", "Balanced",
                "pass-into-space", "Pass Into Space",
                "pass-to-feet", "Pass To Feet"),
                Fm26TacticDecoder.DecodedTactic::passReception, "Pass reception");
        assertInstruction("bus", Map.of(
                "balanced", "Balanced",
                "bypass-press", "Bypass Press",
                "play-through-press", "Play Through Press"),
                Fm26TacticDecoder.DecodedTactic::buildUpStrategy, "Build-up strategy");
        assertInstruction("gk", Map.of(
                "mixed", "Mixed",
                "short", "Short",
                "long", "Long"),
                Fm26TacticDecoder.DecodedTactic::goalKicks, "Goal kicks");
        assertInstruction("gds", Map.of(
                "balanced", "Balanced",
                "distribute-quickly", "Distribute Quickly",
                "slow-pace-down", "Slow Pace Down"),
                Fm26TacticDecoder.DecodedTactic::gkDistributionSpeed, "GK distribution speed");
        assertInstruction("gkd", Map.of(
                "balanced", "Balanced",
                "center-backs", "Center Backs",
                "flanks", "Flanks",
                "full-backs", "Full Backs",
                "playmaker", "Playmaker",
                "target-forward", "Target Forward"),
                Fm26TacticDecoder.DecodedTactic::gkDistribution, "GK distribution");
        assertInstruction("bus", Map.of(
                "balanced", "More Direct",
                "play-through-press", "More Direct"),
                Fm26TacticDecoder.DecodedTactic::passingDirectness, "Passing directness");
        assertInstruction("gkd", Map.of("target-forward", "Long"),
                Fm26TacticDecoder.DecodedTactic::goalKicks, "Goal kicks");
    }

    private void assertInstruction(
            String family, Map<String, String> examples,
            Function<Fm26TacticDecoder.DecodedTactic, String> value, String label) throws IOException {
        for (Map.Entry<String, String> example : examples.entrySet()) {
            String fileName = "4-2-4-" + family + "-" + example.getKey() + ".fmf";
            try (var stream = getClass().getResourceAsStream("/tactics/" + fileName)) {
                assertThat(stream).as(fileName).isNotNull();
                var tactic = parser.parse(stream.readAllBytes()).tactic();
                assertThat(value.apply(tactic)).as(fileName).isEqualTo(example.getValue());
                assertThat(tactic.markdown()).contains(label + ": " + example.getValue());
            }
        }
    }

    private void assertSetPieceApproach(String fileName, String expected) throws IOException {
        try (var stream = getClass().getResourceAsStream("/tactics/" + fileName)) {
            assertThat(stream).as(fileName).isNotNull();
            var tactic = parser.parse(stream.readAllBytes()).tactic();
            assertThat(tactic.setPieceApproach()).as(fileName).isEqualTo(expected);
            assertThat(tactic.markdown()).contains("Set-piece approach: " + expected);
        }
    }

    private void assertWidthAndTimeWasting(String fileName, String width, String timeWasting) throws IOException {
        try (var stream = getClass().getResourceAsStream("/tactics/" + fileName)) {
            assertThat(stream).as(fileName).isNotNull();
            var tactic = parser.parse(stream.readAllBytes()).tactic();
            assertThat(tactic.attackingWidth()).as(fileName).isEqualTo(width);
            assertThat(tactic.timeWasting()).as(fileName).isEqualTo(timeWasting);
            assertThat(tactic.markdown()).contains(
                    "Attacking width: " + width,
                    "Time wasting: " + timeWasting);
        }
    }

    private void assertTeamInstructions(
            String fileName, String tempo, String creativity, String transition, String width) throws IOException {
        try (var stream = getClass().getResourceAsStream("/tactics/" + fileName)) {
            assertThat(stream).as(fileName).isNotNull();
            var tactic = parser.parse(stream.readAllBytes()).tactic();
            assertThat(tactic.tempo()).as(fileName).isEqualTo(tempo);
            assertThat(tactic.creativeFreedom()).as(fileName).isEqualTo(creativity);
            assertThat(tactic.attackingTransition()).as(fileName).isEqualTo(transition);
            assertThat(tactic.attackingWidth()).as(fileName).isEqualTo(width);
            assertThat(tactic.markdown()).contains(
                    "Tempo: " + tempo,
                    "Creative freedom: " + creativity,
                    "Attacking transition: " + transition,
                    "Attacking width: " + width);
        }
    }

    static byte[] fmf(String name) {
        byte[] tactic = tactic(name);
        byte[] compressedTactic = compress(tactic);
        byte[] key = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16};
        byte[] iv = new byte[]{
                16, 15, 14, 13, 12, 11, 10, 9,
                8, 7, 6, 5, 4, 3, 2, 1};
        byte[] ciphertext = encrypt(compressedTactic, key, iv);
        ByteArrayOutputStream resource = new ByteArrayOutputStream();
        integer(resource, key.length);
        integer(resource, iv.length);
        resource.writeBytes(key);
        resource.writeBytes(iv);
        resource.writeBytes(ciphertext);

        ByteArrayOutputStream catalog = new ByteArrayOutputStream();
        string(catalog, name);
        integer(catalog, 1);
        string(catalog, name);
        string(catalog, ".tac");
        longValue(catalog, 0);
        longValue(catalog, resource.size());
        longValue(catalog, tactic.length);
        catalog.writeBytes(new byte[16]);
        integer(catalog, 0);
        byte[] compressedCatalog = compress(catalog.toByteArray());

        int catalogOffset = 26 + resource.size();
        byte[] header = new byte[26];
        System.arraycopy(new byte[]{2, 1, 'a', 'f', 'e', '.', 8, 0, 0}, 0, header, 0, 9);
        putLong(header, 9, catalogOffset - 9L);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.writeBytes(header);
        archive.writeBytes(resource.toByteArray());
        archive.writeBytes(new byte[]{2, 1, 'f', 'm', 'f', '.', 8, 0, 0});
        archive.writeBytes(compressedCatalog);
        return archive.toByteArray();
    }

    static byte[] tactic(String name) {
        ByteArrayOutputStream tactic = new ByteArrayOutputStream();
        tactic.writeBytes(new byte[]{
                3, 1, 'c', 'a', 't', '.', 0x22, 0, 0x22, 'B', 0, 0x1a, 3, 0, 1, 2});
        string(tactic, name);
        tactic.writeBytes(new byte[12]);
        tactic.writeBytes(new byte[]{4, 2, 5, 6, 2, 3});
        tactic.writeBytes(new byte[]{(byte) 0x88, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0});
        tactic.write(0xff);
        string(tactic, "Custom Wing Play");
        tactic.writeBytes(new byte[]{'G', 'N', 'I', 'W'});
        role(tactic, 1, 4096L | 0x400000L);
        role(tactic, 1, 2L | 0x800000L);
        return tactic.toByteArray();
    }

    private static void role(ByteArrayOutputStream output, int position, long selection) {
        output.writeBytes(new byte[]{'B', 0, 2});
        integer(output, position);
        output.writeBytes(new byte[]{(byte) 0xff, 0, 1, 1});
        integer(output, 0);
        integer(output, 0);
        longValue(output, selection);
    }

    private static byte[] compress(byte[] bytes) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream output = new ZstdOutputStream(compressed)) {
            output.write(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return compressed.toByteArray();
    }

    private static byte[] encrypt(byte[] bytes, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void string(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void integer(ByteArrayOutputStream output, int value) {
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array());
    }

    private static void longValue(ByteArrayOutputStream output, long value) {
        output.writeBytes(ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value)
                .array());
    }

    private static void putLong(byte[] target, int offset, long value) {
        ByteBuffer.wrap(target, offset, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value);
    }
}
