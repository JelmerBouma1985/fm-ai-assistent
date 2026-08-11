package com.github.fmaiassistent.codex;

public record CodexAvailability(State state, String message) {
    public enum State {
        DISABLED,
        STARTING,
        AUTHENTICATING,
        READY,
        AUTHENTICATION_REQUIRED,
        UNAVAILABLE,
        ERROR
    }

    public boolean ready() {
        return state == State.READY;
    }
}
