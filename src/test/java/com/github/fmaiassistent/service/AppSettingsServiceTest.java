package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.enums.MoneyCurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppSettingsServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndAtomicallyUpdatesSettings() throws Exception {
        Path settings = temporaryDirectory.resolve("settings.properties");
        AppSettingsService service = new AppSettingsService(settings);

        assertThat(service.currency()).isEqualTo(MoneyCurrency.POUND);
        service.saveCurrency(MoneyCurrency.EURO);

        assertThat(new AppSettingsService(settings).currency()).isEqualTo(MoneyCurrency.EURO);
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .containsExactly("settings.properties");
        }
    }
}
