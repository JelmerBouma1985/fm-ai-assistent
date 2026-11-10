package com.github.fmaiassistent.update;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubReleaseSourceTest {
    @Test void scansReleasesAndUsesOnlyExactRepositoryAssets() throws Exception {
        String base = "https://github.com/" + GitHubReleaseSource.REPOSITORY + "/releases/download/";
        String releases = "[" + release("9.0.0", base) + "," + release("2.1.1", base) + "," + release("2.1.2", base) + "]";
        List<String> requests = new ArrayList<>();
        var source = new GitHubReleaseSource(url -> {
            requests.add(url);
            if (url.startsWith("https://api.github.com/")) return stream(releases);
            if (url.endsWith("update-manifest.json")) {
                String version = url.substring(base.length()).split("/")[0];
                return stream(UpdateTest.manifest(version, version.equals("9.0.0") ? "FM26" : "FM27"));
            }
            return new ByteArrayInputStream(new byte[]{1,2,3});
        });
        var installed = new InstalledApplicationInfo("2.1.0", "windows", "x64", "FM27", Path.of("fixture"));
        var selected = source.findBestCompatibleRelease(installed).orElseThrow();
        assertEquals("2.1.2", selected.version());
        try (var input = source.openAsset(selected.assets().getFirst())) { assertEquals(3, input.readNBytes(4).length); }
        assertTrue(requests.getLast().startsWith(base + "2.1.2/"));
        assertThrows(IOException.class, () -> source.openAsset(new ReleaseAsset("desktop", "linux", "x64", "arbitrary", "", 1)));
    }
    @Test void rejectsUntrustedUrlsAndIndependentDigestMismatch() throws Exception {
        String base = "https://github.com/" + GitHubReleaseSource.REPOSITORY + "/releases/download/";
        var installed = new InstalledApplicationInfo("2.1.0", "windows", "x64", "FM27", Path.of("fixture"));
        for (var json : List.of("[" + release("2.1.1", "https://evil.invalid/") + "]",
                "[" + release("2.1.1", base).replace("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)) + "]")) {
            var source = new GitHubReleaseSource(url -> url.startsWith("https://api.github.com/") ? stream(json) : stream(UpdateTest.manifest("2.1.1", "FM27")));
            assertTrue(source.findBestCompatibleRelease(installed).isEmpty());
        }
    }
    private static InputStream stream(String s) { return new ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String release(String version, String base) {
        return """
                {"draft":false,"prerelease":false,"tag_name":"v%s","assets":[
                {"name":"update-manifest.json","browser_download_url":"%s%s/update-manifest.json"},
                {"name":"fm-ai-assistent-desktop-windows-x64.zip","size":3,"digest":"sha256:%s",
                "browser_download_url":"%s%s/fm-ai-assistent-desktop-windows-x64.zip"}]}
                """.formatted(version, base, version, "a".repeat(64), base, version);
    }
}
