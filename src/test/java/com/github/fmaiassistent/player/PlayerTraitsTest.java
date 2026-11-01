package com.github.fmaiassistent.player;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerTraitsTest {
    @Test
    void matchesAllAdditionalOwnerProfilesIncludingNiangAndNaDisplays() throws Exception {
        byte[] raw;
        try (var input = getClass().getResourceAsStream("/fm27/player-traits-owner-observations.tsv")) {
            assertThat(input).isNotNull();
            raw = input.readNBytes(65_537);
        }
        assertThat(raw.length).isLessThanOrEqualTo(65_536);
        var observations = new String(raw, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("#")).toList();
        assertThat(observations).hasSize(43);
        for (String observation : observations) {
            String[] fields = observation.split("\t", -1);
            assertThat(fields).hasSize(3);
            long mask = Long.parseUnsignedLong(fields[1], 16);
            assertThat(PlayerTraits.describe(mask)).as("Player UID %s", fields[0]).isEqualTo(fields[2]);
            assertThat(PlayerTraits.rawHex(mask)).isEqualTo(fields[1]);
        }
    }

    @Test
    void retainsNoDisplayAndUnverifiedBitsInRawMasksWithoutInventingTraitNames() {
        long noDisplay = (1L << 48) | (1L << 49) | (1L << 61) | (1L << 62);
        assertThat(PlayerTraits.describe(noDisplay)).isEmpty();
        assertThat(PlayerTraits.rawHex(noDisplay)).isEqualTo("6003000000000000");
        assertThat(PlayerTraits.describe(noDisplay | (1L << 15) | (1L << 53) | Long.MIN_VALUE))
                .isEqualTo("Unknown player trait (bit 15), Unknown player trait (bit 53), Unknown player trait (bit 63)");
    }

    @Test
    void matchesAllTenOwnerProfilesAgainstIndependentlyCapturedMasks() {
        // PERSON+C0 captured 2026-10-31, PID 21484; see traits_verification.json.
        var profiles = Map.of(
                "1000000000000000", "Brings Ball Out of Defence",
                "0200400808000002", "Runs With Ball Down Right, Knocks Ball Past Opponent, Tries Tricks, Likes To Beat Opponent Repeatedly, Cuts Inside From Left Wing",
                "0408000800000000", "Tries Tricks, Runs With Ball Often, Cuts Inside From Right Wing",
                "1000002100000000", "Tries To Play Way Out Of Trouble, Dives Into Tackles, Brings Ball Out of Defence",
                "0000000000000100", "Shoots From Distance",
                "0000000000000800", "Curls Ball",
                "000000000000002c", "Runs With Ball Through Centre, Gets Into Opposition Area, Gets Forward Whenever Possible",
                "0000008000000000", "Cuts Inside From Both Wings",
                "1000000000010000", "Winds Up Opponents, Brings Ball Out of Defence",
                "0000000000000010", "Moves Into Channels");
        profiles.forEach((raw, names) -> {
            long mask = Long.parseUnsignedLong(raw, 16);
            assertThat(PlayerTraits.describe(mask)).as(raw).isEqualTo(names);
            assertThat(PlayerTraits.rawHex(mask)).isEqualTo(raw);
        });
    }

    @Test
    void retainsUnmappedHighBitsAndDistinguishesEmptyMask() {
        assertThat(PlayerTraits.describe(0)).isEmpty();
        assertThat(PlayerTraits.describe(Long.MIN_VALUE | (1L << 60)))
                .isEqualTo("Brings Ball Out of Defence, Unknown player trait (bit 63)");
        assertThat(PlayerTraits.rawHex(Long.MIN_VALUE)).isEqualTo("8000000000000000");
    }
}
