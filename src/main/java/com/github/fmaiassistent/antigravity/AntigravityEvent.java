package com.github.fmaiassistent.antigravity;

public sealed interface AntigravityEvent {
    String uiConversationId();

    record TurnStarted(String uiConversationId, String turnId) implements AntigravityEvent {
    }

    record Initialized(
            String uiConversationId,
            String turnId,
            String conversationId,
            String workingDirectory,
            String permissionMode) implements AntigravityEvent {
    }

    record AssistantTextDelta(
            String uiConversationId,
            String turnId,
            String itemId,
            String delta) implements AntigravityEvent {
    }

    record ToolStarted(
            String uiConversationId,
            String turnId,
            String itemId,
            String label,
            String details,
            boolean mcp) implements AntigravityEvent {
    }

    record ToolCompleted(
            String uiConversationId,
            String turnId,
            String itemId,
            String label,
            String status,
            String details,
            boolean mcp) implements AntigravityEvent {
    }

    record SubagentUpdated(
            String uiConversationId,
            String turnId,
            String itemId,
            String label,
            String status,
            String details) implements AntigravityEvent {
    }

    record TurnCompleted(
            String uiConversationId,
            String turnId,
            String status,
            String fallbackResponse,
            String error,
            double durationSeconds,
            long totalTokens) implements AntigravityEvent {
    }

    record Failure(String uiConversationId, String turnId, String message) implements AntigravityEvent {
    }
}
