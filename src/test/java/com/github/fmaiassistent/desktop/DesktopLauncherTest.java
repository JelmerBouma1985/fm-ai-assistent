package com.github.fmaiassistent.desktop;

import com.github.fmaiassistent.FmAiAssistentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopLauncherTest {
    @Test
    void configuresTheDesktopSpringApplication() {
        SpringApplication application = DesktopLauncher.createApplication();

        assertThat(application.getMainApplicationClass())
                .isEqualTo(FmAiAssistentApplication.class);
        assertThat(application.getAdditionalProfiles()).contains("desktop");
    }
}
