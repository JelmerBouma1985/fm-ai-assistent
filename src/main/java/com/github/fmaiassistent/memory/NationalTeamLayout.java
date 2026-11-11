package com.github.fmaiassistent.memory;

/**
 * NATIONAL_TEAM_CONTAINER nation link, verified read-only on FM27 27.0.1+2494687 Steam.
 * The Netherlands manager has a null primary contract and additional contract slot 2.
 * Its typed national container links at +0x170 to NATION UID 784 (matching the container),
 * whose +0x18 name is Netherlands. The adjacent +0x168 points to a different native type.
 */
public record NationalTeamLayout(int nationRel, int nameRel, int alternateNameRel, int bytes) {
    public static NationalTeamLayout current() {
        return new NationalTeamLayout(0x170, 0x18, 0x20, 0x178);
    }
}
