package com.github.fmaiassistent.copilot;

import java.time.Instant;

public record CopilotConversation(String sessionId, String title, String preview, Instant updatedAt) {
}
