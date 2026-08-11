package com.github.fmaiassistent.codex;

import tools.jackson.databind.JsonNode;

public final class CodexRpcException extends CodexException {
    private final JsonNode error;

    CodexRpcException(String message, JsonNode error) {
        super(message);
        this.error = error;
    }

    public JsonNode error() {
        return error;
    }
}
