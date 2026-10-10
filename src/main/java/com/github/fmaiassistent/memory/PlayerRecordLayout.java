package com.github.fmaiassistent.memory;

/**
 * All byte offsets of the FM26 player record and its linked structures.
 *
 * <p>Canonical home for the {@code *_REL} values previously scattered as
 * constants across the exporter. {@link #current()} holds the offsets of the
 * supported build; alternate instances describe other builds (or synthetic
 * drift in tests). Offsets that name a struct field shared with the UI/MCP
 * layers ({@code historyCopySourceRel} and friends) intentionally duplicate
 * their counterparts so this package stays dependency-free; a unit test
 * asserts they stay in sync.</p>
 *
 * <p>Policy values (bit masks, magic bytes, grace periods, safety bounds) are
 * not layouts and stay with the decoding code.</p>
 */
public record PlayerRecordLayout(Direct direct, Registration registration, Duty duty, Injury injury) {

    /** Offsets relative to the player record base. */
    public record Direct(
            int uniqueIdRel,
            int dateOfBirthRel,
            int genderRel,
            int heightCmRel,
            int joinedClubDateRel,
            int registrationRefRel,
            int playingClubRefRel,
            int playingClubBodyRel,
            int injuryReferenceRel,
            int dutyReferenceRel,
            int historyCopySourceRel,
            int homeReputationRel,
            int currentReputationRel,
            int worldReputationRel,
            int currentAbilityRel,
            int potentialAbilityRel,
            int displayValueRel) {
    }

    /** Offsets relative to the registration structure ({@code record + registrationRefRel}). */
    public record Registration(
            int clubRel,
            int clubBodyRel,
            int transferStatusRel,
            int transferAgreedMarkerRel,
            int futureTransferActiveRel,
            int futureTransferSentinelRel,
            int futureTransferTableRel,
            int futureTransferClubRel,
            int futureTransferDateRel,
            int futureTransferContractEndDateRel,
            int joinedDateRel,
            int contractDateRel,
            int salaryWeeklyRel) {
    }

    /** International-duty container vector, callup item layout, team reference and estimated-return date. */
    public record Duty(
            int vectorRel,
            int recordSize,
            int startDayRel,
            int startYearRel,
            int endDayRel,
            int endYearRel,
            int teamReferenceRel,
            int returnDateRel) {
    }

    /** Injury item layout behind the injury reference. */
    public record Injury(
            int typeRel,
            int typeNameRel,
            int dayRel,
            int yearRel,
            int fullTrainingRel,
            int lightTrainingRel) {
    }

    /** The offsets of the currently supported FM26 build. */
    public static PlayerRecordLayout current() {
        return new PlayerRecordLayout(
                new Direct(
                        0x0C,
                        0x88,
                        0x19,
                        -0x5A,
                        -0x38,
                        0xA8,
                        -0x158,
                        0x30,
                        -0x190,
                        -0x168,
                        -0x13A,
                        -0x2A,
                        -0x28,
                        -0x26,
                        -0x24,
                        -0x22,
                        -0x54),
                new Registration(
                        0x10,
                        0x30,
                        0x57,
                        0x51,
                        0x100,
                        0x104,
                        0xD8,
                        0x30,
                        0x10C,
                        0x110,
                        0x4C,
                        0x48,
                        0x20),
                new Duty(0x50, 0x20, 0x10, 0x12, 0x14, 0x16, -0x160, 0xBC),
                new Injury(0x08, 0x20, 0x20, 0x22, 0x28, 0x2A));
    }
}
