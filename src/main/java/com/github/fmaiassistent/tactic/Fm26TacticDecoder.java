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
            3, "Much Shorter",
            4, "Shorter",
            5, "Standard",
            6, "More Direct",
            7, "Much More Direct");
    private static final Map<Integer, String> ATTACKING_TRANSITIONS = Map.of(
            1, "Counter",
            2, "Standard",
            3, "Hold Shape");
    private static final Map<Integer, String> ATTACKING_WIDTHS = Map.of(
            3, "Much Narrower",
            4, "Narrower",
            5, "Standard",
            6, "Wider",
            7, "Much Wider");
    private static final Map<Integer, String> CREATIVE_FREEDOM = Map.of(
            1, "More Expressive",
            2, "Balanced",
            3, "More Disciplined");
    private static final Map<Integer, String> TIME_WASTING = Map.of(
            2, "Less Often",
            3, "Standard",
            4, "More Often");
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
        if (settingsOffset > bytes.length - 6) {
            throw new IllegalArgumentException("The embedded tactic settings are truncated");
        }
        String passingDirectness = option(PASSING_DIRECTNESS, bytes[settingsOffset]);
        String attackingTransition = option(ATTACKING_TRANSITIONS, bytes[settingsOffset + 1]);
        String mentality = option(MENTALITIES, bytes[settingsOffset + 2]);
        String attackingWidth = option(ATTACKING_WIDTHS, bytes[settingsOffset + 3]);
        String creativeFreedom = option(CREATIVE_FREEDOM, bytes[settingsOffset + 4]);
        String timeWasting = option(TIME_WASTING, bytes[settingsOffset + 5]);

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
                name, style, mentality, passingDirectness, attackingTransition,
                attackingWidth, creativeFreedom, timeWasting,
                inPossession, outOfPossession);
    }

    private static String option(Map<Integer, String> options, byte rawValue) {
        int value = Byte.toUnsignedInt(rawValue);
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
            String attackingTransition,
            String attackingWidth,
            String creativeFreedom,
            String timeWasting,
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
                    .append("### Core team instructions\n")
                    .append("- Passing directness: ").append(passingDirectness).append('\n')
                    .append("- Attacking transition: ").append(attackingTransition).append('\n')
                    .append("- Attacking width: ").append(attackingWidth).append('\n')
                    .append("- Creative freedom: ").append(creativeFreedom).append('\n')
                    .append("- Time wasting: ").append(timeWasting).append("\n\n")
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
