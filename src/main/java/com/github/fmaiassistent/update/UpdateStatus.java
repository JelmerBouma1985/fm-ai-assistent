package com.github.fmaiassistent.update;

public record UpdateStatus(Phase phase, String message, String currentVersion, UpdateRelease release, long bytesDone, long totalBytes) {
    public enum Phase { CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, PREPARING, READY_TO_RESTART, INSTALLING, RESTARTING, COMPLETED, ERROR, NO_UPDATE, UNSUPPORTED }
    public boolean busy() {
        return switch (phase) { case CHECKING, DOWNLOADING, VERIFYING, PREPARING, READY_TO_RESTART, INSTALLING, RESTARTING -> true; default -> false; };
    }
}
