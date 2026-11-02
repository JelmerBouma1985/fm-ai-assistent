package com.github.fmaiassistent.memory;

/** Native 11592A0 club physician selection and 409A1B0 assessment seed. */
public record PlayerMedicalStaffLayout(int currentTeam, int teamKind, int teamClub,
        int clubStaff, int staffVirtualBase, int personDisplacement, int uniqueId,
        int contract, int secondaryContracts, int contractTeam, int contractClub,
        int primaryRole, int secondaryRole, int roleGetterSlot, long roleGetterRva, long zeroRoleGetterRva,
        int sportsScience, int currentAbility) {
    public static PlayerMedicalStaffLayout current() {
        return new PlayerMedicalStaffLayout(0x130,0x2F,0x30,0x60,0,4,0x0C,
                0xA8,0xB0,0x10,0x30,0x26,0xC2,0x68,0x3019A0,0x301BD0,0x3F,0xDA);
    }
}
