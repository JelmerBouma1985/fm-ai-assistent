package com.github.fmaiassistent.player;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerTraitsTest {
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
