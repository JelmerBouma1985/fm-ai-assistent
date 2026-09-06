package com.github.fmaiassistent.snapshot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RefreshProperties.class, SnapshotProperties.class})
class RefreshConfiguration {
}
