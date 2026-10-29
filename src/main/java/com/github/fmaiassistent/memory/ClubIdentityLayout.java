package com.github.fmaiassistent.memory;

/** Exact Windows EB6C86...BB8 CLUB registry/common DATABASE_RECORD prefix and TEAM owner namespace. */
public record ClubIdentityLayout(int registryRva, int registryHeader, int index, int uniqueId, int prefixBytes,
                                 long clubVtable, long leagueBodyVtable, int teamOwnerFlag,
                                 long teamVtable, long nationalTeamVtable) {
    public static ClubIdentityLayout current() {
        return new ClubIdentityLayout(0x4E49588,0x80,8,12,16,0x44BA518,0x4740AB8,0x2F,0x44C13A8,0x4592528);
    }
}
