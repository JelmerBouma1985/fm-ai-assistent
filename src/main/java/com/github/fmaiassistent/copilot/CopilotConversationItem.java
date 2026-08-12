package com.github.fmaiassistent.copilot;

public record CopilotConversationItem(String id, Kind kind, String text, String status, String details) {
    public enum Kind { USER, ASSISTANT, TOOL, SYSTEM }
}
