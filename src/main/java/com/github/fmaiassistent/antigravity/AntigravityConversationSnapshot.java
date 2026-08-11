package com.github.fmaiassistent.antigravity;

import java.util.List;

public record AntigravityConversationSnapshot(
        AntigravityConversation conversation,
        List<AntigravityConversationItem> items,
        String activeTurnId,
        String permissionMode) {
}
