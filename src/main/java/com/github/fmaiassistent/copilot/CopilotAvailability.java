package com.github.fmaiassistent.copilot;

public record CopilotAvailability(State state, String message, String cliVersion, int protocolVersion) {
    public enum State { DISABLED, STARTING, READY, UNAVAILABLE, AUTHENTICATION_REQUIRED, ERROR }

    public boolean ready() {
        return state == State.READY;
    }
}
