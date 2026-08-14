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
        if (args.length != 1) {
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
