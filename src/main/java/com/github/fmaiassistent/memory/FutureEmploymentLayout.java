package com.github.fmaiassistent.memory;

/** FM27 future employment records, separate from the primary PERSON contract links. */
public record FutureEmploymentLayout(int personRecords, int recordSize, int recordContract,
                                     int recordOfferIndex, int recordState, int contractWage,
                                     int contractDateFlags, int gameDateFlagMask, int contractEnd,
                                     int gameDateRva, int peopleRegistry) {

    /** Exact-image profile for FM27 27.0.1+2494687. */
    public static FutureEmploymentLayout forModule(String hash) {
        if (NativeModuleProfile.FM27_SHA256.equals(hash)) return current();
        NativeModuleProfile.require2701(hash);
        return new FutureEmploymentLayout(0xA0, 0x48, 0x10, 0x28, 0x3E,
                0x20, 0x2B, 8, 0x48, 0x57CA3B8, 0x5820778);

    }

    public static FutureEmploymentLayout current() {
        return new FutureEmploymentLayout(0xA0, 0x48, 0x10, 0x28, 0x3E,
                0x20, 0x2B, 8, 0x48, 0x56AD378, 0x5703738);
    }
}
