package com.github.fmaiassistent.memory;

/** Exact-image financial variants: native typed64-byte base and2240-byte extended object.
 * Field displacements remain in ClubRecordLayout; these are independent type/extent anchors.
 */
public record ClubFinanceLayout(long baseVtableRva, long fullVtableRva,
                                int baseBytes, int fullBytes, int ownerRel,
                                int currentWagesRel, int firstUncachedWage) {
    public static final String MODULE_SHA256 = "eb6c86fab56e051fe41482c7b93d8cb0af716a1cc2c6197ce1babbff68372bb8";
    public static final String FM27_MODULE_SHA256 = "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";
    public static ClubFinanceLayout current() {
        return new ClubFinanceLayout(0x456CE48L, 0x456D2E8L, 0x40, 0x8C0, 8, 0x81C, -1001);
    }
    /** The owner pointer was removed; -1 requires a guarded CLUB -> finance relationship instead. */
    public static ClubFinanceLayout fm27() {
        return new ClubFinanceLayout(0x4CF07F8,0x4CF0CC8,0x38,0x8B0,-1,0x814,-1001);
    }
}
