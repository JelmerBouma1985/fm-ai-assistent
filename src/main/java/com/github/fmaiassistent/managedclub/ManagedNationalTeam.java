package com.github.fmaiassistent.managedclub;

/** National employment is separate from the club job, including for dual-role managers. */
public record ManagedNationalTeam(long teamAddress, long containerAddress, long nationAddress,
                                 long nationUniqueId, String name) { }
