package com.github.fmaiassistent.copilot;

import com.github.copilot.rpc.PermissionRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotMcpPermissionTest {
    @Test
    void createsExactRuleForApplicationMcpTool() {
        PermissionRequest request = new PermissionRequest();
        request.setKind("mcp");
        request.setExtensionData(Map.of(
                "serverName", "fm-ai-assistent",
                "toolName", "fm-ai-assistent-fm26_get_club_context",
                "toolTitle", "fm26_get_club_context"));

        CopilotMcpPermission permission = CopilotMcpPermission.applicationTool(request, null).orElseThrow();

        assertThat(permission.rule().kind()).isEqualTo("fm-ai-assistent");
        assertThat(permission.rule().argument()).isEqualTo("fm26_get_club_context");
    }

    @Test
    void rejectsOtherMcpServersAndNonMcpRequests() {
        PermissionRequest otherServer = new PermissionRequest();
        otherServer.setKind("mcp");
        otherServer.setExtensionData(Map.of("serverName", "other", "toolTitle", "read"));
        PermissionRequest shell = new PermissionRequest();
        shell.setKind("shell");
        shell.setExtensionData(Map.of("serverName", "fm-ai-assistent", "toolTitle", "command"));

        assertThat(CopilotMcpPermission.applicationTool(otherServer, null)).isEmpty();
        assertThat(CopilotMcpPermission.applicationTool(shell, null)).isEmpty();
    }

    @Test
    void fallsBackToCorrelatedToolStartWhenPermissionMetadataIsMissing() {
        PermissionRequest request = new PermissionRequest();
        request.setKind("mcp");
        request.setToolCallId("call-1");

        CopilotMcpPermission permission = CopilotMcpPermission.applicationTool(
                request, "MCP: fm-ai-assistent/fm26_get_club_context").orElseThrow();

        assertThat(permission.rule().kind()).isEqualTo("fm-ai-assistent");
        assertThat(permission.rule().argument()).isEqualTo("fm26_get_club_context");
    }
}
