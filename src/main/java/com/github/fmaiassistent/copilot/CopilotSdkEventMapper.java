package com.github.fmaiassistent.copilot;

import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class CopilotSdkEventMapper {
    private static final int MAX_DETAILS = 2_000;

    Optional<MappedEvent> map(SessionEvent event, Map<String, String> toolNames) {
        if (event instanceof AssistantMessageDeltaEvent delta && delta.getData() != null) {
            String text = delta.getData().deltaContent();
            return text == null || text.isEmpty() ? Optional.empty()
                    : Optional.of(new TextDelta(messageId(delta.getData().messageId(), event), text));
        }
        if (event instanceof AssistantMessageEvent message && message.getData() != null) {
            String text = message.getData().content();
            return text == null || text.isEmpty() ? Optional.empty()
                    : Optional.of(new FinalText(messageId(message.getData().messageId(), event), text));
        }
        if (event instanceof ToolExecutionStartEvent started && started.getData() != null) {
            var data = started.getData();
            String id = firstNonBlank(data.toolCallId(), UUID.randomUUID().toString());
            String name = toolName(data.toolName(), data.mcpServerName(), data.mcpToolName());
            toolNames.put(id, name);
            return Optional.of(new ToolStarted(id, name,
                    abbreviate(String.valueOf(data.arguments()), MAX_DETAILS),
                    data.mcpServerName() != null || data.mcpToolName() != null));
        }
        if (event instanceof ToolExecutionCompleteEvent completed && completed.getData() != null) {
            var data = completed.getData();
            String id = firstNonBlank(data.toolCallId(), UUID.randomUUID().toString());
            String name = toolNames.getOrDefault(id, "Copilot tool");
            String details = data.error() == null ? String.valueOf(data.result()) : String.valueOf(data.error());
            return Optional.of(new ToolCompleted(id, name,
                    Boolean.TRUE.equals(data.success()) ? "completed" : "failed",
                    abbreviate(details, MAX_DETAILS), data.mcpMeta() != null || name.startsWith("MCP:")));
        }
        if (event instanceof SessionIdleEvent idle) {
            return Optional.of(new TurnIdle(idle.getData() != null && Boolean.TRUE.equals(idle.getData().aborted())));
        }
        if (event instanceof SessionErrorEvent failed) {
            String message = failed.getData() == null ? null : failed.getData().message();
            return Optional.of(new Failure(firstNonBlank(message, "GitHub Copilot turn failed")));
        }
        return Optional.empty();
    }

    sealed interface MappedEvent permits TextDelta, FinalText, ToolStarted, ToolCompleted, TurnIdle, Failure { }
    record TextDelta(String messageId, String text) implements MappedEvent { }
    record FinalText(String messageId, String text) implements MappedEvent { }
    record ToolStarted(String id, String name, String details, boolean mcp) implements MappedEvent { }
    record ToolCompleted(String id, String name, String status, String details, boolean mcp) implements MappedEvent { }
    record TurnIdle(boolean interrupted) implements MappedEvent { }
    record Failure(String message) implements MappedEvent { }

    static String toolName(String name, String mcpServer, String mcpTool) {
        if (mcpTool != null && !mcpTool.isBlank()) {
            return "MCP: " + (mcpServer == null || mcpServer.isBlank() ? "" : mcpServer + "/") + mcpTool;
        }
        return firstNonBlank(name, "Copilot tool");
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String messageId(String messageId, SessionEvent event) {
        return firstNonBlank(messageId, event.getId() == null ? null : event.getId().toString(), "assistant");
    }
}
