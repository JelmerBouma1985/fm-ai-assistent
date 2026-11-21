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

    @Test void malformedBackingFileKeepsAppUsableAndCanBeRecoveredWithoutLosingOriginal() throws Exception {
        Path settings = temporaryDirectory.resolve("bad.properties");
        byte[] original = "currency=\\uZZZZ\ntools.policy={broken".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(settings, original);
        AppSettingsService service = new AppSettingsService(settings);
        assertThat(service.currency()).isEqualTo(MoneyCurrency.POUND);
        assertThat(service.toolPolicy()).contains("unreadable");
        assertThat(Files.readAllBytes(settings)).isEqualTo(original);
        service.saveToolPolicy("{\"version\":1,\"access\":{}}", true);
        assertThat(new AppSettingsService(settings).toolPolicy()).contains("\"version\":1");
        try (var files = Files.list(temporaryDirectory)) {
            var backups = files.filter(path -> path.getFileName().toString().contains(".recovery-")).toList();
            assertThat(backups).hasSize(1);
            assertThat(Files.readAllBytes(backups.getFirst())).isEqualTo(original);
        }
    }

    @Test void oversizedBackingFileIsRecoverableAndCurrencyWritesKeepItsRecoveryCopy() throws Exception {
        Path settings = temporaryDirectory.resolve("large.properties");
        Files.writeString(settings, "x".repeat(512 * 1024 + 1));
        AppSettingsService service = new AppSettingsService(settings);
        assertThat(service.currency()).isEqualTo(MoneyCurrency.POUND);
        service.saveCurrency(MoneyCurrency.EURO);
        assertThat(new AppSettingsService(settings).currency()).isEqualTo(MoneyCurrency.EURO);
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.filter(path -> path.getFileName().toString().contains(".recovery-")).count()).isEqualTo(1);
        }
    }
}
