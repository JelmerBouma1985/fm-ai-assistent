package com.github.fmaiassistent.memory;

/** FM27 27.0.0+2486695 native training helpers 2DB3330,164DCB0,168A270,16B1530. */
public record PlayerTrainingLoadLayout(Globals globals, Player player, Manager manager, Team team,
        Human human, Tree tree, Schedule schedule, Session session, Extra extra, Focus focus, Role role,
        PreferredPosition preferredPosition, Tactic tactic, RestCalendar restCalendar) {
    public record Globals(long manager, long enabled, long humanPredicate, long humanGetter,
                          long scheduleScale, long personalityDivisor, long personalityMultiplier) {}
    public record Player(int virtualBase, int personDisplacement, int attributesDisplacement,
                         int personIndex, int flags, int internationalTeam, int currentTeam,
                         int extra, int individual, int focus, int position, int intensity,
                         int nationalIntensity, int sharpness, int professionalism, int ambition) {}
    public record Manager(int contexts, int definitions, int overrideIntensity, int overridePlayers,
                          int cacheHead, int cacheCount) {}
    public record Team(int uid, int kind, int club, int coach, int clubTeams,
                       int returnDay, int returnYear, int dutyActive, int affiliateFlags,
                       int affiliates, int affiliateClub, int affiliateKind, int affiliateOwner) {}
    public record Human(int virtualBaseDisplacement, int predicateSlot, int getterSlot,
                        int interfaceCorrection, int rootDisplacement, int contextOwner,
                        int configurations, int configurationValue, int intensityTable) {}
    public record Tree(int root, int right, int nil, int key, int value) {}
    public record Schedule(int weeks, int weekDay, int weekYear, int days, int dayStride,
                           int firstSession, int cacheKey, int cacheValue, int cacheNext) {}
    public record Session(int code, int physical, int tactical, int technical) {}
    public record Extra(int stride, int type, int remaining, int inactive,
                        int individualState, int focusState, int positionState) {}
    public record Focus(int sessionEffects, int personalDaysFlag, long reducedMultiplier,
                        long increasedMultiplier, long positiveRounding, long negativeRounding) {}
    public record Role(long contextManager, int contexts, int contextOwner, int teamIndex,
                       int selectedTactic, int tactics, int lineup, int tacticPositions, int tacticSlotStride,
                       int aiState, int aiLineup, int aiPlayers, int positionSource, int goalkeeper,
                       int firstPosition, long extraPositionTable, long packedPositionTable) {}
    public record PreferredPosition(int cache, int position, int development, int startDay, int startYear,
                                    long positionFlags, long attributeRoles) {}
    public record Tactic(int settings, int extendedSettings, int mentality, int packedPressing,
                         int tempo, int slots, int slotStride, int slotPosition, int slotRole,
                         int instructions, int instructionStride, int instructionKey, int instructionBits,
                         long increasedRisk, long highRisk) {}
    public record RestCalendar(int records, int person, int startDay, int startYear, int endDay, int endYear) {}
    public static PlayerTrainingLoadLayout current() {
        return new PlayerTrainingLoadLayout(
                new Globals(0x56F23B0,0x56FF347,0xF47D90,0xF4A0F0,0x4D2A24C,0x4AFB6D8,0x4B01814),
                new Player(8,4,8,0x10,0x1EE,0x128,0x130,0x1A8,0x198,0x1A0,0x1C0,
                        0x1F3,0x1FD,0x25C,0x54,0x51),
                new Manager(0x40,0x68,0x39,0x20,0x98,0xA0),
                new Team(0x0C,0x2F,0x30,0x80,0x18,0xBC,0xBE,0xE6,0x163,0x118,8,0x30,0),
                new Human(4,0x1A8,0x698,4,0x440,0x70,0x40,0x28,0x60),
                new Tree(8,0x10,0x19,0x20,0x28),
                new Schedule(0x20,0x0C,0x0E,0x10,8,2,0x10,0x18,0),
                new Session(0xFA,0xF0,0xF1,0xF2),
                new Extra(0x18,8,0x0A,0x17,6,6,4),
                new Focus(0xF0,0x80,0x4AFC130,0x4C8C3A4,0x4AFC130,0x4AFC134),
                new Role(0x56F5DE0,0x20,0x78,8,0x1050,0x1038,0x1058,0x118,0x48,
                        0xA0,0xD0,0x30,0x148,8,0x0A,0x4FBB698,0x4D2E314),
                new PreferredPosition(0x108,2,3,0xFC,0xFE,0x4D36ACC,0x4FBB700),
                new Tactic(0xE0,0xE8,0x105,0xBC,0xE8,0x438,0x48,0,0x40,
                        8,0x18,0,8,0x4AFFF50,0x4AFEC20),
                new RestCalendar(0x18,0,8,0x0A,0x0C,0x0E));
    }
}
