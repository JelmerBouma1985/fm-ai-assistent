package com.github.fmaiassistent.managedclub;

public record ManagedClubIdentity(
        long managerAddress,
        Long managerUniqueId,
        String managerName,
        long teamAddress,
        long clubAddress,
        String clubName) {

    public ManagedClubIdentity(
            long managerAddress,
            String managerName,
            long teamAddress,
            long clubAddress,
            String clubName) {
        this(managerAddress, null, managerName, teamAddress, clubAddress, clubName);
    }
}
