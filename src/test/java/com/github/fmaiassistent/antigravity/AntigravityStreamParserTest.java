package com.github.fmaiassistent.antigravity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntigravityStreamParserTest {
    private final AntigravityStreamParser parser = new AntigravityStreamParser(JsonMapper.builder().build());

    @Test
    void parsesInstalledCliInitEnvelope() {
        AntigravityStreamEvent.Init init = assertInstanceOf(AntigravityStreamEvent.Init.class,
                parser.parse("""
                        {"event":"init","conversation_id":"abc","init":{"cwd":"/workspace","tools":["call_mcp_tool"],"permission_mode":"request-review"}}
                        """).orElseThrow());

        assertEquals("abc", init.conversationId());
        assertEquals("request-review", init.permissionMode());
        assertEquals("call_mcp_tool", init.tools().get(0).asText());
    }

    @Test
    void parsesTextToolAndTerminalResult() {
        AntigravityStreamEvent.Step text = assertInstanceOf(AntigravityStreamEvent.Step.class,
                parser.parse("""
                        {"event":"step_update","step_update":{"conversation_id":"abc","step_index":2,"state":"DONE","step_type":"agent_response","text_delta":"Hello","usage":{"total_tokens":42}}}
                        """).orElseThrow());
        AntigravityStreamEvent.Step tool = assertInstanceOf(AntigravityStreamEvent.Step.class,
                parser.parse("""
                        {"event":"step_update","step_update":{"conversation_id":"abc","step_index":3,"state":"ACTIVE","step_type":"tool","tool_name":"call_mcp_tool","tool_info":{"name":"call_mcp_tool","parameters":{"ServerName":"fm-ai-assistent","ToolName":"fm26_find_players"}}}}
                        """).orElseThrow());
        AntigravityStreamEvent.Result result = assertInstanceOf(AntigravityStreamEvent.Result.class,
                parser.parse("""
                        {"event":"result","result":{"conversation_id":"abc","status":"SUCCESS","response":"Hello","duration_seconds":1.5,"usage":{"total_tokens":50}}}
                        """).orElseThrow());

        assertEquals("Hello", text.textDelta());
        assertEquals(42, text.totalTokens());
        assertEquals("call_mcp_tool", tool.toolName());
        assertEquals("SUCCESS", result.status());
        assertEquals(50, result.totalTokens());
    }

    @Test
    void ignoresUnknownEventsAndRejectsMalformedJson() {
        assertTrue(parser.parse("{\"event\":\"future_event\",\"new_field\":true}").isEmpty());
        assertThrows(AntigravityException.class, () -> parser.parse("not-json"));
    }
}
