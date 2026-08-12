package com.github.fmaiassistent.copilot;

import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.UnknownSessionEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotSdkEventMapperTest {
    private final CopilotSdkEventMapper mapper = new CopilotSdkEventMapper();
    private final Map<String, String> toolNames = new HashMap<>();

    @Test
    void mapsAssistantTextDelta() {
        AssistantMessageDeltaEvent event = new AssistantMessageDeltaEvent();
        event.setData(new AssistantMessageDeltaEvent.AssistantMessageDeltaEventData("message-1", "Hello", null));

        assertThat(mapper.map(event, toolNames))
                .contains(new CopilotSdkEventMapper.TextDelta("message-1", "Hello"));
    }

    @Test
    void mapsFinalTextAsStreamingFallback() {
        AssistantMessageEvent event = new AssistantMessageEvent();
        event.setData(new AssistantMessageEvent.AssistantMessageEventData(
                "message-1", "model", "Complete answer", null, null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, null, null, null, null));

        assertThat(mapper.map(event, toolNames))
                .contains(new CopilotSdkEventMapper.FinalText("message-1", "Complete answer"));
    }

    @Test
    void mapsMcpToolLifecycleAndKeepsName() {
        ToolExecutionStartEvent started = new ToolExecutionStartEvent();
        started.setData(new ToolExecutionStartEvent.ToolExecutionStartEventData(
                "call-1", "mcp", Map.of("clubId", 123), null, null, false,
                "fm-ai-assistent", "fm26_get_club_context", "turn-1", false, null, null));

        assertThat(mapper.map(started, toolNames)).contains(new CopilotSdkEventMapper.ToolStarted(
                "call-1", "MCP: fm-ai-assistent/fm26_get_club_context", "{clubId=123}", true));

        ToolExecutionCompleteEvent completed = new ToolExecutionCompleteEvent();
        completed.setData(new ToolExecutionCompleteEvent.ToolExecutionCompleteEventData(
                "call-1", true, null, Map.of("server", "fm-ai-assistent"), null,
                false, false, null, null, Map.of(), "turn-1", null, false, null));

        assertThat(mapper.map(completed, toolNames)).contains(new CopilotSdkEventMapper.ToolCompleted(
                "call-1", "MCP: fm-ai-assistent/fm26_get_club_context", "completed", "null", true));
    }

    @Test
    void mapsAbortAndFailure() {
        SessionIdleEvent idle = new SessionIdleEvent();
        idle.setData(new SessionIdleEvent.SessionIdleEventData(true));
        SessionErrorEvent error = new SessionErrorEvent();
        error.setData(new SessionErrorEvent.SessionErrorEventData(
                "runtime", "FAILED", false, "Something failed", null, 500L, null, null, null));

        assertThat(mapper.map(idle, toolNames)).contains(new CopilotSdkEventMapper.TurnIdle(true));
        assertThat(mapper.map(error, toolNames)).contains(new CopilotSdkEventMapper.Failure("Something failed"));
    }

    @Test
    void ignoresUnknownEvents() {
        assertThat(mapper.map(new UnknownSessionEvent(), toolNames)).isEmpty();
    }
}
