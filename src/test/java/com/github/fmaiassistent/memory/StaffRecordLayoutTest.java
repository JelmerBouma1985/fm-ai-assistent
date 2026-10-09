package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.staff.StaffAttributeDefinitions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaffRecordLayoutTest {
    @Test
    void currentMatchesStaffAttributeDefinitions() {
        assertThat(StaffRecordLayout.current().attributesRel())
                .isEqualTo(StaffAttributeDefinitions.ATTRIBUTES_REL);
    }
}
