package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.ImageFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

public record InstalledApplicationInfo(String version, String os, String arch, String game, Path root) {
    public static String currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "windows" : os.equals("linux") ? "linux" : "unsupported";
    }
    public static String currentArch() {
        return switch (System.getProperty("os.arch", "")) { case "amd64", "x86_64" -> "x64"; default -> "unsupported"; };
    }
    public static InstalledApplicationInfo detect() throws IOException {
        if (!Boolean.getBoolean("fmai.desktop.image")) throw new IOException("Automatic updates require a packaged desktop app-image");
        String command = ProcessHandle.current().info().command().orElseThrow(() -> new IOException("Launcher path unavailable"));
        Path executable = Path.of(command).toRealPath();
        String os = currentOs();
        Path root = os.equals("linux") ? executable.getParent().getParent() : executable.getParent();
        ImageFiles.safeInstall(root, Path.of(System.getProperty("user.home")));
        var metadata = ImageFiles.metadata(root);
        if (!ImageFiles.launcher(root, os).toRealPath().equals(executable)
                || !metadata.getProperty("os").equals(os) || !metadata.getProperty("arch").equals(currentArch()))
            throw new IOException("Unrecognized desktop launcher or platform");
        String version = metadata.getProperty("version");
        if (!version.equals(System.getProperty("jpackage.app-version"))) throw new IOException("Running packaged version differs from descriptor");
        SemVer.parse(version);
        return new InstalledApplicationInfo(version, os, currentArch(), metadata.getProperty("game"), root);
    }
}
