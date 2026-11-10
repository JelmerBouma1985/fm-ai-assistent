package com.github.fmaiassistent.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopImageFinalizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void materializesJlinkLegalNoticeLinksButRejectsEscapes() throws Exception {
        Path image = Files.createDirectory(temporaryDirectory.resolve("image"));
        Path target = Files.writeString(image.resolve("notice"), "license");
        Path link = Files.createSymbolicLink(image.resolve("linked-notice"), target.getFileName());
        DesktopImageFinalizer.materializeInternalFileLinks(image);
        assertThat(Files.isSymbolicLink(link)).isFalse();
        assertThat(Files.readString(link)).isEqualTo("license");
        Path outside = Files.writeString(temporaryDirectory.resolve("outside"), "outside");
        Files.createSymbolicLink(image.resolve("escape"), outside);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> DesktopImageFinalizer.materializeInternalFileLinks(image));
    }

    @Test
    void makesGeneratedFilesWritableForMavenClean() throws Exception {
        Path launcher = Files.writeString(temporaryDirectory.resolve("launcher.exe"), "test");
        DosFileAttributeView dosAttributes = Files.getFileAttributeView(
                launcher, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dosAttributes != null) {
            dosAttributes.setReadOnly(true);
            assertThat(dosAttributes.readAttributes().isReadOnly()).isTrue();
        } else {
            assertThat(launcher.toFile().setReadOnly()).isTrue();
            assertThat(Files.isWritable(launcher)).isFalse();
        }

        DesktopImageFinalizer.main(new String[]{temporaryDirectory.toString()});

        if (dosAttributes != null) {
            assertThat(dosAttributes.readAttributes().isReadOnly()).isFalse();
        } else {
            assertThat(Files.isWritable(launcher)).isTrue();
        }
        assertThat(Files.deleteIfExists(launcher)).isTrue();
    }
}
