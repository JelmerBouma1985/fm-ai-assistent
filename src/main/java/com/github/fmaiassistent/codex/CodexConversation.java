package com.github.fmaiassistent.codex;

import java.time.Instant;

public record CodexConversation(
        String threadId,
        String title,
        String preview,
        Instant updatedAt) {
}
