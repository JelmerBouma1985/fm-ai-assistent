package com.github.fmaiassistent.update;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;

public final class UpdateManifest {
    public static final int MAX_JSON = 262144;
    public static final long MAX_ARCHIVE = 2L * 1024 * 1024 * 1024;
    private UpdateManifest() { }
    public static UpdateRelease read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_JSON + 1);
        if (bytes.length > MAX_JSON) throw new IOException("Update manifest exceeds size limit");
        try {
            JsonNode root = tools.jackson.databind.json.JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build().readTree(bytes);
            require(root != null && root.isObject(), "Expected manifest object");
            require(root.path("schemaVersion").isIntegralNumber() && root.path("schemaVersion").canConvertToInt() && root.path("schemaVersion").asInt() == 1, "Unsupported manifest schema");
            require(text(root, "applicationId").equals("fm-ai-assistent"), "Wrong application");
            require(text(root, "channel").equals("stable"), "Unsupported release channel");
            String version = text(root, "version");
            SemVer.parse(version);
            Set<String> games = new HashSet<>();
            var gameNodes = root.path("supportedGames");
            require(gameNodes.isArray() && !gameNodes.isEmpty() && gameNodes.size() <= 16, "Missing supported games");
            for (var game : gameNodes) {
                require(game.isString() && game.asString().matches("FM[0-9]{2}"), "Invalid supported game");
                games.add(game.asString());
            }
            String notes = text(root, "releaseNotes");
            require(notes.length() <= 16000, "Release notes too long");
            var nodes = root.path("assets");
            require(nodes.isArray() && !nodes.isEmpty() && nodes.size() <= 16, "Invalid assets list");
            List<ReleaseAsset> assets = new ArrayList<>();
            Set<String> names = new HashSet<>(), platforms = new HashSet<>();
            for (var node : nodes) {
                String distribution = text(node, "distribution"), os = text(node, "os"), arch = text(node, "arch");
                require(distribution.equals("desktop") && Set.of("windows", "linux").contains(os) && arch.equals("x64"), "Unsupported asset platform");
                String file = text(node, "file"), hash = text(node, "sha256");
                String expected = "fm-ai-assistent-desktop-" + os + "-x64" + (os.equals("windows") ? ".zip" : ".tar.gz");
                require(file.equals(expected), "Unexpected asset filename");
                require(names.add(file) && platforms.add(distribution + os + arch), "Duplicate asset");
                require(hash.matches("[0-9a-fA-F]{64}"), "Invalid SHA-256");
                var size = node.path("sizeBytes");
                require(size.isIntegralNumber() && size.canConvertToLong() && size.asLong() > 0 && size.asLong() <= MAX_ARCHIVE, "Invalid asset size");
                assets.add(new ReleaseAsset(distribution, os, arch, file, hash.toLowerCase(Locale.ROOT), size.asLong()));
            }
            return new UpdateRelease(version, Set.copyOf(games), notes, List.copyOf(assets));
        } catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        catch (tools.jackson.core.JacksonException e) { throw new IOException("Invalid update manifest JSON", e); }
    }
    private static String text(JsonNode node, String field) {
        require(node.path(field).isString(), "Missing or invalid " + field);
        return node.path(field).asString();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
