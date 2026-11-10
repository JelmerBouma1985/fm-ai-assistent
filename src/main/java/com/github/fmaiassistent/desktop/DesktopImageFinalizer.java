package com.github.fmaiassistent.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;

/** Build-time helper that makes generated jpackage output removable by the next Maven clean. */
public final class DesktopImageFinalizer {
    private DesktopImageFinalizer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 && args.length != 2) {
            throw new IllegalArgumentException("Expected the jpackage output directory");
        }
        Path output = Path.of(args[0]);
        if (!Files.exists(output)) {
            throw new IllegalStateException("jpackage output does not exist: " + output);
        }
        try (var paths = Files.walk(output)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(DesktopImageFinalizer::makeWritable);
        }
        if (args.length == 2) {
            Path image = output.resolve("FM AI Assistent");
            packageUpdater(image, args[1]);
            materializeInternalFileLinks(image);
        }
    }

    /** jlink deduplicates Linux legal notices with links; release archives intentionally contain none. */
    static void materializeInternalFileLinks(Path image) throws IOException {
        Path root = image.toRealPath();
        try (var paths = Files.walk(root)) {
            for (Path link : paths.filter(Files::isSymbolicLink).toList()) {
                Path target = link.toRealPath();
                if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsupported generated image link");
                Path copy = link.resolveSibling(link.getFileName() + ".regular-file");
                Files.copy(target, copy, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                Files.move(copy, link, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void packageUpdater(Path image, String version) throws IOException {
        com.github.fmaiassistent.update.SemVer.parse(version);
        String os = com.github.fmaiassistent.update.InstalledApplicationInfo.currentOs();
        if (!java.util.Set.of("windows", "linux").contains(os) || !com.github.fmaiassistent.update.InstalledApplicationInfo.currentArch().equals("x64"))
            throw new IOException("Updater-enabled desktop packaging requires Windows/Linux x64");
        Path helper = Files.createDirectories(image.resolve("updater"));
        Path classes;
        try { classes = Path.of(DesktopImageFinalizer.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (java.net.URISyntaxException e) { throw new IOException(e); }
        Path packageRoot = classes.resolve("com/github/fmaiassistent/update/helper");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(helper.resolve("helper.jar"))); var files = Files.walk(packageRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar); jar.closeEntry();
            }
        }
        String suffix = os.equals("windows") ? ".exe" : "";
        try {
            Process linker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jlink" + suffix).toString(),
                    "--add-modules", "java.base", "--strip-debug", "--no-header-files", "--no-man-pages",
                    "--output", helper.resolve("runtime").toString()).inheritIO().start();
            if (linker.waitFor() != 0) throw new IOException("Updater runtime packaging failed");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        var metadata = new java.util.Properties();
        metadata.setProperty("applicationId", "fm-ai-assistent"); metadata.setProperty("distribution", "desktop");
        metadata.setProperty("version", version); metadata.setProperty("os", os); metadata.setProperty("arch", "x64"); metadata.setProperty("game", "FM27");
        com.github.fmaiassistent.update.helper.ImageFiles.store(image.resolve("app-update.properties"), metadata);
    }

    private static void makeWritable(Path path) {
        try {
            DosFileAttributeView dosAttributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (dosAttributes != null) {
                dosAttributes.setReadOnly(false);
            } else if (!Files.isWritable(path) && !path.toFile().setWritable(true)) {
                throw new IllegalStateException("Could not make generated artifact writable: " + path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not make generated artifact writable: " + path, exception);
        }
    }
}
