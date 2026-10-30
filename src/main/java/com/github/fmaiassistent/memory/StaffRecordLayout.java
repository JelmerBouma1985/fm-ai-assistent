package com.github.fmaiassistent.memory;

/**
 * All byte offsets of the FM27 staff record and its linked structures.
 *
 * <p>Canonical home for the {@code *_REL} values previously scattered as
 * constants across the staff exporter. {@link #current()} holds the offsets
 * of the supported build; alternate instances describe other builds (or
 * synthetic drift in tests). The attribute-table base duplicates its
 * counterpart so this package stays dependency-free; a unit test asserts they
 * stay in sync. Per-attribute table offsets stay with
 * {@code StaffAttributeDefinitions}, mirroring the player attribute block.</p>
 */
public record StaffRecordLayout(
        int uniqueIdRel,
        int genderRel,
        int dateOfBirthRel,
        int contractRefRel,
        int contractTeamRel,
        int contractClubRel,
        int contractJobIdRel,
        int contractSalaryWeeklyRel,
        int contractDateRel,
        int homeReputationRel,
        int currentReputationRel,
        int worldReputationRel,
        int caRel,
        int paRel,
        int attributesRel,
        int divisionScanRelA,
        int divisionScanRelB) {

    /** Shared scalar structure retained by FM27 27.0.0+2486695; live physical ranges rechecked. */
    public static StaffRecordLayout current() {
        return new StaffRecordLayout(
                0x0C,
                0x19,
                0x88,
                0xA8,
                0x10,
                0x30,
                0x26,
                0x20,
                0x48,
                0xD4,
                0xD6,
                0xD8,
                0xDA,
                0xDC,
                0x10,
                0x50,
                0x60);
    }
}
