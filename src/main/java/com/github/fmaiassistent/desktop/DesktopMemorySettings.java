package com.github.fmaiassistent.desktop;

import com.github.fmaiassistent.domain.enums.DesktopMemoryPreset;
import com.github.fmaiassistent.update.helper.LauncherMemory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

@Service
public class DesktopMemorySettings {
    private final Path config;

    public DesktopMemorySettings() {
        this(resolveConfig());
    }

    DesktopMemorySettings(Path config) {
        this.config = config;
    }

    public boolean available() { return config != null; }

    public DesktopMemoryPreset preset() throws IOException {
        return config == null ? DesktopMemoryPreset.MEDIUM
                : DesktopMemoryPreset.fromBytes(LauncherMemory.heapBytes(LauncherMemory.read(config))
                        .orElse(DesktopMemoryPreset.MEDIUM.bytes()));
    }

    public void save(DesktopMemoryPreset preset) throws IOException {
        if (config == null) throw new IOException("Memory settings require the packaged desktop app");
        LauncherMemory.write(config, LauncherMemory.withHeapBytes(LauncherMemory.read(config), preset.bytes()));
    }

    private static Path resolveConfig() {
        String override = System.getProperty("fmaiassistent.desktop.launcher-config");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        if (!Boolean.getBoolean("fmai.desktop.image")) return null;
        Path executable = ProcessHandle.current().info().command().map(Path::of)
                .orElseThrow(() -> new IllegalStateException("Desktop launcher path unavailable"));
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path root = windows ? executable.getParent() : executable.getParent().getParent();
        return LauncherMemory.config(root, windows ? "windows" : "linux");
    }
}
