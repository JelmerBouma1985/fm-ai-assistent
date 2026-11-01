package com.github.fmaiassistent.memory;

/** Exact-build native scouting ABI, anchored by UID-checked live reports and native callers. */
public record ScoutingReportLayout(
        long managerRva, long clockRva, long peopleRva, int peopleHeaderRel,
        int uidRel, int indexRel, int employmentRel, int contractTeamRel, int teamClubRel,
        int staffDisplacement, int playerDisplacement, int playerStaffDisplacement, int humanDisplacement,
        int judgementRel, int jobRel, int reportsRel, int extraRel, int percentRel,
        int categoryRel, int extraJcaRel, long factoryRva, long registerRva,
        long lookupRva, long extraDestructorRva, long extraFreeRva, long tagFreeRva,
        int rttiDisplacementRel, int playerVbtableRel, int playerVbtablePersonRel,
        int tagPersonRel, int tagStaffRel, int tagTeamRel,
        long recruitmentEntryAllocateRva, int humanDataRel, int recruitmentContextRel,
        int recruitmentEntriesRel, int recruitmentEntryTagRel, long recruitmentEntryVtableRva,
        long recruitmentEntryPoolRva, long recruitmentEntryInitRva, long recruitmentEntryDeleteRva,
        long recruitmentEntryAddRva, int recruitmentEntryBytes, int recruitmentEntryDateRel,
        int recruitmentEntryIdRel, int recruitmentEntryDefaultsRel, int recruitmentEntryDeliveryRel,
        long recruitmentEntryPoolGuardRva, int recruitmentPoolsBeginRel, int recruitmentPoolsEndRel,
        int recruitmentPoolPointerBytes) {
    public static final String MODULE_SHA256 =
            "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";

    public static ScoutingReportLayout current() {
        return new ScoutingReportLayout(0x56F32C8, 0x56AD378, 0x5703738, 0x80,
                0x0C, 0x08, 0xA8, 0x10, 0x30, 0x100, 0x288, 0x380, 0x440,
                0x2C, 0x26, 0x110, 0x30, 0x3F, 0x40, 0x84,
                0x2675F00, 0xF2EF90, 0xF1E130, 0x1A22D10, 0xF54940, 0x55B790,
                4, 8, 4, 0, 8, 16,
                0x1570C0, 0x388, 0xBD8, 0x138, 0x18, 0x4CBE7D8,
                0x5709D90, 0x2D13FE0, 0xECEAB0, 0x26CB630, 0x58, 8, 12, 0x4C, 0x54, 0x5709DD0,
                0x18, 0x20, 8);
    }
}
