package com.github.fmaiassistent.tactic;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TacticContextProperties.class)
class TacticContextConfiguration {
}
