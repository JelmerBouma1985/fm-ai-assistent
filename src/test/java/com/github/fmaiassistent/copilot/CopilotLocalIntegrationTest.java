package com.github.fmaiassistent.copilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.UserInputResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
class CopilotLocalIntegrationTest {
    @Test
    void startsAuthenticatedSdkSessionAndReceivesAnswer() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        CopilotClient client = new CopilotClient(new CopilotClientOptions()
                .setCliPath("copilot")
                .setCwd(Path.of(".").toAbsolutePath().normalize().toString())
                .setUseLoggedInUser(true)
                .setUseStdio(true));
        try {
            client.start().get(30, TimeUnit.SECONDS);
            assertThat(client.getAuthStatus().get(15, TimeUnit.SECONDS).isAuthenticated()).isTrue();
            assertThat(client.getStatus().get(15, TimeUnit.SECONDS).getProtocolVersion()).isBetween(2, 3);
            var session = client.createSession(new SessionConfig()
                    .setSessionId(sessionId)
                    .setWorkingDirectory(Path.of(".").toAbsolutePath().normalize().toString())
                    .setStreaming(true)
                    .setOnPermissionRequest((request, invocation) ->
                            java.util.concurrent.CompletableFuture.completedFuture(
                                    PermissionRequestResult.reject("Integration test allows no tools")))
                    .setOnUserInputRequest((request, invocation) ->
                            java.util.concurrent.CompletableFuture.completedFuture(
                                    new UserInputResponse().setAnswer("").setWasFreeform(false))))
                    .get(30, TimeUnit.SECONDS);
            var response = session.sendAndWait("Reply exactly with OK.").get(2, TimeUnit.MINUTES);
            assertThat(response.getData().content().strip()).isEqualTo("OK");
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
