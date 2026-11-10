package com.github.fmaiassistent.update;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class UpdateTest {
    @TempDir Path temp;
    static String manifest(String version, String game) {
        return """
                {"schemaVersion":1,"applicationId":"fm-ai-assistent","version":"%s","channel":"stable",
                "supportedGames":["%s"],"releaseNotes":"<script>unsafe</script>","assets":[{
                "distribution":"desktop","os":"windows","arch":"x64","file":"fm-ai-assistent-desktop-windows-x64.zip",
                "sha256":"%s","sizeBytes":3}]}
                """.formatted(version, game, "a".repeat(64));
    }
    static UpdateRelease parse(String value) throws IOException { return UpdateManifest.read(new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    InstalledApplicationInfo installed(String game) { return new InstalledApplicationInfo("2.1.0", "windows", "x64", game, temp); }
    @Test void strictVersionsAndOrdering() {
        assertTrue(SemVer.parse("v2.1.0").compareTo(SemVer.parse("2.1.1")) < 0);
        assertEquals(0, SemVer.parse("2.1.0+build.2").compareTo(SemVer.parse("2.1.0")));
        for (var version : List.of("2.1.1-beta", "02.1.0", "2.1", "2.1.0\n", "", "2.1.0-rc+test")) assertThrows(IllegalArgumentException.class, () -> SemVer.parse(version));
        assertDoesNotThrow(() -> SemVer.parse("999999999999999999999.1.0"));
    }
    @Test void selectsHighestCompatibleReleaseNotNewestGlobalRelease() throws Exception {
        var releases = List.of(parse(manifest("9.0.0", "FM26")), parse(manifest("2.1.1", "FM27")), parse(manifest("2.2.0", "FM27")));
        assertEquals("2.2.0", ReleaseSelector.best(releases, installed("FM27")).orElseThrow().version());
        assertEquals("9.0.0", ReleaseSelector.best(releases, installed("FM26")).orElseThrow().version());
        assertTrue(ReleaseSelector.best(List.of(parse(manifest("2.1.0", "FM27")), parse(manifest("2.0.0", "FM27"))), installed("FM27")).isEmpty());
        assertTrue(ReleaseSelector.asset(releases.get(1), new InstalledApplicationInfo("2.1.0", "linux", "x64", "FM27", temp)).isEmpty());
        assertTrue(ReleaseSelector.asset(releases.get(1), new InstalledApplicationInfo("2.1.0", "windows", "x64", "FM27", null)).isEmpty());
        assertTrue(ReleaseSelector.asset(releases.get(1), installed("FM26")).isEmpty());
    }
    @Test void validatesManifestStrictlyButAllowsOptionalFields() throws Exception {
        String valid = manifest("2.1.1", "FM27");
        assertEquals("<script>unsafe</script>", parse(valid.replace("\"schemaVersion\":1", "\"optional\":true,\"schemaVersion\":1")).releaseNotes());
        for (String invalid : List.of(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), valid.replace("\"sizeBytes\":3", "\"sizeBytes\":0"),
                valid.replace("\"sizeBytes\":3", "\"sizeBytes\":3.5"), valid.replace("\"sizeBytes\":3", "\"sizeBytes\":99999999999999999999999"),
                valid.replace("2.1.1", "2.1.1-rc"), valid.replace("\"stable\"", "\"beta\""), valid.replace("\"FM27\"", "null"),
                valid.replace("\"desktop\"", "\"native\""), valid.replace("\"x64\"", "\"arm64\""), valid.replace("\"windows\"", "\"macos\""),
                valid.replace("fm-ai-assistent-desktop-windows-x64.zip", "../escape.zip"), valid.replace("a".repeat(64), "invalid"),
                valid.replace("\"applicationId\":\"fm-ai-assistent\"", "\"applicationId\":\"other\""), "{}", "null", "[]", "not JSON")) {
            assertThrows(IOException.class, () -> parse(invalid), invalid);
        }
    }
    @Test void rejectsDuplicateAssetsAndOversizedJson() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var json = mapper.readTree(manifest("2.1.1", "FM27"));
        var assets = (tools.jackson.databind.node.ArrayNode) json.path("assets");
        assets.add(assets.get(0).deepCopy());
        assertThrows(IOException.class, () -> parse(mapper.writeValueAsString(json)));
        assertThrows(IOException.class, () -> parse(" ".repeat(UpdateManifest.MAX_JSON + 1)));
    }
    @Test void localSourceIsOfflineAndDoesNotFallback() throws Exception {
        Files.writeString(temp.resolve("update-manifest.json"), manifest("2.1.1", "FM27"));
        var source = new LocalDirectoryUpdateSource(temp);
        var release = source.findBestCompatibleRelease(installed("FM27")).orElseThrow();
        assertThrows(IOException.class, () -> source.openAsset(release.assets().getFirst()));
        Files.write(temp.resolve(release.assets().getFirst().file()), new byte[]{1,2,3});
        try (var input = source.openAsset(release.assets().getFirst())) { assertArrayEquals(new byte[]{1,2,3}, input.readNBytes(4)); }
        assertThrows(IOException.class, () -> new LocalDirectoryUpdateSource(temp.resolve("missing")));
        Files.writeString(temp.resolve("update-manifest.json"), "broken");
        assertThrows(IOException.class, () -> source.findBestCompatibleRelease(installed("FM27")));
        assertThrows(IOException.class, () -> source.openAsset(new ReleaseAsset("desktop", "windows", "x64", "../escape", "", 1)));
    }
    @Test void downloadChecksLengthHashAndCancellation() throws Exception {
        byte[] bytes = {1, 2, 3};
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var asset = new ReleaseAsset("desktop", "windows", "x64", "asset.zip", hash, 3);
        UpdateSource source = new UpdateSource() {
            public Optional<UpdateRelease> findBestCompatibleRelease(InstalledApplicationInfo i) { return Optional.empty(); }
            public InputStream openAsset(ReleaseAsset a) { return new ByteArrayInputStream(bytes); }
        };
        var service = new UpdateDownloadService();
        var progress = new java.util.concurrent.atomic.AtomicLong();
        Path downloaded = service.download(source, asset, temp, () -> false, progress::set);
        assertArrayEquals(bytes, Files.readAllBytes(downloaded)); assertEquals(3, progress.get());
        Files.delete(downloaded);
        for (var bad : List.of(new ReleaseAsset("desktop", "windows", "x64", "asset.zip", "0".repeat(64), 3),
                new ReleaseAsset("desktop", "windows", "x64", "asset.zip", hash, 2), new ReleaseAsset("desktop", "windows", "x64", "asset.zip", hash, 4))) {
            assertThrows(IOException.class, () -> service.download(source, bad, temp, () -> false, count -> { }));
            assertFalse(Files.exists(temp.resolve("asset.zip.part")));
        }
        assertThrows(IOException.class, () -> service.download(source, asset, temp, () -> true, count -> { }));
        assertFalse(Files.exists(temp.resolve("asset.zip")));
    }
    @Test void jarAndNativeAreNotDesktop() throws Exception {
        String original = System.getProperty("fmai.desktop.image");
        try {
            System.clearProperty("fmai.desktop.image");
            assertThrows(IOException.class, InstalledApplicationInfo::detect);
        } finally { if (original != null) System.setProperty("fmai.desktop.image", original); }
    }
    @Test void detectsOnlySupportedOsAndX64() {
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", "Windows 11"); assertEquals("windows", InstalledApplicationInfo.currentOs());
            System.setProperty("os.name", "Linux"); assertEquals("linux", InstalledApplicationInfo.currentOs());
            System.setProperty("os.name", "Mac OS X"); assertEquals("unsupported", InstalledApplicationInfo.currentOs());
            System.setProperty("os.arch", "aarch64"); assertEquals("unsupported", InstalledApplicationInfo.currentArch());
            System.setProperty("os.arch", "amd64"); assertEquals("x64", InstalledApplicationInfo.currentArch());
        } finally { System.setProperty("os.name", os); System.setProperty("os.arch", arch); }
    }
    @Test void configurationFailureIsSoftAndDisabledChecksDoNotRun() throws Exception {
        var options = new UpdateProperties(); options.setEnabled(false);
        try (var close = new CoordinatorClose(new UpdateCoordinator(options, new MockEnvironment()))) {
            close.value.check(true); assertEquals(UpdateStatus.Phase.UNSUPPORTED, close.value.status().phase());
        }
        options.setEnabled(true); options.setSource("local");
        try (var close = new CoordinatorClose(new UpdateCoordinator(options, new MockEnvironment()))) {
            close.value.check(true);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
            while (close.value.status().busy() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(UpdateStatus.Phase.ERROR, close.value.status().phase());
            assertTrue(close.value.status().message().contains("developer-mode"));
            close.value.install(); assertEquals(UpdateStatus.Phase.ERROR, close.value.status().phase());
        }
    }
    record CoordinatorClose(UpdateCoordinator value) implements AutoCloseable { public void close() { value.close(); } }
}
