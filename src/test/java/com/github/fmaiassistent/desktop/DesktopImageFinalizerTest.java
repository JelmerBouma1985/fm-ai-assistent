package com.github.fmaiassistent.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopImageFinalizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void makesGeneratedFilesWritableForMavenClean() throws Exception {
        Path launcher = Files.writeString(temporaryDirectory.resolve("launcher.exe"), "test");
        assertThat(launcher.toFile().setReadOnly()).isTrue();

        DesktopImageFinalizer.main(new String[]{temporaryDirectory.toString()});

        assertThat(Files.isWritable(launcher)).isTrue();
        assertThat(Files.deleteIfExists(launcher)).isTrue();
    }
}
