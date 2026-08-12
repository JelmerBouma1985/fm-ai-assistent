package com.github.fmaiassistent.copilot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotExecutableResolverTest {
    @Test
    void findsCopilotInstalledByNvmWhenConfiguredPathIsBare() {
        CopilotProperties properties = new CopilotProperties(
                true, "copilot", ".", null, null, null, null, null);

        assertThat(new CopilotExecutableResolver(properties).resolve())
                .isNotNull()
                .endsWith("/bin/copilot");
    }

    @Test
    void missingExplicitExecutableReturnsNull() {
        CopilotProperties properties = new CopilotProperties(
                true, "/definitely/missing/copilot", ".", null, null, null, null, null);

        assertThat(new CopilotExecutableResolver(properties).resolve()).isNull();
    }
}
