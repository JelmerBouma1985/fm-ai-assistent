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

    /** Current FM27 layout. */

    /** FM27 27.0.0+2486695: native finance prefix and CLUB_DATA member drift. */
    public static ClubRecordLayout current() {
        return new ClubRecordLayout(0x30,0x50,0xA8,0xF9,0x150,0x100,
                0xC,0x7C4,0x808,0x8A9,0x120,0x12B,0x12C,0x12D);
    }
}
