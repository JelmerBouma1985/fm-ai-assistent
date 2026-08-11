package com.github.fmaiassistent.antigravity;

public record AntigravityConversationItem(
        String id,
        Kind kind,
        String text,
        String status,
        String details) {
    public enum Kind {
        USER,
        ASSISTANT,
        TOOL,
        SUBAGENT,
        SYSTEM
    }
}
