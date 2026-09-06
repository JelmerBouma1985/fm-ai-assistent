package com.github.fmaiassistent.memory;

import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;

/** All build-gated roots that are safe to use for one FM26 executable build. */
public record FmBuildProfile(
        int build,
        long offsetTableRva,
        OptionalLong currentDateRva,
        OptionalLong currentHumanManagerRva,
        Set<Capability> capabilities) {

    public FmBuildProfile {
        if (build <= 0 || offsetTableRva <= 0) {
            throw new IllegalArgumentException("FM build and offset-table RVA must be positive");
        }
        currentDateRva = currentDateRva == null ? OptionalLong.empty() : currentDateRva;
        currentHumanManagerRva = currentHumanManagerRva == null
                ? OptionalLong.empty() : currentHumanManagerRva;
        EnumSet<Capability> safe = EnumSet.of(Capability.CATALOG_TABLES);
        if (currentDateRva.isPresent()) {
            safe.add(Capability.CURRENT_DATE);
        }
        if (currentHumanManagerRva.isPresent()) {
            safe.add(Capability.MANAGED_CLUB);
        }
        if (capabilities != null) {
            safe.addAll(capabilities);
        }
        capabilities = Set.copyOf(safe);
    }

    public String buildHex() {
        return "0x" + Integer.toHexString(build);
    }

    public enum Capability {
        CATALOG_TABLES,
        CURRENT_DATE,
        MANAGED_CLUB
    }
}
