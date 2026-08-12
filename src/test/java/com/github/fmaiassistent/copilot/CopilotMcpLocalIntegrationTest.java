package com.github.fmaiassistent.copilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.UserInputResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "copilot.mcp.integration", matches = "true")
class CopilotMcpLocalIntegrationTest {
    @Test
    void discoversAndCallsExistingReadOnlyMcpServer() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        CopilotClient client = new CopilotClient(new CopilotClientOptions()
                .setCliPath("copilot")
                .setCwd(Path.of(".").toAbsolutePath().normalize().toString())
                .setUseLoggedInUser(true)
                .setUseStdio(true));
        try {
            client.start().get(30, TimeUnit.SECONDS);
            var session = client.createSession(new SessionConfig()
                    .setSessionId(sessionId)
                    .setWorkingDirectory(Path.of(".").toAbsolutePath().normalize().toString())
                    .setEnableConfigDiscovery(true)
                    .setStreaming(true)
                    .setOnPermissionRequest((request, invocation) -> {
                        String details = String.valueOf(request.getExtensionData());
                        boolean readOnlyFmTool = details.contains("fm-ai-assistent")
                                || details.contains("fm26_find_clubs");
                        return java.util.concurrent.CompletableFuture.completedFuture(readOnlyFmTool
                                ? PermissionRequestResult.approveOnce()
                                : PermissionRequestResult.reject("Only read-only FM MCP call is allowed"));
                    })
                    .setOnUserInputRequest((request, invocation) ->
                            java.util.concurrent.CompletableFuture.completedFuture(
                                    new UserInputResponse().setAnswer("").setWasFreeform(false))))
                    .get(30, TimeUnit.SECONDS);
            AtomicReference<String> calledTool = new AtomicReference<>();
            var subscription = session.on(ToolExecutionStartEvent.class, event -> {
                if (event.getData() != null && "fm-ai-assistent".equals(event.getData().mcpServerName())) {
                    calledTool.set(event.getData().mcpToolName());
                }
            });
            session.sendAndWait("Call the fm-ai-assistent fm26_find_clubs tool with query Heerenveen. "
                    + "Then briefly report its result.").get(3, TimeUnit.MINUTES);
            subscription.close();
            assertThat(calledTool.get()).isEqualTo("fm26_find_clubs");
            session.close();
            client.deleteSession(sessionId).get(15, TimeUnit.SECONDS);
        } finally {
            try {
                client.stop().get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                client.forceStop().get(15, TimeUnit.SECONDS);
            }
        }
    }
}
