package com.github.fmaiassistent.tactic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Fm26TacticDecoder {
    private static final byte[] TACTIC_MAGIC = {3, 1, 'c', 'a', 't', '.'};
    private static final byte[] ROLE_MARKER = {'B', 0, 2};
    private static final long LEFT_SIDED = 0x100000L;
    private static final long RIGHT_SIDED = 0x200000L;
    private static final Map<Integer, String> MENTALITIES = Map.of(
            1, "Very Defensive",
            2, "Defensive",
            3, "Cautious",
            4, "Balanced",
            5, "Positive",
            6, "Attacking",
            7, "Very Attacking");
    private static final Map<Integer, String> PASSING_DIRECTNESS = Map.of(
            0x10, "Much Shorter",
            0x08, "Shorter",
            0, "Standard",
            0x04, "More Direct",
            0x02, "Much More Direct");
    private static final Map<Integer, String> TEMPOS = Map.of(
            0x0200, "Much Lower",
            0x0100, "Lower",
            0, "Standard",
            0x0080, "Higher",
            0x0040, "Much Higher");
    private static final Map<Integer, String> ATTACKING_TRANSITIONS = Map.of(
            0x08, "Counter Attack",
            0, "Standard",
            0x10, "Hold Shape");
    private static final Map<Integer, String> SET_PIECE_APPROACHES = Map.of(
            0, "Keep Ball in Play",
            0x04, "Play for Set Pieces");
    private static final Map<Integer, String> ATTACKING_WIDTHS = Map.of(
            0x20, "Much Narrower",
            0x80, "Narrower",
            0, "Standard",
            0x08, "Wider",
            0x04, "Much Wider");
    private static final Map<Integer, String> CREATIVE_FREEDOM = Map.of(
            0x40, "More Expressive",
            0, "Balanced",
            0x10, "More Disciplined");
    private static final Map<Integer, String> TIME_WASTING = Map.of(
            0x80, "Less Often",
            0, "Standard",
            0x40, "More Often");
    private static final Map<Integer, String> LINES_OF_ENGAGEMENT = Map.of(
            0x20, "High Press",
            0x40, "Mid Block",
            0x80, "Low Block");
    private static final Map<Integer, String> DEFENSIVE_LINES = Map.of(
            0x01, "Much Higher",
            0x02, "Higher",
            0, "Standard",
            0x04, "Lower",
            0x08, "Much Lower");
    private static final Map<Integer, String> DEFENSIVE_LINE_BEHAVIOURS = Map.of(
            0, "Balanced",
            0x20, "Drop Off More",
            0x10, "Step Up More");
    private static final Map<Integer, String> TRIGGER_PRESS = Map.of(
            0x10, "Much Less Often",
            0x08, "Less Often",
            0, "Standard",
            0x04, "More Often",
            0x02, "Much More Often");
    private static final Map<Integer, String> DEFENSIVE_TRANSITIONS = Map.of(
            0x40, "Counter-Press",
            0, "Standard",
            0x80, "Regroup");
    private static final Map<Integer, String> TACKLING = Map.of(
            0x01, "Get Stuck In",
            0, "Standard",
            0x02, "Stay On Feet");
    private static final Map<Integer, String> CROSS_ENGAGEMENT = Map.of(
            0, "Balanced",
            0x08, "Invite Crosses",
            0x04, "Stop Crosses");
    private static final Map<Integer, String> PRESSING_TRAPS = Map.of(
            0, "Balanced",
            0x01, "Trap Inside",
            0x02, "Trap Outside");
    private static final Map<Integer, String> SHORT_GOALKEEPING_DISTRIBUTION = Map.of(
            0, "No",
            0x20, "Yes");
    private static final Map<Integer, String> DRIBBLING = Map.of(
            0, "Balanced",
            0x40, "Discourage",
            0x20, "Encourage");
    private static final Map<Integer, String> PATIENCE = Map.of(
            0, "Standard",
            0x0200, "Hit Early Crosses",
            0x0002, "Work Ball Into Box");
    private static final Map<Integer, String> SHOTS_FROM_DISTANCE = Map.of(
            0, "Balanced",
            0x0100, "Discourage",
            0x0080, "Encourage");
    private static final Map<Integer, String> CROSSING_STYLES = Map.of(
            0, "Balanced",
            0x04, "Floated Crosses",
            0x10, "Low Crosses",
            0x08, "Whipped Crosses");
    private static final Map<Integer, String> SUPPORTING_RUNS = Map.of(
            0, "Balanced",
            0x10, "Both Flanks",
            0x20, "Left",
            0x40, "Right");
    private static final Map<Integer, String> PROGRESS_THROUGH = Map.of(
            0, "Balanced",
            0x0100, "Both Flanks",
            0x20, "Left",
            0x80, "Middle",
            0x40, "Right");
    private static final Map<Integer, String> PASS_RECEPTION = Map.of(
            0, "Balanced",
            0x20, "Pass Into Space",
            0x40, "Pass To Feet");
    private static final Map<Integer, String> BUILD_UP_STRATEGIES = Map.of(
            0, "Balanced",
            0x0100, "Bypass Press",
            0x0080, "Play Through Press");
    private static final Map<Integer, String> GOAL_KICKS = Map.of(
            0, "Mixed",
            0x10, "Short",
            0x20, "Long");
    private static final Map<Integer, String> GK_DISTRIBUTION_SPEED = Map.of(
            0, "Balanced",
            0x04, "Distribute Quickly",
            0x08, "Slow Pace Down");
    private static final Map<Integer, String> GK_DISTRIBUTION = Map.of(
            0, "Balanced",
            0x08, "Center Backs",
            0x10, "Full Backs",
            0x20, "Flanks",
            0x80, "Playmaker",
            0x0100, "Target Forward");
    private static final Map<Long, String> DUTIES = Map.of(
            0x200000L, "Defend",
            0x400000L, "Support",
            0x800000L, "Attack",
            0x2000000L, "Stopper",
            0x4000000L, "Cover",
            0x400000000L, "Float");
    private static final long DUTY_MASK = DUTIES.keySet().stream().reduce(0L, (left, right) -> left | right);
    private static final Map<Long, String> IN_POSSESSION_ROLES = inPossessionRoles();
    private static final Map<Long, String> OUT_OF_POSSESSION_ROLES = outOfPossessionRoles();

    DecodedTactic decode(byte[] bytes) {
        if (!matchesAt(bytes, 0, TACTIC_MAGIC) || bytes.length < 64) {
            throw new IllegalArgumentException("The embedded tactic data is not a supported FM26 tactic");
        }

        int nameLength = littleEndianInt(bytes, 16);
        int nameOffset = 20;
        if (nameLength <= 0 || nameLength > 1024 || nameOffset > bytes.length - nameLength) {
            throw new IllegalArgumentException("The embedded tactic name is damaged");
        }
        String name = new String(bytes, nameOffset, nameLength, StandardCharsets.UTF_8);
        int settingsOffset = nameOffset + nameLength + 12;
        if (settingsOffset > bytes.length - 17) {
            throw new IllegalArgumentException("The embedded tactic settings are truncated");
        }
        int passingByte = Byte.toUnsignedInt(bytes[settingsOffset + 6]);
        int attackingByte = Byte.toUnsignedInt(bytes[settingsOffset + 7]);
        int transitionByte = Byte.toUnsignedInt(bytes[settingsOffset + 8]);
        int movementByte = Byte.toUnsignedInt(bytes[settingsOffset + 9]);
        int goalkeeperByte = Byte.toUnsignedInt(bytes[settingsOffset + 10]);
        int goalKickByte = Byte.toUnsignedInt(bytes[settingsOffset + 11]);
        int instructionByte = Byte.toUnsignedInt(bytes[settingsOffset + 12]);
        String passingDirectness = option(PASSING_DIRECTNESS, passingByte & 0x1e);
        int tempoCode = (goalKickByte & 0xc0)
                | (instructionByte & 0x03) << Byte.SIZE;
        String tempo = option(TEMPOS, tempoCode);
        String attackingTransition = option(ATTACKING_TRANSITIONS, transitionByte & 0x18);
        String setPieceApproach = option(SET_PIECE_APPROACHES, transitionByte & 0x04);
        String mentality = option(MENTALITIES, bytes[settingsOffset + 2]);
        String attackingWidth = option(ATTACKING_WIDTHS, instructionByte & 0xac);
        String creativeFreedom = option(CREATIVE_FREEDOM, instructionByte & 0x50);
        String dribbling = option(DRIBBLING, attackingByte & 0x60);
        String patience = option(PATIENCE, ((attackingByte & 0x02) << Byte.SIZE) | (transitionByte & 0x02));
        String shotsFromDistance = option(SHOTS_FROM_DISTANCE,
                (attackingByte & 0x80) | ((transitionByte & 0x01) << Byte.SIZE));
        String crossingStyle = option(CROSSING_STYLES, attackingByte & 0x1c);
        String supportingRuns = option(SUPPORTING_RUNS, movementByte & 0x70);
        String progressThrough = option(PROGRESS_THROUGH,
                (transitionByte & 0xe0) | ((movementByte & 0x01) << Byte.SIZE));
        String passReception = option(PASS_RECEPTION, passingByte & 0x60);
        String buildUpStrategy = option(BUILD_UP_STRATEGIES,
                (passingByte & 0x80) | ((attackingByte & 0x01) << Byte.SIZE));
        String goalKicks = option(GOAL_KICKS, goalKickByte & 0x30);
        String gkDistributionSpeed = option(GK_DISTRIBUTION_SPEED, goalKickByte & 0x0c);
        String gkDistribution = option(GK_DISTRIBUTION,
                (goalkeeperByte & 0xb8) | ((goalKickByte & 0x01) << Byte.SIZE));
        int lineByte = Byte.toUnsignedInt(bytes[settingsOffset + 13]);
        int pressingByte = Byte.toUnsignedInt(bytes[settingsOffset + 14]);
        int behaviourByte = Byte.toUnsignedInt(bytes[settingsOffset + 15]);
        int trappingByte = Byte.toUnsignedInt(bytes[settingsOffset + 16]);
        String timeWasting = option(TIME_WASTING, behaviourByte & 0xc0);
        String lineOfEngagement = option(LINES_OF_ENGAGEMENT, lineByte & 0xe0);
        String defensiveLine = option(DEFENSIVE_LINES, lineByte & 0x0f);
        String defensiveLineBehaviour = option(DEFENSIVE_LINE_BEHAVIOURS, behaviourByte & 0x30);
        String triggerPress = option(TRIGGER_PRESS, pressingByte & 0x1e);
        String defensiveTransition = option(DEFENSIVE_TRANSITIONS, pressingByte & 0xc0);
        String tackling = option(TACKLING, behaviourByte & 0x03);
        String crossEngagement = option(CROSS_ENGAGEMENT, trappingByte & 0x0c);
        String pressingTrap = option(PRESSING_TRAPS, trappingByte & 0x03);
        String shortGoalkeepingDistribution = option(SHORT_GOALKEEPING_DISTRIBUTION, pressingByte & 0x20);

        int firstRole = indexOf(bytes, ROLE_MARKER, settingsOffset);
        if (firstRole < 0) {
            throw new IllegalArgumentException("The embedded tactic contains no player roles");
        }
        String style = findTacticalStyle(bytes, settingsOffset, firstRole, name);
        List<RoleRecord> records = parseRoleRecords(bytes, firstRole);
        if (records.size() < 2 || records.size() % 2 != 0) {
            throw new IllegalArgumentException("The embedded tactic role data is incomplete");
        }

        List<RoleSelection> inPossession = new ArrayList<>(records.size() / 2);
        List<RoleSelection> outOfPossession = new ArrayList<>(records.size() / 2);
        for (int index = 0; index < records.size(); index++) {
            RoleRecord record = records.get(index);
            boolean inPossessionPhase = index % 2 == 0;
            Map<Long, String> roleNames = inPossessionPhase
                    ? IN_POSSESSION_ROLES
                    : OUT_OF_POSSESSION_ROLES;
            long roleValue = record.selection() & ~DUTY_MASK;
            String role = roleNames.getOrDefault(roleValue, "Unknown role (0x" + Long.toHexString(roleValue) + ")");
            String duty = duty(record.selection());
            RoleSelection selection = new RoleSelection(position(record.positionMask()), role, duty);
            (inPossessionPhase ? inPossession : outOfPossession).add(selection);
        }

        return new DecodedTactic(
                name, style, mentality, passingDirectness, tempo, attackingTransition,
                attackingWidth, creativeFreedom, timeWasting, setPieceApproach,
                dribbling, patience, shotsFromDistance, crossingStyle, supportingRuns,
                progressThrough, passReception, buildUpStrategy,
                goalKicks, gkDistributionSpeed, gkDistribution,
                lineOfEngagement, defensiveLine, defensiveLineBehaviour,
                triggerPress, defensiveTransition, tackling,
                crossEngagement, pressingTrap, shortGoalkeepingDistribution,
                inPossession, outOfPossession);
    }

    private static String option(Map<Integer, String> options, byte rawValue) {
        return option(options, Byte.toUnsignedInt(rawValue));
    }

    private static String option(Map<Integer, String> options, int value) {
        return options.getOrDefault(value, "Unknown (code " + value + ")");
    }

    private static List<RoleRecord> parseRoleRecords(byte[] bytes, int firstRole) {
        List<RoleRecord> records = new ArrayList<>();
        int offset = firstRole;
        while (matchesAt(bytes, offset, ROLE_MARKER)) {
            int positionMask = littleEndianInt(bytes, offset + 3);
            int optionCount = littleEndianInt(bytes, offset + 11);
            if (optionCount < 0 || optionCount > 128) {
                throw new IllegalArgumentException("The embedded tactic contains an invalid role option count");
            }
            long selectedOffset = (long) offset + 15 + 24L * optionCount;
            if (selectedOffset > bytes.length - 12) {
                throw new IllegalArgumentException("The embedded tactic role data is truncated");
            }
            long selection = littleEndianLong(bytes, Math.toIntExact(selectedOffset + 4));
            records.add(new RoleRecord(positionMask, selection));

            offset = Math.toIntExact(selectedOffset + 12);
            if (!matchesAt(bytes, offset, ROLE_MARKER)
                    && offset < bytes.length && matchesAt(bytes, offset + 1, ROLE_MARKER)) {
                offset++;
            }
        }
        return List.copyOf(records);
    }

    private static String findTacticalStyle(
            byte[] bytes, int start, int end, String tacticName) {
        String result = null;
        for (int offset = start; offset <= end - 5; offset++) {
            int length = littleEndianInt(bytes, offset);
            if (length <= 0 || length > 128 || offset + 4 + length > end
                    || !readable(bytes, offset + 4, length)) {
                continue;
            }
            String candidate = new String(bytes, offset + 4, length, StandardCharsets.UTF_8).strip();
            if (!candidate.equals(tacticName) && candidate.chars().anyMatch(Character::isLetter)) {
                result = candidate;
            }
        }
        return result == null ? "Custom" : result;
    }

    private static String duty(long selected) {
        for (Map.Entry<Long, String> entry : DUTIES.entrySet()) {
            if ((selected & entry.getKey()) != 0) {
                return entry.getValue();
            }
        }
        return "Unspecified";
    }

    private static String position(int rawMask) {
        long mask = Integer.toUnsignedLong(rawMask);
        boolean left = (mask & LEFT_SIDED) != 0;
        boolean right = (mask & RIGHT_SIDED) != 0;
        long base = mask & 0x1ffffL;
        return switch ((int) base) {
            case 0x1 -> "GK";
            case 0x2 -> "SW";
            case 0x4 -> "DR";
            case 0x8 -> "DL";
            case 0x10 -> sided("DC", left, right);
            case 0x20 -> "WBR";
            case 0x40 -> "WBL";
            case 0x80 -> sided("DM", left, right);
            case 0x100 -> "MR";
            case 0x200 -> "ML";
            case 0x400 -> sided("MC", left, right);
            case 0x800 -> "AMR";
            case 0x1000 -> "AML";
            case 0x2000 -> sided("AMC", left, right);
            case 0x4000 -> sided("ST", left, right);
            case 0x8000 -> "STR";
            case 0x10000 -> "STL";
            default -> "Position 0x" + Long.toHexString(mask);
        };
    }

    private static String sided(String centre, boolean left, boolean right) {
        if (left) {
            return centre + "L";
        }
        if (right) {
            return centre + "R";
        }
        return centre;
    }

    private static Map<Long, String> inPossessionRoles() {
        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(0L, "No Role");
        roles.put(1L, "Goalkeeper");
        roles.put(4096L, "Ball-Playing Goalkeeper");
        roles.put(9007199254740992L, "No-Nonsense Goalkeeper");
        roles.put(4L, "Full-Back");
        roles.put(8L, "Wing-Back");
        roles.put(68719476736L, "No-Nonsense Full-Back");
        roles.put(274877906944L, "Advanced Wing-Back");
        roles.put(17592186044416L, "Inverted Wing-Back");
        roles.put(4503599627370496L, "Inverted Full-Back");
        roles.put(36028797018963968L, "Playmaking Wing-Back");
        roles.put(2L, "Central Defender");
        roles.put(16384L, "Libero");
        roles.put(16777216L, "Ball-Playing Centre-Back");
        roles.put(536870912L, "No-Nonsense Centre-Back");
        roles.put(2251799813685248L, "Wide Centre-Back");
        roles.put(18014398509481984L, "Overlapping Centre-Back");
        roles.put(144115188075855872L, "Midfield Playmaker");
        roles.put(16L, "Defensive Midfielder");
        roles.put(32L, "Central Midfielder");
        roles.put(32768L, "Deep-Lying Playmaker");
        roles.put(65536L, "Box-to-Box Midfielder");
        roles.put(268435456L, "Ball-Winning Midfielder");
        roles.put(8589934592L, "Anchor");
        roles.put(34359738368L, "Half-Back");
        roles.put(137438953472L, "Enganche");
        roles.put(549755813888L, "Regista");
        roles.put(70368744177664L, "Box-to-Box Playmaker");
        roles.put(140737488355328L, "Mezzala");
        roles.put(1125899906842624L, "Segundo Volante");
        roles.put(64L, "Wide Midfielder");
        roles.put(128L, "Winger");
        roles.put(512L, "Attacking Midfielder");
        roles.put(72057594037927936L, "Channel Midfielder");
        roles.put(131072L, "Advanced Playmaker");
        roles.put(134217728L, "Inside Forward");
        roles.put(562949953421312L, "Inverted Winger");
        roles.put(1073741824L, "Defensive Winger");
        roles.put(4294967296L, "Trequartista");
        roles.put(4398046511104L, "Wide Target Forward");
        roles.put(8796093022208L, "Wide Playmaker");
        roles.put(35184372088832L, "Wide Forward");
        roles.put(281474976710656L, "Wide Central Midfielder");
        roles.put(1024L, "Deep-Lying Forward");
        roles.put(2048L, "Centre Forward");
        roles.put(262144L, "Target Forward");
        roles.put(524288L, "Poacher");
        roles.put(1048576L, "Complete Forward");
        roles.put(2147483648L, "Channel Forward");
        roles.put(1099511627776L, "False Nine");
        roles.put(2199023255552L, "Shadow Striker");
        return Map.copyOf(roles);
    }

    private static Map<Long, String> outOfPossessionRoles() {
        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(0L, "No Role");
        roles.put(1L, "Goalkeeper");
        roles.put(2L, "Sweeper Keeper");
        roles.put(4L, "Line Keeper");
        roles.put(8L, "Centre-Back");
        roles.put(16L, "Stopping Centre-Back");
        roles.put(32L, "Covering Centre-Back");
        roles.put(64L, "Wide Centre-Back");
        roles.put(128L, "Stopping Wide Centre-Back");
        roles.put(256L, "Covering Wide Centre-Back");
        roles.put(512L, "Full-Back");
        roles.put(1024L, "Pressing Full-Back");
        roles.put(2048L, "Holding Full-Back");
        roles.put(4096L, "Wing-Back");
        roles.put(8192L, "Pressing Wing-Back");
        roles.put(16384L, "Holding Wing-Back");
        roles.put(32768L, "Defensive Midfielder");
        roles.put(65536L, "Dropping Defensive Midfielder");
        roles.put(131072L, "Pressing Defensive Midfielder");
        roles.put(262144L, "Screening Defensive Midfielder");
        roles.put(524288L, "Wide-Cover Defensive Midfielder");
        roles.put(1048576L, "Central Midfielder");
        roles.put(134217728L, "Pressing Central Midfielder");
        roles.put(268435456L, "Screening Central Midfielder");
        roles.put(536870912L, "Wide-Cover Central Midfielder");
        roles.put(1073741824L, "Attacking Midfielder");
        roles.put(2147483648L, "Tracking Attacking Midfielder");
        roles.put(4294967296L, "Central Outlet Midfielder");
        roles.put(8589934592L, "Splitting Attacking Midfielder");
        roles.put(34359738368L, "Wide Midfielder");
        roles.put(68719476736L, "Tracking Wide Midfielder");
        roles.put(137438953472L, "Wide Outlet Midfielder");
        roles.put(274877906944L, "Winger");
        roles.put(549755813888L, "Tracking Winger");
        roles.put(1099511627776L, "Inverting Outlet Winger");
        roles.put(2199023255552L, "Wide Outlet Winger");
        roles.put(4398046511104L, "Centre Forward");
        roles.put(8796093022208L, "Tracking Centre Forward");
        roles.put(17592186044416L, "Central Outlet Centre Forward");
        roles.put(35184372088832L, "Splitting Outlet Centre Forward");
        return Map.copyOf(roles);
    }

    private static boolean readable(byte[] bytes, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            if (value < 0x20 || value == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length - Integer.BYTES) {
            throw new IndexOutOfBoundsException();
        }
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }

    private static long littleEndianLong(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length - Long.BYTES) {
            throw new IndexOutOfBoundsException();
        }
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result |= (long) Byte.toUnsignedInt(bytes[offset + index]) << index * 8;
        }
        return result;
    }

    private static int indexOf(byte[] bytes, byte[] needle, int start) {
        for (int offset = Math.max(0, start); offset <= bytes.length - needle.length; offset++) {
            if (matchesAt(bytes, offset, needle)) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean matchesAt(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || offset > bytes.length - expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    record RoleSelection(String position, String role, String duty) {
        String description() {
            return role + ("Unspecified".equals(duty) ? "" : " (" + duty + ")");
        }
    }

    record DecodedTactic(
            String name,
            String tacticalStyle,
            String mentality,
            String passingDirectness,
            String tempo,
            String attackingTransition,
            String attackingWidth,
            String creativeFreedom,
            String timeWasting,
            String setPieceApproach,
            String dribbling,
            String patience,
            String shotsFromDistance,
            String crossingStyle,
            String supportingRuns,
            String progressThrough,
            String passReception,
            String buildUpStrategy,
            String goalKicks,
            String gkDistributionSpeed,
            String gkDistribution,
            String lineOfEngagement,
            String defensiveLine,
            String defensiveLineBehaviour,
            String triggerPress,
            String defensiveTransition,
            String tackling,
            String crossEngagement,
            String pressingTrap,
            String shortGoalkeepingDistribution,
            List<RoleSelection> inPossession,
            List<RoleSelection> outOfPossession) {
        DecodedTactic {
            inPossession = List.copyOf(inPossession);
            outOfPossession = List.copyOf(outOfPossession);
        }

        String markdown() {
            StringBuilder markdown = new StringBuilder()
                    .append("Tactical style: ").append(tacticalStyle).append('\n')
                    .append("Mentality: ").append(mentality).append("\n\n")
                    .append("### In possession team instructions\n")
                    .append("- Passing directness: ").append(passingDirectness).append('\n')
                    .append("- Tempo: ").append(tempo).append('\n')
                    .append("- Attacking transition: ").append(attackingTransition).append('\n')
                    .append("- Attacking width: ").append(attackingWidth).append('\n')
                    .append("- Creative freedom: ").append(creativeFreedom).append('\n')
                    .append("- Time wasting: ").append(timeWasting).append('\n')
                    .append("- Set-piece approach: ").append(setPieceApproach).append('\n')
                    .append("- Dribbling: ").append(dribbling).append('\n')
                    .append("- Patience: ").append(patience).append('\n')
                    .append("- Shots from distance: ").append(shotsFromDistance).append('\n')
                    .append("- Crossing style: ").append(crossingStyle).append('\n')
                    .append("- Supporting runs: ").append(supportingRuns).append('\n')
                    .append("- Progress through: ").append(progressThrough).append('\n')
                    .append("- Pass reception: ").append(passReception).append('\n')
                    .append("- Build-up strategy: ").append(buildUpStrategy).append('\n')
                    .append("- Goal kicks: ").append(goalKicks).append('\n')
                    .append("- GK distribution speed: ").append(gkDistributionSpeed).append('\n')
                    .append("- GK distribution: ").append(gkDistribution).append("\n\n")
                    .append("### Out of possession team instructions\n")
                    .append("- Line of engagement: ").append(lineOfEngagement).append('\n')
                    .append("- Defensive line: ").append(defensiveLine).append('\n')
                    .append("- Defensive line behaviour: ").append(defensiveLineBehaviour).append('\n')
                    .append("- Trigger press: ").append(triggerPress).append('\n')
                    .append("- Defensive transition: ").append(defensiveTransition).append('\n')
                    .append("- Tackling: ").append(tackling).append('\n')
                    .append("- Cross engagement: ").append(crossEngagement).append('\n')
                    .append("- Pressing trap: ").append(pressingTrap).append('\n')
                    .append("- Short goalkeeping distribution: ").append(shortGoalkeepingDistribution).append("\n\n")
                    .append("### In possession\n");
            appendRoles(markdown, inPossession);
            markdown.append("\n### Out of possession\n");
            appendRoles(markdown, outOfPossession);
            return markdown.toString();
        }

        private static void appendRoles(StringBuilder markdown, List<RoleSelection> selections) {
            for (RoleSelection selection : selections) {
                markdown.append("- ").append(selection.position()).append(": ")
                        .append(selection.description()).append('\n');
            }
        }
    }

    private record RoleRecord(int positionMask, long selection) {
    }
}
