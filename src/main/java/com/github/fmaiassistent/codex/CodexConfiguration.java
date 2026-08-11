package com.github.fmaiassistent.codex;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CodexProperties.class)
class CodexConfiguration {
}
