package com.github.fmaiassistent.managedclub;

public record ManagedClubIdentity(
        long managerAddress,
        String managerName,
        long teamAddress,
        long clubAddress,
        String clubName) {
}
