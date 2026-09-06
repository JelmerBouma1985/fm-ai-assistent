package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.linux.FmOffsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FmBuildProfileTest {
    @Test
    void defaultBuildExplicitlyDeclaresItsSupportedCapabilities() {
        FmBuildProfile profile = FmOffsets.requireProfile(FmOffsets.DEFAULT_BUILD);

        assertThat(profile.capabilities()).containsExactlyInAnyOrder(
                FmBuildProfile.Capability.CATALOG_TABLES,
                FmBuildProfile.Capability.CURRENT_DATE,
                FmBuildProfile.Capability.MANAGED_CLUB);
        assertThat(profile.currentDateRva()).isPresent();
        assertThat(profile.currentHumanManagerRva()).isPresent();
    }

    @Test
    void unknownBuildsFailClosedBeforeAnyMemoryRead() {
        assertThatThrownBy(() -> FmOffsets.requireProfile(0x7fff_ffff))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported FM26 build");
    }
}
