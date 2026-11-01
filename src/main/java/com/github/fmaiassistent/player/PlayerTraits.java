package com.github.fmaiassistent.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Names corroborated by the owner's ten FM27 player profiles and their live masks.
 * Unmapped bits stay explicit rather than silently disappearing from AI advice.
 */
public final class PlayerTraits {
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(1, "Runs With Ball Down Right"),
            Map.entry(2, "Runs With Ball Through Centre"),
            Map.entry(3, "Gets Into Opposition Area"),
            Map.entry(4, "Moves Into Channels"),
            Map.entry(5, "Gets Forward Whenever Possible"),
            Map.entry(8, "Shoots From Distance"),
            Map.entry(11, "Curls Ball"),
            Map.entry(16, "Winds Up Opponents"),
            Map.entry(27, "Knocks Ball Past Opponent"),
            Map.entry(32, "Tries To Play Way Out Of Trouble"),
            Map.entry(35, "Tries Tricks"),
            Map.entry(37, "Dives Into Tackles"),
            Map.entry(39, "Cuts Inside From Both Wings"),
            Map.entry(46, "Likes To Beat Opponent Repeatedly"),
            Map.entry(51, "Runs With Ball Often"),
            Map.entry(57, "Cuts Inside From Left Wing"),
            Map.entry(58, "Cuts Inside From Right Wing"),
            Map.entry(60, "Brings Ball Out of Defence"));

    private PlayerTraits() { }

    public static String describe(long mask) {
        List<String> names = new ArrayList<>();
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if ((mask & (1L << bit)) != 0) {
                names.add(NAMES.getOrDefault(bit, "Unknown player trait (bit " + bit + ")"));
            }
        }
        return String.join(", ", names);
    }

    public static String rawHex(long mask) {
        return java.util.HexFormat.of().toHexDigits(mask);
    }
}
