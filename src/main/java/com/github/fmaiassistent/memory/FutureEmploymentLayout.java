package com.github.fmaiassistent.memory;

/** Windows26.3.2/EB6C86...BB8; PERSON v358 and FULL_CONTRACT vE8.
 * See FUTURE_EMPLOYMENT_CONTRACTS_AND_NATIVE_UI_PRODUCER_20261024.md.
 * These records are separate from the PERSON A8/B0 contract links. */
public record FutureEmploymentLayout(int personRecords, int recordSize, int recordContract,
                                     int recordOfferIndex, int recordState, int contractWage,
                                     int contractDateFlags, int gameDateFlagMask, int contractEnd,
                                     int gameDateRva, int peopleRegistry) {
    public static FutureEmploymentLayout current() {
        return new FutureEmploymentLayout(0xA0, 0x48, 0x10, 0x28, 0x3E,
                0x20, 0x2B, 8, 0x48, 0x4DF3C18, 0x4E495E0);
    }
}
