package com.github.fmaiassistent.codex;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexAppServerClientTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void createsNormalAppServerThreadWithoutCustomSource() {
        CodexJsonRpcClient rpc = mock(CodexJsonRpcClient.class);
        when(rpc.request(eq("thread/start"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()));
        CodexAppServerClient client = client(rpc);

        client.startThread();

        ArgumentCaptor<JsonNode> params = ArgumentCaptor.forClass(JsonNode.class);
        verify(rpc).request(eq("thread/start"), params.capture());
        assertFalse(params.getValue().has("serviceName"));
    }

    @Test
    void listsAllResumableWorkspaceThreadsWithoutSourceFilter() {
        CodexJsonRpcClient rpc = mock(CodexJsonRpcClient.class);
        when(rpc.request(eq("thread/list"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()));
        CodexAppServerClient client = client(rpc);

        client.listThreads();

        ArgumentCaptor<JsonNode> params = ArgumentCaptor.forClass(JsonNode.class);
        verify(rpc).request(eq("thread/list"), params.capture());
        assertFalse(params.getValue().has("sourceKinds"));
    }

    private CodexAppServerClient client(CodexJsonRpcClient rpc) {
        CodexProperties properties = new CodexProperties(
                true, "codex", ".",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new CodexAppServerClient(rpc, mapper, new CodexWorkspaceResolver(properties));
    }
}
