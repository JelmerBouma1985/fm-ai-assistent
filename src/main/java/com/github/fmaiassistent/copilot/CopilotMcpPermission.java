package com.github.fmaiassistent.copilot;

import com.github.copilot.generated.rpc.PermissionRule;
import com.github.copilot.rpc.PermissionRequest;

import java.util.Optional;

record CopilotMcpPermission(String serverName, String toolName) {
    private static final String APPLICATION_SERVER = "fm-ai-assistent";

    static Optional<CopilotMcpPermission> applicationTool(PermissionRequest request, String startedToolName) {
        if (!"mcp".equalsIgnoreCase(request.getKind())) {
            return Optional.empty();
        }
        String server = extensionString(request, "serverName");
        // toolName may contain Copilot's server prefix. toolTitle is the MCP
        // registration name expected by permission rules.
        String tool = firstNonBlank(extensionString(request, "toolTitle"), extensionString(request, "toolName"));
        if (APPLICATION_SERVER.equals(server) && !tool.isBlank()) {
            return Optional.of(new CopilotMcpPermission(server, tool));
        }

        // Current CLI versions may omit serverName/toolTitle from PermissionRequest,
        // while the preceding tool.execution_start contains both. The service links
        // them by toolCallId and supplies its stable mapped name here.
        String prefix = "MCP: " + APPLICATION_SERVER + "/";
        if (startedToolName != null && startedToolName.startsWith(prefix)) {
            String startedTool = startedToolName.substring(prefix.length());
            if (!startedTool.isBlank()) {
                return Optional.of(new CopilotMcpPermission(APPLICATION_SERVER, startedTool));
            }
        }
        return Optional.empty();
    }

    PermissionRule rule() {
        return new PermissionRule(serverName, toolName);
    }

    private static String extensionString(PermissionRequest request, String key) {
        Object value = request.getExtensionData() == null ? null : request.getExtensionData().get(key);
        return value == null ? "" : value.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
