package com.github.fmaiassistent.memory;

/** Exact-image financial variants: native typed64-byte base and2240-byte extended object.
 * Field displacements remain in ClubRecordLayout; these are independent type/extent anchors.
 */
public record ClubFinanceLayout(long baseVtableRva, long fullVtableRva,
                                int baseBytes, int fullBytes, int ownerRel,
                                int currentWagesRel, int firstUncachedWage) {
    public static final String MODULE_SHA256 = "eb6c86fab56e051fe41482c7b93d8cb0af716a1cc2c6197ce1babbff68372bb8";
    public static ClubFinanceLayout current() {
        return new ClubFinanceLayout(0x456CE48L, 0x456D2E8L, 0x40, 0x8C0, 8, 0x81C, -1001);
    }
}
