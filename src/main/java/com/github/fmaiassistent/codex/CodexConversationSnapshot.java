package com.github.fmaiassistent.codex;

import java.util.List;

public record CodexConversationSnapshot(
        CodexConversation conversation,
        List<CodexConversationItem> items,
        String activeTurnId) {
}
