package com.github.fmaiassistent.memory;

/** Native PLAYER injuries and INJURY fields used by 15D4D30 and 2EE2B50. */
public record PlayerMedicalAssessmentLayout(int medicalRel, int vectorBeginRel,
        int vectorEndRel, int vectorCapacityRel, int typeRel, int startDayRel,
        int startYearRel, int fullDurationRel, int lightDurationRel, int stateRel,
        int weeksRemainingRel, int pronenessRel, int naturalFitnessRel, int encodingRel,
        int reputationRel, int recurringRel, int historyIndexRel, long managerRva,
        int historyStoreRel, long typeRegistryRva, long registryModeRva,
        int storeMemoryRel, int registryVectorRel, int registryDirectBeginRel,
        int registryDirectEndRel, int historyDayRel, int historyYearRel,
        int historyTypeRel, int historyDurationRel, int historyStateRel,
        int typeBodyPartRel, int typeOccurrenceRel, int typeRepeatableRel,
        int conditionRel, int sharpnessRel, int fatigueRel, int recurringStride,
        int archiveOwnerRel, int archivePathRel, int archiveNameRel, int pathTextRel,
        long pathVtableRva, int archiveIndexBeginRel, int archiveIndexEndRel,
        int archiveIndexCapacityRel, int archiveIndexStride, int archiveVersionRel,
        int statusVectorRel, int statusKindRel, int statusDayRel, int statusYearRel,
        int statusDurationRel, long statusCutoffFlagRva, long statusManagerRva,
        int statusCutoffDayRel, int statusCutoffYearRel) {
    public static PlayerMedicalAssessmentLayout current() {
        return new PlayerMedicalAssessmentLayout(0xF8, 0, 8, 16, 8, 0x20,
                0x22, 0x28, 0x2A, 0x2E, 0x33, 0x18F, 0x191, 0x278, 0x262,
                0x20C, 0x208, 0x56F5D08L, 0x50, 0x5703710L, 0x5703DE0L,
                8, 0x80, 0x60, 0x68, 4, 6, 8, 0xA, 0xE, 0x64, 0x6C, 0x71,
                0x258, 0x25C, 0x25A, 4, 0, 0, 0x10, 0x10, 0x5131588L,
                0x78, 0x80, 0x88, 24, 0xA0, 0x18, 5, 0x10, 0x12, 0x16,
                0x56FF347L, 0x5709280L, 0x164, 0x166);
    }
}
