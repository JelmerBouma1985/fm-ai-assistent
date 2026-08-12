package com.github.fmaiassistent.copilot;

import java.util.List;

public sealed interface CopilotEvent {
    String sessionId();

    record TurnStarted(String sessionId, String turnId) implements CopilotEvent { }
    record TextDelta(String sessionId, String turnId, String itemId, String delta) implements CopilotEvent { }
    record ToolStarted(String sessionId, String turnId, String itemId, String name, String details, boolean mcp)
            implements CopilotEvent { }
    record ToolCompleted(String sessionId, String turnId, String itemId, String name, String status, String details,
                         boolean mcp) implements CopilotEvent { }
    record PermissionRequested(String sessionId, String requestId, String kind, String description,
                               boolean applicationMcp, String mcpToolName)
            implements CopilotEvent { }
    record UserInputRequested(String sessionId, String requestId, String question, List<String> choices,
                              boolean freeform) implements CopilotEvent { }
    record TurnCompleted(String sessionId, String turnId, boolean interrupted, String fallbackResponse)
            implements CopilotEvent { }
    record Failure(String sessionId, String turnId, String message) implements CopilotEvent { }
}
