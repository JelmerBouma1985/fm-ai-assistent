package com.github.fmaiassistent.antigravity;

import com.github.fmaiassistent.ai.AiPromptContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AntigravityConversationServiceTest {
    private final JsonNode missing = JsonMapper.builder().build().getNodeFactory().missingNode();
    private final List<String> conversationArguments = Collections.synchronizedList(new ArrayList<>());
    private final List<Consumer<AntigravityStreamEvent>> streamListeners = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<AntigravityProcessResult>> completions = new CopyOnWriteArrayList<>();
    private final List<AtomicBoolean> cancellations = new CopyOnWriteArrayList<>();
    private AntigravityConversationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AntigravityCliClient client = mock(AntigravityCliClient.class);
        when(client.available()).thenReturn(true);
        when(client.start(any(), any(), any(), any())).thenAnswer(invocation -> {
            String conversationId = invocation.getArgument(1);
            conversationArguments.add(conversationId);
            streamListeners.add(invocation.getArgument(3));
            CompletableFuture<AntigravityProcessResult> completion = new CompletableFuture<>();
            completions.add(completion);
            AtomicBoolean cancelled = new AtomicBoolean();
            cancellations.add(cancelled);
            return new AntigravityTurnHandle(invocation.getArgument(0), completion, () -> cancelled.set(true));
        });
        AntigravityProperties properties = new AntigravityProperties(
                true, "agy", ".", Duration.ofMinutes(15), Duration.ofSeconds(1),
                null, null, null, false);
        service = new AntigravityConversationService(client, properties, AiPromptContext.none());
        service.initialize();
    }

    @Test
    void capturesConversationIdStreamsOnceAndUsesExactIdForFollowUp() {
        AntigravityConversationSnapshot created = service.newConversation().join();
        List<AntigravityEvent> events = new ArrayList<>();
        service.subscribe(created.conversation().uiId(), events::add);

        String firstTurn = service.sendMessage(created.conversation().uiId(), "Inspect FM state").join();
        assertNull(conversationArguments.getFirst());
        Consumer<AntigravityStreamEvent> first = streamListeners.getFirst();
        first.accept(new AntigravityStreamEvent.Init(
                "agy-A", "/workspace", "request-review", missing));
        first.accept(step("agy-A", 2, "DONE", "agent_response", "Hello ", null, missing));
        first.accept(step("agy-A", 3, "DONE", "agent_response", "world", null, missing));
        first.accept(step("agy-A", 4, "ACTIVE", "tool", null, "call_mcp_tool",
                JsonMapper.builder().build().createObjectNode()
                        .put("name", "call_mcp_tool")
                        .set("parameters", JsonMapper.builder().build().createObjectNode()
                                .put("tool_name", "fm26_find_players"))));
        first.accept(new AntigravityStreamEvent.Result(
                "agy-A", "SUCCESS", "Hello world", null, 1.2, 100));
        completions.getFirst().complete(new AntigravityProcessResult(0, "", false, false, true));

        AntigravityConversationSnapshot afterFirst = service.openConversation(created.conversation().uiId()).join();
        assertEquals("agy-A", afterFirst.conversation().conversationId());
        assertEquals("Hello world", afterFirst.items().stream()
                .filter(item -> item.kind() == AntigravityConversationItem.Kind.ASSISTANT)
                .findFirst().orElseThrow().text());
        assertEquals(1, events.stream().filter(AntigravityEvent.TurnCompleted.class::isInstance).count());

        service.sendMessage(created.conversation().uiId(), "Follow up").join();
        assertEquals("agy-A", conversationArguments.get(1));
        assertEquals(firstTurn, ((AntigravityEvent.TurnStarted) events.getFirst()).turnId());
    }

    @Test
    void keepsNewConversationsIsolatedAndCancelsOnlySelectedTurn() {
        AntigravityConversationSnapshot first = service.newConversation().join();
        AntigravityConversationSnapshot second = service.newConversation().join();

        service.sendMessage(first.conversation().uiId(), "First").join();
        service.sendMessage(second.conversation().uiId(), "Second").join();

        assertNull(conversationArguments.get(0));
        assertNull(conversationArguments.get(1));
        service.interrupt(first.conversation().uiId()).join();
        assertTrue(cancellations.get(0).get());
        assertFalse(cancellations.get(1).get());
    }

    @Test
    void usesResultResponseOnlyWhenNoTextWasStreamed() {
        AntigravityConversationSnapshot conversation = service.newConversation().join();
        service.sendMessage(conversation.conversation().uiId(), "Hello").join();

        streamListeners.getFirst().accept(new AntigravityStreamEvent.Result(
                "agy-B", "SUCCESS", "Fallback response", null, 1, 10));

        AntigravityConversationSnapshot snapshot = service.openConversation(conversation.conversation().uiId()).join();
        assertEquals("Fallback response", snapshot.items().getLast().text());
    }

    private AntigravityStreamEvent.Step step(
            String conversationId,
            int index,
            String state,
            String type,
            String delta,
            String toolName,
            JsonNode toolInfo) {
        return new AntigravityStreamEvent.Step(
                conversationId, index, state, type, delta, toolName, toolInfo, missing, 0, 0);
    }
}
