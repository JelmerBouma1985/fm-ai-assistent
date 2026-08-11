package com.github.fmaiassistent.antigravity;

public record AntigravityAvailability(State state, String message) {
    public boolean ready() {
        return state == State.READY;
    }

    public enum State {
        DISABLED,
        READY,
        BUSY,
        UNAVAILABLE,
        AUTHENTICATION_REQUIRED,
        ERROR
    }
}
