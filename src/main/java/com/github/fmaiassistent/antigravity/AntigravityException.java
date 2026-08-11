package com.github.fmaiassistent.antigravity;

public class AntigravityException extends RuntimeException {
    private final Code code;

    public AntigravityException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AntigravityException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        EXECUTABLE_NOT_FOUND,
        AUTHENTICATION_REQUIRED,
        WORKSPACE_NOT_TRUSTED,
        PROCESS_START_FAILED,
        PROCESS_EXITED,
        STREAM_PARSE_FAILED,
        ANTIGRAVITY_ERROR,
        MCP_ERROR,
        PERMISSION_DENIED,
        TIMEOUT,
        INTERRUPTED,
        INVALID_CONVERSATION
    }
}
