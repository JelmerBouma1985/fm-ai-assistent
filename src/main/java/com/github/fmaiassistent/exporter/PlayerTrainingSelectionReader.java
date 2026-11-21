package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerTrainingSelectionLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/** Pure reads plus the exact native best-role calculation; no target calls. */
public final class PlayerTrainingSelectionReader {
    private static final long EXCLUDED = 0xFC0484B656F02100L;
    private static final Map<Integer, byte[]> LEGACY_CLASSIFIERS = classifiers("/fm27/player-training-role-classifiers.tsv", 50);
    private static final Map<Integer, byte[]> UPDATED_CLASSIFIERS = classifiers("/fm27/player-training-role-classifiers-26620374.tsv", 39);
    private final Map<Integer, byte[]> classifiers;
    private final boolean updatedRoles;
    private final PlayerTrainingSelectionLayout layout;
    private Long verifiedBase;

    public PlayerTrainingSelectionReader() { this(PlayerTrainingSelectionLayout.current()); }
    PlayerTrainingSelectionReader(PlayerTrainingSelectionLayout layout) {
        this(layout, com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256);
    }
    PlayerTrainingSelectionReader(PlayerTrainingSelectionLayout layout, String profile) {
        this.layout = layout;
        this.updatedRoles = com.github.fmaiassistent.memory.NativeModuleProfile.STEAM_26620374_SHA256.equals(profile);
        this.classifiers = updatedRoles ? UPDATED_CLASSIFIERS : LEGACY_CLASSIFIERS;
    }

    public Result read(ProcessMemoryReader reader, long player, long module, int positionMask) throws IOException {
        return read(reader, player, module, positionMask, null);
    }

    public Result read(ProcessMemoryReader reader, long player, long module, int positionMask,
            Long explicitRole) throws IOException {
        PlayerSnapshotValidator.validatePlayerTrainingSelectionLayout(layout);
        verifyNative(reader, module);
        if (explicitRole != null && explicitRole != 0) {
            if (Long.bitCount(explicitRole) != 1 || !classifiers.containsKey(Long.numberOfTrailingZeros(explicitRole)))
                throw new IOException("Player explicit training role range anchor failed");
            return attributesForRole(reader, module, explicitRole);
        }
        byte[] positionValues = reader.readBytes(player + layout.positions(), 15);
        byte[] attributes = reader.readBytes(player + layout.attributeBase(), layout.firstAttribute() + 54);
        int ca = reader.readU16(player + layout.currentAbility());
        int cached = reader.readU8(player + layout.cachedAbility());
        if (ca < 1 || ca > 200) throw new IOException("Player training selection ability range anchor failed");
        for (byte value : positionValues) if (Byte.toUnsignedInt(value) > 20)
            throw new IOException("Player training selection position range anchor failed");
        for (int i = 0; i < 54; i++) if (Byte.toUnsignedInt(attributes[layout.firstAttribute() + i]) > 100)
            throw new IOException("Player training selection attribute range anchor failed");
        if (positionMask == 0) {
            for (int index = 0; index < 15; index++)
                if (Byte.toUnsignedInt(positionValues[PlayerTrainingSelectionLayout.positionAttributeIndex(index)]) >= 15) positionMask |= 1 << index;
        }
        long[] allowed = new long[15];
        for (int i = 0; i < allowed.length; i++) allowed[i] = reader.readU64(module + layout.positionRolesRva() + i * 8L);
        long bestRole = 0; int best = 0;
        for (var entry : classifiers.entrySet()) {
            long role = 1L << entry.getKey();
            if ((role & EXCLUDED) != 0) continue;
            for (int position = 0; position < 15; position++) {
                if (position == 1 || (positionMask & (1 << position)) == 0 || (allowed[position] & role) == 0) continue;
                int score = ability(attributes, layout.firstAttribute(), positionValues, ca, cached, role,
                        1 << position, PlayerTrainingSelectionLayout.positionAttributeIndex(position), entry.getValue(), updatedRoles);
                if (score > best) { best = score; bestRole = role; }
            }
        }
        return attributesForRole(reader, module, bestRole);
    }

    private Result attributesForRole(ProcessMemoryReader reader, long module, long bestRole) throws IOException {
        long begin = reader.readU64(module + layout.attributesBeginRva());
        long end = reader.readU64(module + layout.attributesEndRva());
        if (begin < 0 || end < begin || end > ProcessMemoryReader.MAX_USER_ADDRESS || end - begin > 128)
            throw new IOException("Player training selection attribute list range anchor failed");
        var selected = new ArrayList<Integer>();
        if (bestRole != 0) {
            byte[] classifier = classifiers.get(Long.numberOfTrailingZeros(bestRole));
            for (byte value : reader.readBytes(begin, (int) (end - begin))) {
                int id = Byte.toUnsignedInt(value);
                if (id < 15 || id > 68) throw new IOException("Player training selection attribute ID range anchor failed");
                int importance = Byte.toUnsignedInt(classifier[id - 15]);
                if (importance == 0 || importance == 1) selected.add(id);
            }
        }
        return new Result(bestRole, selected.stream().mapToInt(Integer::intValue).toArray());
    }

    static int ability(byte[] attributes, int first, byte[] positions, int ca, int cached, long role,
            int positionMask, int positionIndex, byte[] importance) {
        return ability(attributes, first, positions, ca, cached, role, positionMask, positionIndex, importance, false);
    }

    static int ability(byte[] attributes, int first, byte[] positions, int ca, int cached, long role,
            int positionMask, int positionIndex, byte[] importance, boolean updatedRoles) {
        float primary = 0, secondary = 0, inverted = 0;
        int primaries = 0, secondaries = 0, inversions = 0;
        for (int i = 0; i < 54; i++) {
            int value = attributes[first + i];
            switch (Byte.toUnsignedInt(importance[i])) {
                case 0 -> { primary += value; primaries++; }
                case 1 -> { secondary += value; secondaries++; }
                case 2 -> { inverted += Math.max(1f, 100 - value); inversions++; }
                default -> { }
            }
        }
        int weights = (primary > 0 ? 16 : 0) | (secondary > 0 ? 4 : 0) | (inverted > 0 ? 3 : 0);
        int score = 0;
        int familiarity = Byte.toUnsignedInt(positions[positionIndex]);
        int originalFamiliarity = familiarity;
        if (weights != 0) {
            float a = primary > 0 ? primary / primaries * 16f : 0;
            float b = secondary > 0 ? secondary / secondaries * 4f : 0;
            float c = inverted > 0 ? inverted / inversions * 3f : 0;
            score = (int) (((c + (a + b)) / weights) * 2f);
            int partner = 0;
            if (updatedRoles && familiarity >= 12) {
                partner = updatedPartner(positions, role, positionMask);
            } else if (!updatedRoles && familiarity >= 12 && (role & 0x48000000000000L) != 0) {
                partner = (positionMask & 0x111248) != 0 ? Byte.toUnsignedInt(positions[2])
                        : (positionMask & 0x208924) != 0 ? Byte.toUnsignedInt(positions[4])
                        : Math.max(Byte.toUnsignedInt(positions[2]), Byte.toUnsignedInt(positions[4]));
            } else if (!updatedRoles && familiarity >= 12 && (role & (1L << 52)) != 0) partner = Byte.toUnsignedInt(positions[3]);
            else if (!updatedRoles && familiarity >= 12 && (role & 0x2080008000000L) != 0) partner = Byte.toUnsignedInt(positions[10]);
            else if (!updatedRoles && familiarity >= 12 && (role & (1L << 40)) != 0) {
                partner = Byte.toUnsignedInt(positions[10]);
                if (partner <= 14) partner /= 2;
                if (partner != 0) familiarity = (familiarity + partner) / 2;
                partner = 0;
            } else if (!updatedRoles && familiarity >= 12 && (role & (1L << 14)) != 0) partner = Byte.toUnsignedInt(positions[5]);
            if (partner > familiarity) familiarity = (partner + familiarity) / 2;
            int gap = 20 - familiarity;
            int penalty = familiarity <= 11 ? gap * 4 : familiarity <= 14 ? (int) (gap * 2.5f) : gap;
            score = Math.max(1, score - score * penalty / 100);
        }
        if (score == 0) return ca & 255;
        if (cached == 0) return score & 255;
        if (originalFamiliarity <= 14 && cached > score) {
            int divisor = (int) (Math.max(0f, 1f - score / (float) cached) * 800f) + 2;
            score += (cached - score) / (divisor & 255);
        }
        int adjusted = score == cached ? ca : (int) (ca * Math.min(1f, score / (float) cached));
        int minimum = (role & 0x100000L) != 0 ? 120 : (role & 0x600100000000L) != 0 ? 100
                : (role & 0x9A000004000L) != 0 ? 80
                : (role & ((1L << 38) | (1L << 35))) != 0 ? 60 : 0;
        return adjusted < minimum ? (adjusted & 255) >>> 1 : adjusted & 255;
    }

    /** Current native scorer4978A70: each role has its own partner-position familiarity. */
    private static int updatedPartner(byte[] positions, long role, int mask) {
        if ((role & 0x1C0L) != 0) {
            return (mask & 0x111248) != 0 ? Byte.toUnsignedInt(positions[2])
                    : (mask & 0x208924) != 0 ? Byte.toUnsignedInt(positions[4])
                    : Math.max(Byte.toUnsignedInt(positions[2]), Byte.toUnsignedInt(positions[4]));
        }
        int left = -1, right = -1;
        if ((role & ((1L << 10) | (1L << 19) | (1L << 36))) != 0) { left = 13; right = 14; }
        else if ((role & ((1L << 13) | (1L << 29) | (1L << 39))) != 0) { left = 6; right = 8; }
        else if ((role & ((1L << 33) | (1L << 45))) != 0) { left = 9; right = 11; }
        if (left >= 0) return (mask & 0x111248) != 0 ? Byte.toUnsignedInt(positions[left])
                : (mask & 0x208924) != 0 ? Byte.toUnsignedInt(positions[right]) : 0;
        if ((role & (1L << 16)) != 0) return Byte.toUnsignedInt(positions[3]);
        if ((role & ((1L << 17) | (1L << 31))) != 0) return Byte.toUnsignedInt(positions[7]);
        if ((role & ((1L << 27) | (1L << 40) | (1L << 43))) != 0) return Byte.toUnsignedInt(positions[10]);
        return 0;
    }

    private void verifyNative(ProcessMemoryReader reader, long module) throws IOException {
        if (verifiedBase != null && verifiedBase == module) return;
        try {
            for (var anchor : layout.codeAnchors()) {
                byte[] bytes = reader.readBytes(module + anchor.rva(), anchor.bytes());
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!digest.equals(anchor.sha256())) throw new IOException("Player training selection native code anchor failed");
            }
        } catch (NoSuchAlgorithmException exception) { throw new IOException(exception); }
        verifiedBase = module;
    }

    private static Map<Integer, byte[]> classifiers(String resource, int expectedCount) {
        try (var stream = PlayerTrainingSelectionReader.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing native training classifiers");
            byte[] bytes = stream.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IOException("Native training classifier resource exceeds cap");
            Map<Integer, byte[]> result = new java.util.TreeMap<>();
            for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t");
                int bit = Integer.parseInt(fields[0]);
                byte[] categories = HexFormat.of().parseHex(fields[1]);
                if (bit < 0 || bit > 63 || categories.length != 54
                        || result.put(bit, categories) != null) throw new IOException("Invalid native training classifier");
                for (byte category : categories) if (category != -1 && category != 0 && category != 1 && category != 2)
                    throw new IOException("Invalid native training attribute importance");
            }
            if (result.size() != expectedCount) throw new IOException("Incomplete native training classifiers");
            return java.util.Collections.unmodifiableMap(result);
        } catch (IOException | RuntimeException error) { throw new ExceptionInInitializerError(error); }
    }

    public record Result(long roleBit, int[] nativeAttributeIDs) {
        public Result { nativeAttributeIDs = nativeAttributeIDs.clone(); }
        @Override public int[] nativeAttributeIDs() { return nativeAttributeIDs.clone(); }
    }
}
