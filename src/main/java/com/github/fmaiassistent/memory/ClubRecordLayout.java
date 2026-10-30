package com.github.fmaiassistent.memory;

/**
 * All byte offsets of the team/club record and its linked finance and
 * facilities structures.
 *
 * <p>Canonical home for the {@code *_REL} values previously scattered as
 * constants across the club exporter. {@link #current()} holds the offsets
 * of the supported build; alternate instances describe other builds (or
 * synthetic drift in tests). Finance marker values are policy, not layout,
 * and stay with the decoding code.</p>
 */
public record ClubRecordLayout(
        long teamClubRel,
        long teamCompetitionRel,
        long teamReputationRel,
        long competitionGenderFlagRel,
        long clubFinanceBlockRel,
        long clubFacilitiesBlockRel,
        long clubBalanceRel,
        long clubTransferBudgetRel,
        long clubPayrollBudgetRel,
        long clubCorporateFacilitiesRel,
        long clubTrainingFacilitiesRel,
        long clubYouthFacilitiesRel,
        long clubYouthCoachingRel,
        long clubYouthRecruitmentRel) {

    /** Historical layout for the earlier verified executable image. */
    public static ClubRecordLayout current() {
        return new ClubRecordLayout(
                0x30L,
                0x50L,
                0xA8L,
                0xF9L,
                0x150L,
                0x100L,
                0x14L,
                0x7CCL,
                0x810L,
                0x8B5L,
                0x118L,
                0x123L,
                0x124L,
                0x125L);
    }

    /** FM27 27.0.0+2486695: native finance prefix and CLUB_DATA member drift. */
    public static ClubRecordLayout fm27() {
        return new ClubRecordLayout(0x30,0x50,0xA8,0xF9,0x150,0x100,
                0xC,0x7C4,0x808,0x8A9,0x120,0x12B,0x12C,0x12D);
    }
}
