package com.github.fmaiassistent.memory;

/** FM27 CLUB registry and TEAM owner namespace. */
public record ClubIdentityLayout(int registryRva, int registryHeader, int index, int uniqueId, int prefixBytes,
                                 long clubVtable, long leagueBodyVtable, int teamOwnerFlag,
                                 long teamVtable, long nationalTeamVtable) {

    /** Exact-image profile for FM27 27.0.1+2494687. */
    public static ClubIdentityLayout forModule(String hash) {
        if (NativeModuleProfile.FM27_SHA256.equals(hash)) return current();
        NativeModuleProfile.require2701(hash);
        return new ClubIdentityLayout(0x5820720,0x80,8,12,16,0x4DDE298,0x507E7D8,0x2F,0x4DE51A8,0x4EC6D88);

    }

    /** FM27 registry and independently read native RTTI identities. */
    public static ClubIdentityLayout current() {
        return new ClubIdentityLayout(0x57036E0,0x80,8,12,16,0x4CDA1F8,0x4F65538,0x2F,0x4CE1108,0x4DC2288);
    }
}
