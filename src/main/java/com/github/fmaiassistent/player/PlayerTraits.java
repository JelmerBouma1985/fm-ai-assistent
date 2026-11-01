package com.github.fmaiassistent.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Names corroborated by the owner's FM27 player profiles and their live masks.
 * Unverified bits stay explicit; bits with no displayed trait are preserved in the raw mask.
 */
public final class PlayerTraits {
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(0, "Runs With Ball Down Left"),
            Map.entry(1, "Runs With Ball Down Right"),
            Map.entry(2, "Runs With Ball Through Centre"),
            Map.entry(3, "Gets Into Opposition Area"),
            Map.entry(4, "Moves Into Channels"),
            Map.entry(5, "Gets Forward Whenever Possible"),
            Map.entry(6, "Plays Short Simple Passes"),
            Map.entry(7, "Tries Killer Balls Often"),
            Map.entry(8, "Shoots From Distance"),
            Map.entry(9, "Shoots With Power"),
            Map.entry(10, "Places Shots"),
            Map.entry(11, "Curls Ball"),
            Map.entry(12, "Likes To Round Keeper"),
            Map.entry(13, "Likes To Try To Beat Offside Trap"),
            Map.entry(14, "Uses Outside Of Foot"),
            Map.entry(16, "Winds Up Opponents"),
            Map.entry(17, "Argues With Officials"),
            Map.entry(18, "Plays With Back To Goal"),
            Map.entry(19, "Comes Deep To Get Ball"),
            Map.entry(20, "Plays One-Twos"),
            Map.entry(21, "Likes To Lob Keeper"),
            Map.entry(22, "Dictates Tempo"),
            Map.entry(23, "Attempts Overhead Kicks"),
            Map.entry(24, "Looks To Pass Rather Than Attempting To Score"),
            Map.entry(25, "Plays No Through Balls"),
            Map.entry(26, "Stops Play"),
            Map.entry(27, "Knocks Ball Past Opponent"),
            Map.entry(28, "Moves Ball To Right Foot Before Dribble Attempt"),
            Map.entry(29, "Moves Ball To Left Foot Before Dribble Attempt"),
            Map.entry(30, "Dwells On Ball"),
            Map.entry(31, "Arrives Late In Opponents' Area"),
            Map.entry(32, "Tries To Play Way Out Of Trouble"),
            Map.entry(33, "Stays Back At All Times"),
            Map.entry(34, "Avoids Using Weaker Foot"),
            Map.entry(35, "Tries Tricks"),
            Map.entry(36, "Tries Long Range Free Kicks"),
            Map.entry(37, "Dives Into Tackles"),
            Map.entry(38, "Does Not Dive Into Tackles"),
            Map.entry(39, "Cuts Inside From Both Wings"),
            Map.entry(40, "Hugs Line"),
            Map.entry(41, "Gets Crowd Going"),
            Map.entry(42, "Tries First Time Shots"),
            Map.entry(43, "Tries Long Range Passes"),
            Map.entry(44, "Likes Ball Played Into Feet"),
            Map.entry(45, "Hits Free Kicks With Power"),
            Map.entry(46, "Likes To Beat Opponent Repeatedly"),
            Map.entry(47, "Likes To Switch Ball To Wide Areas"),
            Map.entry(50, "Possesses Long Flat Throw"),
            Map.entry(51, "Runs With Ball Often"),
            Map.entry(52, "Runs With Ball Rarely"),
            Map.entry(54, "Does Not Move Into Channels"),
            Map.entry(55, "Uses Long Throw To Start Counter Attacks"),
            Map.entry(56, "Refrains From Taking Long Shots"),
            Map.entry(57, "Cuts Inside From Left Wing"),
            Map.entry(58, "Cuts Inside From Right Wing"),
            Map.entry(59, "Crosses Early"),
            Map.entry(60, "Brings Ball Out of Defence"));

    // The checked profiles displayed N/A for 49, 61 and 62. Niang's complete
    // visible list accounts for bits 9 and 51, with no additional name for 48.
    // This records observed display behavior, not a claim that these bits are unused by FM.
    private static final Set<Integer> NO_DISPLAYED_TRAIT = Set.of(48, 49, 61, 62);

    private PlayerTraits() { }

    public static String describe(long mask) {
        List<String> names = new ArrayList<>();
        for (int bit = 0; bit < Long.SIZE; bit++) {
            if ((mask & (1L << bit)) != 0 && !NO_DISPLAYED_TRAIT.contains(bit)) {
                names.add(NAMES.getOrDefault(bit, "Unknown player trait (bit " + bit + ")"));
            }
        }
        return String.join(", ", names);
    }

    public static String rawHex(long mask) {
        return java.util.HexFormat.of().toHexDigits(mask);
    }
}
