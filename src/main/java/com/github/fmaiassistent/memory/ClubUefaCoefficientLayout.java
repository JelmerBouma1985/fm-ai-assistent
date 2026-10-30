package com.github.fmaiassistent.memory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Exact-image native layout. Evidence: CLUB_UEFA_COEFFICIENT_LAYOUT_20261026.md. */
public record ClubUefaCoefficientLayout(int clubDataRel, int coefficientRel, int nationRel, int selectorRel,
        int nationContextRel, int contextStride, int objectBytes, int countRel, int historyRel,
        int capacity, int historyStride, int cursorRel, int cacheRel, int rank5Rel, int rank10Rel,
        int value5Rel, int value10Rel, int currentPointsRel, long ruleVersionRva,
        int ruleVersionBytes, int ruleThreshold, int oldPointsScale, int newPointsScale, int coefficientScale) {
    public static final String MODULE_SHA256 = "eb6c86fab56e051fe41482c7b93d8cb0af716a1cc2c6197ce1babbff68372bb8";
    public static final String FM27_MODULE_SHA256 = ClubFinanceLayout.FM27_MODULE_SHA256;

    public static ClubUefaCoefficientLayout current() {
        return new ClubUefaCoefficientLayout(0x100, 0x40, 0xD8, 0xB9, 0x108, 8,
                0x4C, 0, 4, 10, 4, 0x2C, 0x30, 0x34, 0x38, 0x3C, 0x40, 0x44,
                0x4E08742L, 2, 0x653, 4, 100, 1000);
    }

    public static ClubUefaCoefficientLayout fm27() {
        return new ClubUefaCoefficientLayout(0x100,0x48,0xD8,0xB9,0x108,8,
                0x4C,0,4,10,4,0x2C,0x30,0x34,0x38,0x3C,0x40,0x44,
                0x56C212A,2,0x671,100,1000,1000);
    }

    public int pointsScale(int ruleVersion) {
        // FM27 retains the pre-0x654 four-point scale as well as its new 0x672 scale.
        if (ruleThreshold == 0x671 && ruleVersion < 0x654) return 4;
        return ruleVersion > ruleThreshold ? newPointsScale : oldPointsScale;
    }

    /** Pure decoding, never sums history to replace a native computed total. */
    public Values decode(byte[] raw, int ruleVersion) {
        if (raw.length != objectBytes || ruleVersion < 0 || ruleVersion > 0xFFFF)
            throw new IllegalArgumentException("Invalid UEFA coefficient extent/rule context");
        var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int count = Byte.toUnsignedInt(b.get(countRel)), cursor = Byte.toUnsignedInt(b.get(cursorRel));
        if (count > capacity || cursor >= capacity || !Float.isFinite(b.getFloat(cacheRel)))
            throw new IllegalArgumentException("Invalid UEFA coefficient count/cursor/cache");
        for (int i = 0; i < capacity; i++) bounded(b.getInt(historyRel + i * historyStride), 0, 100_000_000);
        for (int offset : new int[]{rank5Rel, rank10Rel}) bounded(b.getInt(offset), -1, 100_000);
        for (int offset : new int[]{value5Rel, value10Rel, currentPointsRel}) bounded(b.getInt(offset), 0, 100_000_000);
        List<BigDecimal> history = IntStream.range(0, count)
                .map(i -> (cursor + i) % capacity)
                .mapToObj(i -> scaled(b.getInt(historyRel + i * historyStride), coefficientScale)).toList();
        return new Values(history, rank(b.getInt(rank5Rel)), rank(b.getInt(rank10Rel)),
                scaled(b.getInt(value5Rel), coefficientScale), scaled(b.getInt(value10Rel), coefficientScale),
                scaled(b.getInt(currentPointsRel), pointsScale(ruleVersion)), b.getFloat(cacheRel));
    }

    /** Old rows, unsupported images or malformed payloads remain unavailable. */
    public static Optional<Values> values(String hex, Integer ruleVersion, String sourceSha256) {
        if ((!MODULE_SHA256.equals(sourceSha256) && !FM27_MODULE_SHA256.equals(sourceSha256)) || hex == null || ruleVersion == null || hex.length() != 152)
            return Optional.empty();
        try { return Optional.of((FM27_MODULE_SHA256.equals(sourceSha256) ? fm27() : current()).decode(HexFormat.of().parseHex(hex), ruleVersion)); }
        catch (IllegalArgumentException | IndexOutOfBoundsException invalid) { return Optional.empty(); }
    }

    private static void bounded(int value, int low, int high) {
        if (value < low || value > high) throw new IllegalArgumentException("UEFA coefficient value outside native range anchor");
    }
    private static Integer rank(int index) { return index < 0 ? null : index + 1; }
    private static BigDecimal scaled(int raw, int scale) { return BigDecimal.valueOf(raw).divide(BigDecimal.valueOf(scale)); }

    public record Values(List<BigDecimal> history, Integer fiveYearRank, Integer tenYearRank,
                         BigDecimal fiveYearCoefficient, BigDecimal tenYearCoefficient,
                         BigDecimal currentSeasonPoints, float cachedComputedCoefficient) {
        public Values { history = List.copyOf(history); }
    }
}
