package com.github.fmaiassistent.codex;

public sealed interface CodexEvent {
    String threadId();

    record TurnStarted(String threadId, String turnId) implements CodexEvent {
    }

    record AssistantStarted(String threadId, String turnId, String itemId) implements CodexEvent {
    }

    record AssistantTextDelta(String threadId, String turnId, String itemId, String delta) implements CodexEvent {
    }

    record AssistantCompleted(String threadId, String turnId, String itemId, String text) implements CodexEvent {
    }

    record ToolStarted(
            String threadId,
            String turnId,
            String itemId,
            String label,
            String details) implements CodexEvent {
    }

    record ToolCompleted(
            String threadId,
            String turnId,
            String itemId,
            String label,
            String status,
            String details) implements CodexEvent {
    }

    record TurnCompleted(String threadId, String turnId, String status, String error) implements CodexEvent {
    }

    record Failure(String threadId, String message) implements CodexEvent {
    }

    record ApprovalRequested(
            String threadId,
            String turnId,
            String requestKey,
            ApprovalKind kind,
            String summary,
            String details) implements CodexEvent {
    }

    record McpStatusChanged(String threadId, String server, String status, String error) implements CodexEvent {
    }

    enum ApprovalKind {
        COMMAND,
        FILE_CHANGE,
        PERMISSIONS
    }
}
