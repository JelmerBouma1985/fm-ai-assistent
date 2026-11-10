package com.github.fmaiassistent.update;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deliberately has no HTTP client or fallback. */
public final class LocalDirectoryUpdateSource implements UpdateSource {
    private final Path root;
    public LocalDirectoryUpdateSource(Path directory) throws IOException {
        root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Local update directory does not exist");
    }
    public Optional<UpdateRelease> findBestCompatibleRelease(InstalledApplicationInfo installed) throws IOException {
        try (var stream = open("update-manifest.json")) {
            var release = UpdateManifest.read(stream);
            return ReleaseSelector.asset(release, installed).isPresent() ? Optional.of(release) : Optional.empty();
        }
    }
    public InputStream openAsset(ReleaseAsset asset) throws IOException { return open(asset.file()); }
    private InputStream open(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9_.-]+") || name.contains("..")) throw new IOException("Unsafe local asset filename");
        Path file = root.resolve(name).toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Local asset must be a regular file within release directory");
        return Files.newInputStream(file);
    }
}
