package com.github.fmaiassistent.copilot;

import java.util.List;

public record CopilotConversationSnapshot(
        CopilotConversation conversation,
        List<CopilotConversationItem> items,
        String activeTurnId,
        String selectedModel) {
}
