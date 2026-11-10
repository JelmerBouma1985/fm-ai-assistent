package com.github.fmaiassistent.update.helper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

/** Shared with the dependency-free external helper. Never follows links during copies. */
public final class ImageFiles {
    private ImageFiles() { }
    public static Path launcher(Path root, String os) {
        return root.resolve(os.equals("windows") ? "FM AI Assistent.exe" : "bin/FM AI Assistent");
    }
    public static Properties properties(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Oversized updater metadata");
            Properties p = new Properties(); p.load(new ByteArrayInputStream(bytes)); return p;
        }
    }
    public static void store(Path file, Properties p) throws IOException {
        Path part = file.resolveSibling(file.getFileName() + ".tmp");
        try (var channel = java.nio.channels.FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var out = java.nio.channels.Channels.newOutputStream(channel);
            p.store(out, "FM AI Assistent updater"); out.flush(); channel.force(true);
        }
        try { Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(part, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    public static Properties metadata(Path root) throws IOException {
        Properties p = properties(root.resolve("app-update.properties"));
        String os = p.getProperty("os", "");
        if (!p.getProperty("applicationId", "").equals("fm-ai-assistent")
                || !p.getProperty("distribution", "").equals("desktop")
                || !p.getProperty("arch", "").equals("x64")
                || !(os.equals("linux") || os.equals("windows"))
                || !p.getProperty("game", "").matches("FM[0-9]{2}")
                || !p.getProperty("version", "").matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                || !Files.isRegularFile(launcher(root, os))
                || !Files.isDirectory(root.resolve(os.equals("linux") ? "lib/runtime" : "runtime"))
                || !Files.isRegularFile(root.resolve("updater/helper.jar"))
                || !Files.isRegularFile(root.resolve("updater/runtime/bin/" + (os.equals("windows") ? "java.exe" : "java"))))
            throw new IOException("Incomplete or unsupported desktop image");
        Path config = root.resolve(os.equals("linux") ? "lib/app/FM AI Assistent.cfg" : "app/FM AI Assistent.cfg");
        try (var input = Files.newInputStream(config)) {
            byte[] content = input.readNBytes(65537);
            String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            if (content.length > 65536 || text.lines().filter(line -> line.equals("java-options=-Djpackage.app-version=" + p.getProperty("version"))).count() != 1)
                throw new IOException("jpackage embedded version differs from descriptor");
            Path app = config.getParent();
            int classpaths = 0;
            for (String line : text.lines().toList()) {
                if (!line.startsWith("app.classpath=")) continue;
                String prefix = "app.classpath=$APPDIR";
                if (!line.startsWith(prefix)) throw new IOException("Unrecognized jpackage classpath");
                String relative = line.substring(prefix.length()).replace('\\', '/');
                if (!relative.startsWith("/") || relative.contains("..") || relative.contains(":")) throw new IOException("Unsafe packaged classpath");
                Path jar = app.resolve(relative.substring(1)).normalize();
                if (!jar.startsWith(app) || !Files.isRegularFile(jar)) throw new IOException("Missing packaged application dependency");
                classpaths++;
            }
            if (classpaths == 0) throw new IOException("Missing packaged application JAR");
        }
        if (!Files.isRegularFile(root.resolve(os.equals("linux") ? "lib/runtime/lib/server/libjvm.so" : "runtime/bin/server/jvm.dll")))
            throw new IOException("Missing packaged Java runtime");
        return p;
    }
    public static Path safeInstall(Path supplied, Path home) throws IOException {
        Path root = supplied.toAbsolutePath().normalize();
        Path canonicalParent = root.getParent() == null ? null : root.getParent().toRealPath();
        if (canonicalParent == null || !canonicalParent.equals(root.getParent())) throw new IOException("Installation parent must be canonical");
        Path canonicalHome = home.toRealPath();
        Path data = canonicalHome.resolve(".fm-ai-assistent");
        if (root.getNameCount() < 2 || root.equals(canonicalHome) || canonicalHome.startsWith(root)
                || root.startsWith(data) || data.startsWith(root) || Files.isSymbolicLink(root))
            throw new IOException("Protected installation directory");
        for (String protectedDirectory : new String[]{System.getenv("WINDIR"), System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
            if (protectedDirectory != null && root.startsWith(Path.of(protectedDirectory).toAbsolutePath().normalize())) throw new IOException("System installation requires manual update");
        }
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            for (String protectedDirectory : new String[]{"/usr", "/bin", "/sbin", "/etc", "/lib", "/boot", "/var"})
                if (root.startsWith(Path.of(protectedDirectory))) throw new IOException("System installation requires manual update");
        }
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"))) throw new IOException("Cannot replace a Git checkout");
        }
        if (!Files.isWritable(canonicalParent) || (Files.exists(root) && !Files.isWritable(root))) throw new IOException("Installation requires a manual update: directory is not writable");
        return root;
    }
    public static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) throw new IOException("Links are not supported in updater copies");
                Files.createDirectories(destination.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Nonregular file in updater copy");
                Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES); return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void move(Path from, Path to) throws IOException {
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Updater move target already exists");
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(from, to); }
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(250L + attempt * 100L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Update interrupted", interrupted); }
            }
        }
        throw last;
    }
}
