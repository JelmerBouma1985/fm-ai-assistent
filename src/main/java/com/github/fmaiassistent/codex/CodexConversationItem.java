package com.github.fmaiassistent.codex;

public record CodexConversationItem(
        String id,
        Kind kind,
        String text,
        String status,
        String details) {

    public enum Kind {
        USER,
        ASSISTANT,
        TOOL,
        SYSTEM
    }
}
