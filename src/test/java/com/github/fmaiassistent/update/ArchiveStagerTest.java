package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.ImageFiles;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveStagerTest {
    @TempDir Path temp;
    UpdateRelease release() { return new UpdateRelease("2.1.1", Set.of("FM27"), "notes", List.of()); }
    ReleaseAsset asset(String os) { return new ReleaseAsset("desktop", os, "x64", "fixture", "0".repeat(64), 1); }
    void entry(ZipArchiveOutputStream out, String name, byte[] data, int mode) throws IOException {
        var e = new ZipArchiveEntry(name); e.setUnixMode(mode); out.putArchiveEntry(e); out.write(data); out.closeArchiveEntry();
    }
    @Test void rejectsTraversalAlternateStreamsLinksAndDuplicateEntries() throws Exception {
        int index = 0;
        for (String name : List.of("../escaped", "/absolute", "C:/absolute", "image/file:stream", "image/../../escape", "image\\file", "image/CON", "image/trailing.", "image/link")) {
            Path zip = temp.resolve("bad" + index + ".zip");
            try (var out = new ZipArchiveOutputStream(zip)) { entry(out, name, new byte[]{1}, name.endsWith("link") ? 0120777 : 0100644); }
            Path destination = temp.resolve("extract" + index++);
            assertThrows(IOException.class, () -> new ArchiveStager().extract(zip, destination, release(), asset("windows")));
        }
        Path zip = temp.resolve("duplicate.zip");
        try (var out = new ZipArchiveOutputStream(zip)) {
            entry(out, "image/file", new byte[]{1}, 0100644); entry(out, "image/FILE", new byte[]{2}, 0100644);
        }
        assertThrows(IOException.class, () -> new ArchiveStager().extract(zip, temp.resolve("duplicate"), release(), asset("windows")));
    }
    @Test void validatesEmbeddedVersionAndCompleteImage() throws Exception {
        Path image = temp.resolve("image"); UpdaterTransactionTest.image(image, "2.1.1", "windows");
        Path zip = temp.resolve("image.zip"); zip(image, zip);
        Path extracted = new ArchiveStager().extract(zip, temp.resolve("good"), release(), asset("windows"));
        assertEquals("2.1.1", ImageFiles.metadata(extracted).getProperty("version"));
        var wrong = new UpdateRelease("2.1.2", Set.of("FM27"), "", List.of());
        assertThrows(IOException.class, () -> new ArchiveStager().extract(zip, temp.resolve("wrong"), wrong, asset("windows")));
        Files.delete(image.resolve("FM AI Assistent.exe"));
        Path missing = temp.resolve("missing.zip"); zip(image, missing);
        assertThrows(IOException.class, () -> new ArchiveStager().extract(missing, temp.resolve("missing"), release(), asset("windows")));
    }
    private void zip(Path image, Path zip) throws IOException {
        try (var out = new ZipArchiveOutputStream(zip); var paths = Files.walk(image)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                entry(out, "FM AI Assistent/" + image.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file), 0100644);
            }
        }
    }
    @Test void tarRejectsLinksAndOversizedEntriesBeforeAllocating() throws Exception {
        for (boolean link : List.of(true, false)) {
            Path archive = temp.resolve("bad" + link + ".tar.gz");
            try (var out = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)))) {
                var e = new TarArchiveEntry("image/file", link ? TarConstants.LF_SYMLINK : TarConstants.LF_NORMAL);
                if (link) { e.setLinkName("../../escape"); out.putArchiveEntry(e); out.closeArchiveEntry(); }
                else { e.setSize(7L * 1024 * 1024 * 1024); out.putArchiveEntry(e); } // deliberately truncated bomb header
            } catch (IOException expectedForTruncatedArchive) { }
            assertThrows(IOException.class, () -> new ArchiveStager().extract(archive, temp.resolve("tar" + link), release(), asset("linux")));
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void linuxTarPreservesExecutablePermissions() throws Exception {
        Path image = temp.resolve("linux"); UpdaterTransactionTest.image(image, "2.1.1", "linux");
        Files.setPosixFilePermissions(ImageFiles.launcher(image, "linux"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Path archive = temp.resolve("linux.tar.gz");
        try (var out = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(archive))); var paths = Files.walk(image)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                var e = new TarArchiveEntry(path, "FM AI Assistent/" + image.relativize(path));
                if (path.equals(ImageFiles.launcher(image, "linux"))) e.setMode(0755);
                out.putArchiveEntry(e); Files.copy(path, out); out.closeArchiveEntry();
            }
        }
        Path extracted = new ArchiveStager().extract(archive, temp.resolve("linux-extracted"), release(), asset("linux"));
        assertEquals("rwxr-xr-x", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(ImageFiles.launcher(extracted, "linux"))));
    }
}
