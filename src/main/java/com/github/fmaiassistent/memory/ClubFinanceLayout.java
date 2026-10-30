package com.github.fmaiassistent.memory;

/** FM27 financial variants: native 56-byte base and 2224-byte extended object.
 * Field displacements remain in ClubRecordLayout; these are independent type/extent anchors.
 */
public record ClubFinanceLayout(long baseVtableRva, long fullVtableRva,
                                int baseBytes, int fullBytes, int ownerRel,
                                int currentWagesRel, int firstUncachedWage) {
    public static final String MODULE_SHA256 = "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";

    /** The owner pointer was removed; -1 requires a guarded CLUB -> finance relationship instead. */
    public static ClubFinanceLayout current() {
        return new ClubFinanceLayout(0x4CF07F8,0x4CF0CC8,0x38,0x8B0,-1,0x814,-1001);
    }
}
