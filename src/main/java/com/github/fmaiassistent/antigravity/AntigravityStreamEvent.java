package com.github.fmaiassistent.antigravity;

import tools.jackson.databind.JsonNode;

sealed interface AntigravityStreamEvent {
    String conversationId();

    record Init(
            String conversationId,
            String cwd,
            String permissionMode,
            JsonNode tools) implements AntigravityStreamEvent {
    }

    record Step(
            String conversationId,
            int index,
            String state,
            String type,
            String textDelta,
            String toolName,
            JsonNode toolInfo,
            JsonNode subagentInfo,
            double durationSeconds,
            long totalTokens) implements AntigravityStreamEvent {
    }

    record Result(
            String conversationId,
            String status,
            String response,
            String error,
            double durationSeconds,
            long totalTokens) implements AntigravityStreamEvent {
    }
}
