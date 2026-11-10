package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.ImageFiles;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.zip.GZIPInputStream;

public final class ArchiveStager {
    private static final long MAX_EXPANDED = 6L * 1024 * 1024 * 1024;
    private final Set<String> entries = new HashSet<>();
    private long expanded;
    public Path extract(Path archive, Path destination, UpdateRelease release, ReleaseAsset asset) throws IOException {
        Files.createDirectory(destination);
        if (asset.os().equals("windows")) {
            try (var zip = ZipFile.builder().setPath(archive).get()) {
                var enumeration = zip.getEntries();
                while (enumeration.hasMoreElements()) {
                    var e = enumeration.nextElement();
                    int type = e.getUnixMode() & 0170000;
                    if (e.isUnixSymlink() || (type != 0 && type != 0100000 && type != 0040000) || !zip.canReadEntryData(e)) throw new IOException("Unsupported ZIP entry");
                    try (var input = zip.getInputStream(e)) { extractEntry(destination, e.getName(), e.isDirectory(), e.getSize(), 0, input); }
                }
            }
        } else {
            try (var tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
                for (TarArchiveEntry e; (e = tar.getNextEntry()) != null;) {
                    if (!(e.isDirectory() || e.isFile()) || e.isSymbolicLink() || e.isLink() || e.isSparse()) throw new IOException("Unsupported TAR entry");
                    extractEntry(destination, e.getName(), e.isDirectory(), e.getSize(), e.getMode(), tar);
                }
            }
        }
        List<Path> roots;
        try (var paths = Files.list(destination)) { roots = paths.toList(); }
        if (roots.size() != 1 || !Files.isDirectory(roots.getFirst())) throw new IOException("Archive must contain one app-image root");
        Path root = roots.getFirst();
        var metadata = ImageFiles.metadata(root);
        if (!metadata.getProperty("version").equals(release.version()) || !metadata.getProperty("os").equals(asset.os())
                || !metadata.getProperty("arch").equals(asset.arch()) || !release.supportedGames().contains(metadata.getProperty("game")))
            throw new IOException("Packaged version or platform does not match manifest");
        if (asset.os().equals("linux") && !Files.isExecutable(ImageFiles.launcher(root, "linux"))) throw new IOException("Linux launcher is not executable");
        return root;
    }
    private void extractEntry(Path root, String original, boolean directory, long size, int mode, InputStream input) throws IOException {
        String name = original;
        while (name.startsWith("./")) name = name.substring(2);
        if (directory && name.isEmpty()) return; // tar -C dist .
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) throw new IOException("Unsafe archive path");
        for (String component : name.split("/", -1)) {
            if (component.isBlank() || component.equals(".") || component.equals("..") || component.endsWith(".") || component.endsWith(" ")
                    || component.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw new IOException("Unsafe archive component");
        }
        if (entries.size() >= 100000 || !entries.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate or excessive archive entries");
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || size < 0 || size > MAX_EXPANDED - expanded) throw new IOException("Archive exceeds extraction limits");
        if (directory) { Files.createDirectories(target); return; }
        expanded += size;
        Files.createDirectories(target.getParent());
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536]; long written = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                written += read;
                if (written > size) throw new IOException("Archive entry exceeds declared size");
                output.write(buffer, 0, read);
            }
            if (written != size) throw new IOException("Truncated archive entry");
        }
        if (mode != 0 && Files.getFileStore(target).supportsFileAttributeView("posix")) {
            var permissions = EnumSet.noneOf(PosixFilePermission.class);
            var bits = PosixFilePermission.values();
            for (int i = 0; i < 9; i++) if ((mode & (1 << (8 - i))) != 0) permissions.add(bits[i]);
            Files.setPosixFilePermissions(target, permissions);
        }
    }
}
