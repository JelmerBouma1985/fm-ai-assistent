package com.github.fmaiassistent.update;

import tools.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.util.*;

public final class GitHubReleaseSource implements UpdateSource {
    public static final String REPOSITORY = "JelmerBouma1985/fm-ai-assistent";
    private final Map<ReleaseAsset, String> downloads = new HashMap<>();
    private final Map<String, byte[]> cache = new HashMap<>();
    private final Map<String, String> etags = new HashMap<>();
    @FunctionalInterface interface Reader { InputStream open(String url) throws IOException; }
    private final Reader fixture;
    public GitHubReleaseSource() { fixture = null; }
    GitHubReleaseSource(Reader fixture) { this.fixture = fixture; }
    private InputStream assetStream(String url) throws IOException { return fixture == null ? request(url) : fixture.open(url); }
    public Optional<UpdateRelease> findBestCompatibleRelease(InstalledApplicationInfo installed) throws IOException {
        downloads.clear();
        List<UpdateRelease> candidates = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JsonNode releases = new ObjectMapper().readTree(json("https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=100&page=" + page));
            if (!releases.isArray()) throw new IOException("Invalid GitHub releases response");
            for (var release : releases) {
                if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean()) continue;
                try {
                    String tag = release.path("tag_name").asString();
                    if (SemVer.parse(tag).compareTo(SemVer.parse(installed.version())) <= 0) continue;
                    JsonNode manifestAsset = null;
                    for (var asset : release.path("assets")) {
                        if (asset.path("name").asString("").equals("update-manifest.json")) {
                            if (manifestAsset != null) throw new IOException("Duplicate release manifest");
                            manifestAsset = asset;
                        }
                    }
                    if (manifestAsset == null) continue;
                    UpdateRelease manifest;
                    try (var stream = assetStream(assetUrl(manifestAsset))) { manifest = UpdateManifest.read(stream); }
                    if (!manifest.version().equals(tag.replaceFirst("^v", ""))) continue;
                    var selected = ReleaseSelector.asset(manifest, installed);
                    if (selected.isEmpty()) continue;
                    ReleaseAsset selectedAsset = selected.get();
                    int matches = 0;
                    String url = null;
                    for (var asset : release.path("assets")) {
                        if (!asset.path("name").asString("").equals(selectedAsset.file())) continue;
                        matches++;
                        if (asset.path("size").asLong() != selectedAsset.sizeBytes()) throw new IOException("GitHub asset size differs from manifest");
                        String digest = asset.path("digest").asString("");
                        if (!digest.isBlank() && !digest.equalsIgnoreCase("sha256:" + selectedAsset.sha256())) throw new IOException("GitHub digest differs from manifest");
                        url = assetUrl(asset);
                    }
                    if (matches == 1) { downloads.put(selectedAsset, url); candidates.add(manifest); }
                } catch (IllegalArgumentException | IOException ignored) {
                    // A broken or incompatible release must not hide other compatible stable releases.
                }
            }
            if (releases.size() < 100) break;
        }
        return ReleaseSelector.best(candidates, installed);
    }
    public InputStream openAsset(ReleaseAsset asset) throws IOException {
        String url = downloads.get(asset);
        if (url == null) throw new IOException("Asset was not discovered in the configured repository");
        return assetStream(url);
    }
    private String assetUrl(JsonNode asset) throws IOException {
        String url = asset.path("browser_download_url").asString("");
        if (!url.startsWith("https://github.com/" + REPOSITORY + "/releases/download/")) throw new IOException("Untrusted GitHub asset URL");
        return url;
    }
    private byte[] json(String url) throws IOException {
        if (fixture != null) {
            try (var input = fixture.open(url)) {
                byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("GitHub response too large");
                return bytes;
            }
        }
        HttpURLConnection connection = connection(url);
        if (etags.containsKey(url)) connection.setRequestProperty("If-None-Match", etags.get(url));
        try {
            if (connection.getResponseCode() == 304 && cache.containsKey(url)) return cache.get(url);
            if (connection.getResponseCode() != 200) throw new IOException("GitHub update check unavailable (HTTP " + connection.getResponseCode() + ")");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("GitHub response too large");
                String etag = connection.getHeaderField("ETag");
                if (etag != null) { cache.put(url, bytes); etags.put(url, etag); }
                return bytes;
            }
        } finally { connection.disconnect(); }
    }
    private static HttpURLConnection connection(String url) throws IOException {
        var c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "fm-ai-assistent-desktop-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }
    private static InputStream request(String initial) throws IOException {
        String url = initial;
        for (int count = 0; count < 6; count++) {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || host == null
                    || !(host.equals("github.com") || host.endsWith(".githubusercontent.com"))) throw new IOException("Untrusted asset redirect");
            var c = connection(url);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location"); c.disconnect();
                if (location == null) throw new IOException("Invalid download redirect");
                url = uri.resolve(location).toString(); continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("Update asset unavailable (HTTP " + code + ")"); }
            return new FilterInputStream(c.getInputStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { c.disconnect(); } }
            };
        }
        throw new IOException("Too many download redirects");
    }
}
