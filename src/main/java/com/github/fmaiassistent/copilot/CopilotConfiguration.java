package com.github.fmaiassistent.copilot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CopilotProperties.class)
class CopilotConfiguration {
}
