package com.example.fmgenie26.player;

import java.util.List;

public final class AttributeDefinitions {
    public static final int HISTORY_COPY_SOURCE_REL = -0x13A;
    public static final int SOURCE_OBJECT_BASE_OFFSET = 0x28;
    public static final int HOME_REPUTATION_REL = -0x2A;
    public static final int CURRENT_REPUTATION_REL = -0x28;
    public static final int WORLD_REPUTATION_REL = -0x26;
    public static final int CURRENT_ABILITY_REL = -0x24;
    public static final int POTENTIAL_ABILITY_REL = -0x22;
    public static final int DISPLAY_VALUE_REL = -0x54;

    public static final List<FieldDef> POSITION_FIELDS = List.of(
            new FieldDef(0x2A, "Goalkeeper"), new FieldDef(0x2C, "DefenderLeft"),
            new FieldDef(0x2D, "DefenderCentral"), new FieldDef(0x2E, "DefenderRight"),
            new FieldDef(0x37, "WingBackLeft"), new FieldDef(0x2F, "DefensiveMidfielder"),
            new FieldDef(0x38, "WingBackRight"), new FieldDef(0x30, "MidfielderLeft"),
            new FieldDef(0x31, "MidfielderCentral"), new FieldDef(0x32, "MidfielderRight"),
            new FieldDef(0x33, "AttackingMidfielderLeft"), new FieldDef(0x34, "AttackingMidfielderCentral"),
            new FieldDef(0x35, "AttackingMidfielderRight"), new FieldDef(0x36, "Striker")
    );

    public static final List<FieldDef> VISIBLE_FIELDS = List.of(
            new FieldDef(0x39, "Crossing"), new FieldDef(0x3A, "Dribbling"),
            new FieldDef(0x3B, "Finishing"), new FieldDef(0x3C, "Heading"),
            new FieldDef(0x3D, "LongShots"), new FieldDef(0x3E, "Marking"),
            new FieldDef(0x3F, "OffTheBall"), new FieldDef(0x40, "Passing"),
            new FieldDef(0x41, "Penalties"), new FieldDef(0x42, "Tackling"),
            new FieldDef(0x43, "Vision"), new FieldDef(0x44, "Handling"),
            new FieldDef(0x45, "AerialAbility"), new FieldDef(0x46, "CommandOfArea"),
            new FieldDef(0x47, "Communication"), new FieldDef(0x48, "Kicking"),
            new FieldDef(0x49, "Throwing"), new FieldDef(0x4A, "Anticipation"),
            new FieldDef(0x4B, "Decisions"), new FieldDef(0x4C, "OneOnOnes"),
            new FieldDef(0x4D, "Positioning"), new FieldDef(0x4E, "Reflexes"),
            new FieldDef(0x4F, "FirstTouch"), new FieldDef(0x50, "Technique"),
            new FieldDef(0x51, "LeftFoot"), new FieldDef(0x52, "RightFoot"),
            new FieldDef(0x53, "Flair"), new FieldDef(0x54, "Corners"),
            new FieldDef(0x55, "Teamwork"), new FieldDef(0x56, "WorkRate"),
            new FieldDef(0x57, "LongThrows"), new FieldDef(0x58, "Eccentricity"),
            new FieldDef(0x59, "RushingOut"), new FieldDef(0x5A, "TendencyToPunch"),
            new FieldDef(0x5B, "Acceleration"), new FieldDef(0x5C, "FreeKicks"),
            new FieldDef(0x5D, "Strength"), new FieldDef(0x5E, "Stamina"),
            new FieldDef(0x5F, "Pace"), new FieldDef(0x60, "JumpingReach"),
            new FieldDef(0x61, "Leadership"), new FieldDef(0x62, "Dirtiness"),
            new FieldDef(0x63, "Balance"), new FieldDef(0x64, "Bravery"),
            new FieldDef(0x65, "Consistency"), new FieldDef(0x66, "Aggression"),
            new FieldDef(0x67, "Agility"), new FieldDef(0x68, "ImportantMatches"),
            new FieldDef(0x69, "InjuryProneness"), new FieldDef(0x6A, "Versatility"),
            new FieldDef(0x6B, "NaturalFitness"), new FieldDef(0x6C, "Determination"),
            new FieldDef(0x6D, "Composure"), new FieldDef(0x6E, "Concentration")
    );

    private AttributeDefinitions() {
    }

    public static int direct20(int raw) {
        return Math.max(1, Math.min(20, raw));
    }

    public static int norm20(int raw) {
        if (raw <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(20, Math.round(raw * 20f / 100f)));
    }

    public static long roundObservedAskingPrice(long raw) {
        if (raw <= 0) {
            return 0;
        }
        if (raw < 100_000) {
            return ceilTo(raw, 1_000);
        }
        if (raw < 500_000) {
            return ceilTo(raw, 5_000);
        }
        return Math.round(raw / 25_000.0) * 25_000;
    }

    private static long ceilTo(long raw, int unit) {
        return ((raw + unit - 1) / unit) * unit;
    }
}
