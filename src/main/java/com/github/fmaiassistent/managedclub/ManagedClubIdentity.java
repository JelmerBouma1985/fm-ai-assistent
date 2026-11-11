package com.github.fmaiassistent.managedclub;

public record ManagedClubIdentity(
        long managerAddress,
        Long managerUniqueId,
        String managerName,
        long teamAddress,
        long clubAddress,
        String clubName,
        ManagedNationalTeam nationalTeam) {

    public ManagedClubIdentity(long managerAddress, Long managerUniqueId, String managerName,
                               long teamAddress, long clubAddress, String clubName) {
        this(managerAddress, managerUniqueId, managerName, teamAddress, clubAddress, clubName, null);
    }

    public boolean unemployed() {
        return teamAddress == 0 && clubAddress == 0 && nationalTeam == null;
    }

    public ManagedClubIdentity(
            long managerAddress,
            String managerName,
            long teamAddress,
            long clubAddress,
            String clubName) {
        this(managerAddress, null, managerName, teamAddress, clubAddress, clubName);
    }
}
