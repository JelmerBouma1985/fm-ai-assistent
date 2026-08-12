package com.github.fmaiassistent.tactic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai.tactic-context")
public record TacticContextProperties(
        String ocrExecutable,
        Duration ocrTimeout,
        DataSize maxFileSize,
        int maxContextCharacters) {

    public TacticContextProperties {
        ocrExecutable = ocrExecutable == null || ocrExecutable.isBlank() ? "tesseract" : ocrExecutable;
        ocrTimeout = ocrTimeout == null || ocrTimeout.isNegative() || ocrTimeout.isZero()
                ? Duration.ofSeconds(30)
                : ocrTimeout;
        maxFileSize = maxFileSize == null || maxFileSize.toBytes() <= 0
                ? DataSize.ofMegabytes(20)
                : maxFileSize;
        maxContextCharacters = maxContextCharacters <= 0 ? 16_000 : maxContextCharacters;
    }
}
