package com.github.fmaiassistent.tactic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.ai.tactic-context")
public record TacticContextProperties(
        DataSize maxFileSize,
        int maxContextCharacters) {

    public TacticContextProperties {
        maxFileSize = maxFileSize == null || maxFileSize.toBytes() <= 0
                ? DataSize.ofMegabytes(20)
                : maxFileSize;
        maxContextCharacters = maxContextCharacters <= 0 ? 16_000 : maxContextCharacters;
    }
}
