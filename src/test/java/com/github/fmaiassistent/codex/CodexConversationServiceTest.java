package com.github.fmaiassistent.codex;

import com.github.fmaiassistent.ai.AiPromptContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexConversationServiceTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private CodexAppServerClient client;
    private CodexConversationService service;
    private Consumer<CodexJsonRpcClient.Notification> notifications;
    private Consumer<CodexJsonRpcClient.ServerRequest> serverRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        client = mock(CodexAppServerClient.class);
        when(client.onNotification(any())).thenAnswer(invocation -> {
            notifications = invocation.getArgument(0);
            return (CodexSubscription) () -> { };
        });
        when(client.onServerRequest(any())).thenAnswer(invocation -> {
            serverRequests = invocation.getArgument(0);
            return (CodexSubscription) () -> { };
        });
        when(client.onFailure(any())).thenReturn(() -> { });
        when(client.start()).thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()));
        when(client.account()).thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()
                .set("account", mapper.createObjectNode().put("type", "chatgpt").put("planType", "plus"))
                .put("requiresOpenaiAuth", true)));
        CodexProperties properties = new CodexProperties(
                true, "codex", ".",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        service = new CodexConversationService(client, properties, mapper, AiPromptContext.none());
        service.start();
        waitUntil(() -> service.availability().ready());
    }

    @Test
    void createsAndReadsPersistedThreadHistory() throws Exception {
        JsonNode thread = thread("thread-1", "My first question", mapper.createArrayNode().add(
                mapper.createObjectNode()
                        .put("id", "turn-1")
                        .put("status", "completed")
                        .set("items", mapper.createArrayNode()
                                .add(mapper.createObjectNode().put("id", "u1").put("type", "userMessage")
                                        .set("content", mapper.createArrayNode().add(
                                                mapper.createObjectNode().put("type", "text").put("text", "Hello"))))
                                .add(mapper.createObjectNode().put("id", "a1").put("type", "agentMessage")
                                        .put("text", "Hi there")))));
        when(client.startThread()).thenReturn(CompletableFuture.completedFuture(
                mapper.createObjectNode().set("thread", thread)));

        CodexConversationSnapshot snapshot = service.newConversation().get(1, TimeUnit.SECONDS);

        assertEquals("thread-1", snapshot.conversation().threadId());
        assertEquals("My first question", snapshot.conversation().title());
        assertEquals(List.of(CodexConversationItem.Kind.USER, CodexConversationItem.Kind.ASSISTANT),
                snapshot.items().stream().map(CodexConversationItem::kind).toList());
    }

    @Test
    void convertsStreamingEventsAndInterruptsActiveTurn() throws Exception {
        when(client.startTurn("thread-1", "Hello", "ignored"))
                .thenReturn(CompletableFuture.completedFuture(turnResponse("turn-1")));
        when(client.startTurn(org.mockito.ArgumentMatchers.eq("thread-1"),
                org.mockito.ArgumentMatchers.eq("Hello"), any()))
                .thenReturn(CompletableFuture.completedFuture(turnResponse("turn-1")));
        when(client.interruptTurn("thread-1", "turn-1"))
                .thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()));
        List<CodexEvent> events = new CopyOnWriteArrayList<>();
        service.subscribe("thread-1", events::add);

        service.sendMessage("thread-1", "Hello").get(1, TimeUnit.SECONDS);
        notifications.accept(notification("item/agentMessage/delta", mapper.createObjectNode()
                .put("threadId", "thread-1").put("turnId", "turn-1")
                .put("itemId", "agent-1").put("delta", "Hi")));
        service.interrupt("thread-1").get(1, TimeUnit.SECONDS);
        notifications.accept(notification("turn/completed", mapper.createObjectNode()
                .put("threadId", "thread-1")
                .set("turn", mapper.createObjectNode().put("id", "turn-1").put("status", "interrupted"))));

        assertInstanceOf(CodexEvent.AssistantTextDelta.class, events.getFirst());
        assertEquals("Hi", ((CodexEvent.AssistantTextDelta) events.getFirst()).delta());
        assertEquals("interrupted", ((CodexEvent.TurnCompleted) events.getLast()).status());
        verify(client).interruptTurn("thread-1", "turn-1");
    }

    @Test
    void completedTurnCannotBeReactivatedByLateStartResponse() throws Exception {
        CompletableFuture<JsonNode> firstResponse = new CompletableFuture<>();
        when(client.startTurn(org.mockito.ArgumentMatchers.eq("thread-1"),
                org.mockito.ArgumentMatchers.eq("First"), any())).thenReturn(firstResponse);
        CompletableFuture<String> firstTurn = service.sendMessage("thread-1", "First");

        notifications.accept(notification("turn/completed", mapper.createObjectNode()
                .put("threadId", "thread-1")
                .set("turn", mapper.createObjectNode().put("id", "turn-1").put("status", "completed"))));
        firstResponse.complete(turnResponse("turn-1"));
        firstTurn.get(1, TimeUnit.SECONDS);

        when(client.startTurn(org.mockito.ArgumentMatchers.eq("thread-1"),
                org.mockito.ArgumentMatchers.eq("Follow-up"), any()))
                .thenReturn(CompletableFuture.completedFuture(turnResponse("turn-2")));
        assertEquals("turn-2", service.sendMessage("thread-1", "Follow-up").get(1, TimeUnit.SECONDS));
    }

    @Test
    void exposesApprovalAndReturnsExplicitDecision() {
        List<CodexEvent> events = new CopyOnWriteArrayList<>();
        service.subscribe("thread-1", events::add);
        JsonNode requestId = mapper.getNodeFactory().numberNode(99);

        serverRequests.accept(new CodexJsonRpcClient.ServerRequest(
                requestId,
                "item/commandExecution/requestApproval",
                mapper.createObjectNode()
                        .put("threadId", "thread-1")
                        .put("turnId", "turn-1")
                        .put("command", "./mvnw test")
                        .put("cwd", "/workspace")));
        CodexEvent.ApprovalRequested approval = (CodexEvent.ApprovalRequested) events.getFirst();
        service.decideApproval(approval.requestKey(), CodexConversationService.ApprovalDecision.DENY);

        assertEquals("./mvnw test", approval.summary());
        ArgumentCaptor<JsonNode> response = ArgumentCaptor.forClass(JsonNode.class);
        verify(client).respond(org.mockito.ArgumentMatchers.eq(requestId), response.capture());
        assertEquals("decline", response.getValue().path("decision").asText());
    }

    @Test
    void startsAndCompletesChatGptBrowserLogin() throws Exception {
        when(client.startChatGptLogin()).thenReturn(CompletableFuture.completedFuture(mapper.createObjectNode()
                .put("type", "chatgpt")
                .put("loginId", "login-1")
                .put("authUrl", "https://chatgpt.com/auth")));

        CodexLogin login = service.startChatGptLogin().get(1, TimeUnit.SECONDS);

        assertEquals("login-1", login.loginId());
        assertEquals(CodexAvailability.State.AUTHENTICATING, service.availability().state());
        notifications.accept(notification("account/login/completed", mapper.createObjectNode()
                .put("loginId", "login-1")
                .put("success", true)));
        waitUntil(() -> service.availability().ready());
    }

    @Test
    void synchronousStartupFailureDoesNotRemainStuckOnStarting() throws Exception {
        CodexAppServerClient failingClient = mock(CodexAppServerClient.class);
        when(failingClient.onNotification(any())).thenReturn(() -> { });
        when(failingClient.onServerRequest(any())).thenReturn(() -> { });
        when(failingClient.onFailure(any())).thenReturn(() -> { });
        when(failingClient.start()).thenThrow(new CodexException(
                "Codex is not installed or could not be started."));
        CodexProperties properties = new CodexProperties(
                true, "codex", ".",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        CodexConversationService failingService = new CodexConversationService(
                failingClient, properties, mapper, AiPromptContext.none());

        failingService.start();

        waitUntil(() -> failingService.availability().state() == CodexAvailability.State.UNAVAILABLE);
        assertTrue(failingService.availability().message().contains("not installed"));
    }

    private JsonNode thread(String id, String preview, JsonNode turns) {
        return mapper.createObjectNode()
                .put("id", id)
                .put("preview", preview)
                .put("updatedAt", 100)
                .set("turns", turns);
    }

    private JsonNode turnResponse(String id) {
        return mapper.createObjectNode().set("turn", mapper.createObjectNode().put("id", id));
    }

    private static CodexJsonRpcClient.Notification notification(String method, JsonNode params) {
        return new CodexJsonRpcClient.Notification(method, params);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }
}
