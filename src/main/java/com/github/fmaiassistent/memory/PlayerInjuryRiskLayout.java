package com.github.fmaiassistent.memory;

/** FM27 27.0.0+2486695 medical members, relative to the complete PLAYER object. */
public record PlayerInjuryRiskLayout(
        int injuryPronenessRel,
        int attributeEncodingRel,
        int recurringInjuriesRel,
        int recurringInjuryStride,
        int injuryReferenceRel,
        int activeInjuryTypeRel,
        int injuryTypeIndexRel,
        int injuryTypeKindRel,
        int injuryTypeOccurrenceRel) {
    public static PlayerInjuryRiskLayout current() {
        return new PlayerInjuryRiskLayout(0x18F, 0x278, 0x20C, 4, 0xF8, 8, 8, 0x65, 0x6C);
    }

    public record TrainingCategories(int managerDefinitions, int definitionCode, int definitionCategories,
            int roleCategoryStride, int roleAttributesBegin, int roleAttributesEnd, int roleAttributesStride,
            int roleIndividualFlag, int dayStride, int firstSession, int virtualBase, int attributesDisplacement,
            int professionalism, int ambition, int goalkeeper, int categorySizesRva, int categorySizeStride,
            int firstMapRva, int goalkeeperMapRva, int outfieldMapRva, int personalDayExclusionsRva) {
        public static TrainingCategories current() {
            return new TrainingCategories(0x68, 0xFA, 0, 36, 0x90, 0x98, 24, 0xF6,
                    8, 2, 8, 8, 0x54, 0x51, 0x150, 0x4EBD048, 19,
                    0x4EBD42C, 0x4EBD1BC, 0x4EBD504, 0x4D34208);
        }
    }

    public record Calculation(int disabledRva, int managerRva, int catalogRva, int registryHeader,
            int vectorBegin, int vectorEnd, int flags, int personVirtualBase, int personDisplacement,
            int personIndex, int previousBegin, int previousEnd, int previousPlayerIndex,
            int previousInjuryIndex, int injuryFactorMask, int recurringCount, int recoveryState) {
        public static Calculation current() {
            return new Calculation(0x56FF311, 0x56F5D08, 0x5703710, 0x80, 0, 8,
                    0x1EE, 8, 4, 0x10, 0xB8, 0xC0, 4, 8, 0x66, 2, 0x30);
        }
    }
}
