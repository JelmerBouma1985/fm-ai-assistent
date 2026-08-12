package com.github.fmaiassistent.copilot;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotPropertiesTest {
    @Test
    void suppliesSafeLocalDefaults() {
        CopilotProperties properties = new CopilotProperties(true, null, null, null, null, null, null, null);

        assertThat(properties.executable()).isEqualTo("copilot");
        assertThat(properties.workingDirectory()).isEqualTo(".");
        assertThat(properties.startupTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.permissionTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.model()).isNull();
    }
}
