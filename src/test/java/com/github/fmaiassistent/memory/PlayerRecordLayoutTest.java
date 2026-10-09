package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.player.AttributeDefinitions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerRecordLayoutTest {
    @Test
    void currentMatchesAttributeDefinitions() {
        PlayerRecordLayout.Direct direct = PlayerRecordLayout.current().direct();

        assertThat(direct.historyCopySourceRel()).isEqualTo(AttributeDefinitions.HISTORY_COPY_SOURCE_REL);
        assertThat(direct.sourceObjectBaseOffset()).isEqualTo(AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET);
        assertThat(direct.homeReputationRel()).isEqualTo(AttributeDefinitions.HOME_REPUTATION_REL);
        assertThat(direct.currentReputationRel()).isEqualTo(AttributeDefinitions.CURRENT_REPUTATION_REL);
        assertThat(direct.worldReputationRel()).isEqualTo(AttributeDefinitions.WORLD_REPUTATION_REL);
        assertThat(direct.currentAbilityRel()).isEqualTo(AttributeDefinitions.CURRENT_ABILITY_REL);
        assertThat(direct.potentialAbilityRel()).isEqualTo(AttributeDefinitions.POTENTIAL_ABILITY_REL);
        assertThat(direct.displayValueRel()).isEqualTo(AttributeDefinitions.DISPLAY_VALUE_REL);
    }
}
