package com.github.fmaiassistent.antigravity;

public record AntigravityProcessResult(
        int exitCode,
        String stderr,
        boolean cancelled,
        boolean timedOut,
        boolean resultReceived) {
}
