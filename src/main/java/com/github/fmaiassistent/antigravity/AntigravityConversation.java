package com.github.fmaiassistent.antigravity;

import java.time.Instant;

public record AntigravityConversation(
        String uiId,
        String conversationId,
        String title,
        String preview,
        Instant updatedAt) {
}
