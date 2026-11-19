package com.github.fmaiassistent.domain.enums;

public enum DesktopMemoryPreset {
    SMALL("Small", 4),
    MEDIUM("Medium",7),
    LARGE("Large", 10),
    VERY_LARGE("Very large", 13);

    private final String label;
    private final int gigabytes;

    DesktopMemoryPreset(String label, int gigabytes) {
        this.label = label;
        this.gigabytes = gigabytes;
    }

    public String label() { return label + " (" + gigabytes + " GB)"; }
    public long bytes() { return gigabytes * 1024L * 1024 * 1024; }
    public String heapOption() { return "-Xmx" + gigabytes + "g"; }

    public static DesktopMemoryPreset fromBytes(long bytes) {
        for (var preset : values()) {
            if (preset.bytes() == bytes) return preset;
        }
        return null;
    }
}
