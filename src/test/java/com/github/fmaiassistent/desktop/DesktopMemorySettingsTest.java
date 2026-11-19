package com.github.fmaiassistent.desktop;

import com.github.fmaiassistent.domain.enums.DesktopMemoryPreset;
import com.github.fmaiassistent.update.helper.LauncherMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopMemorySettingsTest {
    @TempDir Path temporary;

    private static final String CONFIG = "[Application]\r\napp.mainclass=example.Main\r\n"
            + "app.classpath=$APPDIR/app.jar\r\n[JavaOptions]\r\n"
            + "java-options=-Djpackage.app-version=2.1.0\r\njava-options=-Dvaadin.productionMode=true\r\n"
            + "java-options=-Xmx2g\r\njava-options=-XX:MaxHeapSize=3221225472\r\n"
            + "[ArgOptions]\r\narguments=--example=value with spaces\r\n";

    @ParameterizedTest
    @EnumSource(DesktopMemoryPreset.class)
    void persistsEveryPresetWithoutChangingOtherLauncherOptions(DesktopMemoryPreset preset) throws Exception {
        Path config = temporary.resolve("FM AI Assistent.cfg");
        Files.writeString(config, CONFIG);
        var settings = new DesktopMemorySettings(config);

        settings.save(preset);

        assertThat(new DesktopMemorySettings(config).preset()).isEqualTo(preset);
        String saved = Files.readString(config);
        assertThat(LauncherMemory.heapBytes(saved)).hasValue(preset.bytes());
        assertThat(saved).contains("app.classpath=$APPDIR/app.jar", "java-options=-Djpackage.app-version=2.1.0",
                "java-options=-Dvaadin.productionMode=true", "[ArgOptions]\r\narguments=--example=value with spaces");
        assertThat(saved.lines().filter(line -> line.startsWith("java-options=-Xmx"))).hasSize(1);
        assertThat(saved).doesNotContain("-XX:MaxHeapSize=");
        try (var files = Files.list(temporary)) {
            assertThat(files.toList()).containsExactly(config);
        }
    }

    @Test
    void javaLaunchHasNoEditableDesktopConfiguration() throws Exception {
        var settings = new DesktopMemorySettings(null);
        assertThat(settings.available()).isFalse();
        assertThatThrownBy(() -> settings.save(DesktopMemoryPreset.LARGE)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsMalformedAndOversizedFilesWithoutReplacingThem() throws Exception {
        Path config = temporary.resolve("FM AI Assistent.cfg");
        for (String invalid : new String[]{"[Application]\napp.mainclass=example.Main\n",
                CONFIG + "[JavaOptions]\n", "x".repeat(65_537)}) {
            Files.writeString(config, invalid);
            assertThatThrownBy(() -> new DesktopMemorySettings(config).save(DesktopMemoryPreset.LARGE))
                    .isInstanceOf(IOException.class);
            assertThat(Files.readString(config)).isEqualTo(invalid);
        }
    }

    @Test
    void recognisesEquivalentUnitsAndKeepsCustomLimitsUntilUserChoosesAPreset() throws Exception {
        assertThat(LauncherMemory.heapBytes("[JavaOptions]\njava-options=-Xmx6144m\n"))
                .hasValue(DesktopMemoryPreset.MEDIUM.bytes());
        Path config = temporary.resolve("FM AI Assistent.cfg");
        Files.writeString(config, "[JavaOptions]\njava-options=-Xmx8g\n");
        assertThat(new DesktopMemorySettings(config).preset()).isNull();
        assertThat(LauncherMemory.heapBytes(Files.readString(config))).hasValue(8L * 1024 * 1024 * 1024);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"windows", "linux"})
    void updateCarriesOnlyMemoryIntoTheNewVersion(String os) throws Exception {
        Path installed = temporary.resolve("old"), staged = temporary.resolve("new");
        Path oldConfig = LauncherMemory.config(installed, os), newConfig = LauncherMemory.config(staged, os);
        Files.createDirectories(oldConfig.getParent());
        Files.createDirectories(newConfig.getParent());
        Files.writeString(oldConfig, LauncherMemory.withHeapBytes(CONFIG, DesktopMemoryPreset.LARGE.bytes()));
        Files.writeString(newConfig, CONFIG.replace("2.1.0", "2.2.0").replace("app.jar", "new.jar"));

        LauncherMemory.preserve(installed, staged, os);

        assertThat(LauncherMemory.heapBytes(Files.readString(newConfig))).hasValue(DesktopMemoryPreset.LARGE.bytes());
        assertThat(Files.readString(newConfig)).contains("jpackage.app-version=2.2.0", "$APPDIR/new.jar")
                .doesNotContain("jpackage.app-version=2.1.0", "$APPDIR/app.jar");
    }
}
