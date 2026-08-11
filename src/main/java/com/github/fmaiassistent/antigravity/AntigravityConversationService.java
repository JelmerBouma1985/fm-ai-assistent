package com.github.fmaiassistent.antigravity;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class AntigravityConversationService {
    private static final Logger log = LoggerFactory.getLogger(AntigravityConversationService.class);
    private static final int MAX_DETAILS = 2_000;

    private final AntigravityCliClient client;
    private final AntigravityProperties properties;
    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<AntigravityEvent>>> listeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<AntigravityAvailability>> availabilityListeners =
            new CopyOnWriteArrayList<>();
    private volatile AntigravityAvailability availability;

    AntigravityConversationService(AntigravityCliClient client, AntigravityProperties properties) {
        this.client = client;
        this.properties = properties;
        availability = properties.enabled()
                ? new AntigravityAvailability(AntigravityAvailability.State.READY, "Antigravity ready")
                : new AntigravityAvailability(AntigravityAvailability.State.DISABLED, "Antigravity integration is disabled");
    }

    @PostConstruct
    void initialize() {
        if (properties.enabled() && !client.available()) {
            setAvailability(new AntigravityAvailability(
                    AntigravityAvailability.State.UNAVAILABLE,
                    "Antigravity CLI unavailable · `" + properties.executable() + "` was not found"));
        }
    }

    public AntigravityAvailability availability() {
        return availability;
    }

    public AntigravitySubscription subscribeAvailability(Consumer<AntigravityAvailability> listener) {
        availabilityListeners.add(listener);
        return () -> availabilityListeners.remove(listener);
    }

    public AntigravitySubscription subscribe(String uiConversationId, Consumer<AntigravityEvent> listener) {
        listeners.computeIfAbsent(uiConversationId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<AntigravityEvent>> current = listeners.get(uiConversationId);
            if (current != null) {
                current.remove(listener);
                if (current.isEmpty()) {
                    listeners.remove(uiConversationId, current);
                }
            }
        };
    }

    public CompletableFuture<AntigravityConversationSnapshot> newConversation() {
        if (!availability.ready()) {
            return CompletableFuture.failedFuture(new AntigravityException(
                    AntigravityException.Code.EXECUTABLE_NOT_FOUND, availability.message()));
        }
        String uiId = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(uiId);
        conversations.put(uiId, state);
        return CompletableFuture.completedFuture(snapshot(state));
    }

    public CompletableFuture<List<AntigravityConversation>> listConversations() {
        List<AntigravityConversation> values = conversations.values().stream()
                .map(this::conversation)
                .sorted(Comparator.comparing(AntigravityConversation::updatedAt).reversed())
                .toList();
        return CompletableFuture.completedFuture(values);
    }

    public CompletableFuture<AntigravityConversationSnapshot> openConversation(String uiId) {
        ConversationState state = conversations.get(uiId);
        if (state == null) {
            return CompletableFuture.failedFuture(new AntigravityException(
                    AntigravityException.Code.INVALID_CONVERSATION, "Antigravity conversation was not found"));
        }
        return CompletableFuture.completedFuture(snapshot(state));
    }

    public CompletableFuture<String> sendMessage(String uiId, String text) {
        ConversationState state = conversations.get(uiId);
        if (state == null) {
            return CompletableFuture.failedFuture(new AntigravityException(
                    AntigravityException.Code.INVALID_CONVERSATION, "Antigravity conversation was not found"));
        }
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Message cannot be empty"));
        }

        String turnId = UUID.randomUUID().toString();
        String antigravityId;
        synchronized (state) {
            if (state.activeTurnId != null) {
                return CompletableFuture.failedFuture(new AntigravityException(
                        AntigravityException.Code.ANTIGRAVITY_ERROR,
                        "A turn is already active in this Antigravity conversation"));
            }
            state.activeTurnId = turnId;
            state.updatedAt = Instant.now();
            if (state.items.isEmpty()) {
                state.title = title(text);
                state.preview = abbreviate(text.strip(), 140);
            }
            state.items.add(new AntigravityConversationItem(
                    "user-" + turnId,
                    AntigravityConversationItem.Kind.USER,
                    text,
                    "completed",
                    null));
            antigravityId = state.antigravityConversationId;
        }
        emit(new AntigravityEvent.TurnStarted(uiId, turnId));

        try {
            AntigravityTurnHandle handle = client.start(
                    turnId, antigravityId, text, event -> handleStreamEvent(state, turnId, event));
            synchronized (state) {
                if (turnId.equals(state.activeTurnId)) {
                    state.activeHandle = handle;
                }
            }
            handle.completion().whenComplete((result, error) -> completeProcess(state, turnId, result, error));
            log.info("Started Antigravity prompt uiConversationId={} conversationId={} turnId={}",
                    uiId, antigravityId, turnId);
            return CompletableFuture.completedFuture(turnId);
        } catch (RuntimeException ex) {
            synchronized (state) {
                state.activeTurnId = null;
                state.activeHandle = null;
            }
            emit(new AntigravityEvent.Failure(uiId, turnId, friendlyMessage(ex, null)));
            return CompletableFuture.failedFuture(ex);
        }
    }

    public CompletableFuture<Void> interrupt(String uiId) {
        ConversationState state = conversations.get(uiId);
        if (state == null) {
            return CompletableFuture.failedFuture(new AntigravityException(
                    AntigravityException.Code.INVALID_CONVERSATION, "Antigravity conversation was not found"));
        }
        AntigravityTurnHandle handle;
        synchronized (state) {
            handle = state.activeHandle;
        }
        if (handle == null) {
            return CompletableFuture.failedFuture(new AntigravityException(
                    AntigravityException.Code.INTERRUPTED, "No Antigravity turn is active"));
        }
        handle.cancel();
        return CompletableFuture.completedFuture(null);
    }

    private void handleStreamEvent(
            ConversationState state,
            String turnId,
            AntigravityStreamEvent event) {
        if (event instanceof AntigravityStreamEvent.Init init) {
            synchronized (state) {
                if (init.conversationId() != null) {
                    state.antigravityConversationId = init.conversationId();
                }
                state.permissionMode = init.permissionMode();
            }
            emit(new AntigravityEvent.Initialized(
                    state.uiId, turnId, init.conversationId(), init.cwd(), init.permissionMode()));
            return;
        }
        if (event instanceof AntigravityStreamEvent.Step step) {
            handleStep(state, turnId, step);
            return;
        }
        if (event instanceof AntigravityStreamEvent.Result result) {
            completeResult(state, turnId, result);
        }
    }

    private void handleStep(ConversationState state, String turnId, AntigravityStreamEvent.Step step) {
        switch (step.type() == null ? "" : step.type()) {
            case "agent_response" -> {
                if (step.textDelta() != null && !step.textDelta().isEmpty()) {
                    String itemId = "assistant-" + turnId;
                    synchronized (state) {
                        state.assistantText.append(step.textDelta());
                        upsert(state, new AntigravityConversationItem(
                                itemId,
                                AntigravityConversationItem.Kind.ASSISTANT,
                                state.assistantText.toString(),
                                "inProgress",
                                null));
                    }
                    emit(new AntigravityEvent.AssistantTextDelta(
                            state.uiId, turnId, itemId, step.textDelta()));
                }
            }
            case "tool" -> handleTool(state, turnId, step);
            case "subagent" -> handleSubagent(state, turnId, step);
            default -> {
                if (step.subagentInfo() != null && !step.subagentInfo().isMissingNode()
                        && !step.subagentInfo().isNull()) {
                    handleSubagent(state, turnId, step);
                }
            }
        }
    }

    private void handleTool(ConversationState state, String turnId, AntigravityStreamEvent.Step step) {
        String itemId = "tool-" + turnId + "-" + step.index();
        String label = toolLabel(step);
        String details = toolDetails(step.toolInfo(), "ACTIVE".equalsIgnoreCase(step.state()));
        boolean mcp = isMcpTool(step);
        String status = "ACTIVE".equalsIgnoreCase(step.state()) ? "inProgress"
                : toolError(step.toolInfo()) == null ? "completed" : "failed";
        synchronized (state) {
            upsert(state, new AntigravityConversationItem(
                    itemId, AntigravityConversationItem.Kind.TOOL, label, status, details));
        }
        if ("ACTIVE".equalsIgnoreCase(step.state())) {
            emit(new AntigravityEvent.ToolStarted(state.uiId, turnId, itemId, label, details, mcp));
        } else {
            emit(new AntigravityEvent.ToolCompleted(
                    state.uiId, turnId, itemId, label, status, details, mcp));
        }
    }

    private void handleSubagent(ConversationState state, String turnId, AntigravityStreamEvent.Step step) {
        String itemId = "subagent-" + turnId + "-" + step.index();
        JsonNode info = step.subagentInfo();
        JsonNode firstSubagent = info.path("subagents").isArray() && !info.path("subagents").isEmpty()
                ? info.path("subagents").get(0)
                : info;
        String label = firstNonBlank(
                info.path("name").asText(null),
                info.path("agent_name").asText(null),
                firstSubagent.path("role").asText(null),
                firstSubagent.path("type_name").asText(null),
                "Antigravity subagent");
        String status = "ACTIVE".equalsIgnoreCase(step.state()) ? "inProgress" : "completed";
        String details = compact(info);
        synchronized (state) {
            upsert(state, new AntigravityConversationItem(
                    itemId, AntigravityConversationItem.Kind.SUBAGENT, label, status, details));
        }
        emit(new AntigravityEvent.SubagentUpdated(
                state.uiId, turnId, itemId, label, status, details));
    }

    private void completeResult(
            ConversationState state,
            String turnId,
            AntigravityStreamEvent.Result result) {
        String fallback = null;
        synchronized (state) {
            if (result.conversationId() != null) {
                state.antigravityConversationId = result.conversationId();
            }
            if (state.assistantText.isEmpty() && result.response() != null && !result.response().isBlank()) {
                fallback = result.response();
                state.assistantText.append(fallback);
                upsert(state, new AntigravityConversationItem(
                        "assistant-" + turnId,
                        AntigravityConversationItem.Kind.ASSISTANT,
                        fallback,
                        "completed",
                        null));
            } else {
                completeAssistantItem(state, turnId);
            }
            state.activeTurnId = null;
            state.activeHandle = null;
            state.updatedAt = Instant.now();
            state.assistantText = new StringBuilder();
        }
        emit(new AntigravityEvent.TurnCompleted(
                state.uiId,
                turnId,
                normalizeStatus(result.status()),
                fallback,
                result.error() == null ? null : friendlyMessage(null, result.error()),
                result.durationSeconds(),
                result.totalTokens()));
        log.info("Completed Antigravity prompt uiConversationId={} conversationId={} turnId={} status={}",
                state.uiId, result.conversationId(), turnId, result.status());
    }

    private void completeProcess(
            ConversationState state,
            String turnId,
            AntigravityProcessResult processResult,
            Throwable error) {
        if (processResult != null && processResult.resultReceived()) {
            return;
        }
        synchronized (state) {
            if (!turnId.equals(state.activeTurnId)) {
                return;
            }
            completeAssistantItem(state, turnId);
            state.activeTurnId = null;
            state.activeHandle = null;
            state.updatedAt = Instant.now();
            state.assistantText = new StringBuilder();
        }

        if (processResult != null && processResult.cancelled()) {
            emit(new AntigravityEvent.TurnCompleted(
                    state.uiId, turnId, "interrupted", null, null, 0, 0));
            return;
        }
        if (processResult != null && processResult.timedOut()) {
            emit(new AntigravityEvent.Failure(
                    state.uiId, turnId, "Antigravity exceeded the configured process timeout."));
            return;
        }
        String stderr = processResult == null ? null : processResult.stderr();
        String message = friendlyMessage(error, stderr);
        emit(new AntigravityEvent.Failure(state.uiId, turnId, message));
    }

    private AntigravityConversationSnapshot snapshot(ConversationState state) {
        synchronized (state) {
            return new AntigravityConversationSnapshot(
                    conversation(state), List.copyOf(state.items), state.activeTurnId, state.permissionMode);
        }
    }

    private AntigravityConversation conversation(ConversationState state) {
        synchronized (state) {
            return new AntigravityConversation(
                    state.uiId,
                    state.antigravityConversationId,
                    state.title,
                    state.preview,
                    state.updatedAt);
        }
    }

    private static void completeAssistantItem(ConversationState state, String turnId) {
        for (int i = 0; i < state.items.size(); i++) {
            AntigravityConversationItem item = state.items.get(i);
            if (item.id().equals("assistant-" + turnId)) {
                state.items.set(i, new AntigravityConversationItem(
                        item.id(), item.kind(), item.text(), "completed", item.details()));
                return;
            }
        }
    }

    private static void upsert(ConversationState state, AntigravityConversationItem replacement) {
        for (int i = 0; i < state.items.size(); i++) {
            if (state.items.get(i).id().equals(replacement.id())) {
                state.items.set(i, replacement);
                return;
            }
        }
        state.items.add(replacement);
    }

    private void emit(AntigravityEvent event) {
        List<Consumer<AntigravityEvent>> current = listeners.get(event.uiConversationId());
        if (current != null) {
            current.forEach(listener -> safelyAccept(listener, event));
        }
    }

    private void setAvailability(AntigravityAvailability value) {
        availability = value;
        availabilityListeners.forEach(listener -> safelyAccept(listener, value));
    }

    private static <T> void safelyAccept(Consumer<T> listener, T value) {
        try {
            listener.accept(value);
        } catch (RuntimeException ex) {
            log.warn("Antigravity listener failed", ex);
        }
    }

    private static String friendlyMessage(Throwable error, String stderr) {
        String detail = ((error == null ? "" : error.getMessage()) + "\n" + (stderr == null ? "" : stderr)).toLowerCase();
        if (detail.contains("not logged") || detail.contains("not authenticated") || detail.contains("authentication")) {
            return "Antigravity is not authenticated. Run `agy` in a terminal and complete Google sign-in.";
        }
        if (detail.contains("not trusted") || detail.contains("trust this workspace")) {
            return "Antigravity does not trust this workspace. Run `agy` here and approve the workspace.";
        }
        if (detail.contains("permission") && (detail.contains("denied") || detail.contains("not permitted"))) {
            return "Antigravity was not permitted to perform this action. Update its permission rules if appropriate.";
        }
        if (detail.contains("mcp")) {
            return "Antigravity could not use the configured application MCP server. Check that the application is running and its MCP URL is correct.";
        }
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        if (stderr != null && !stderr.isBlank()) {
            return stderr.strip().lines().findFirst().orElse("Antigravity reported an error");
        }
        return "The Antigravity process stopped before returning a result.";
    }

    private static String toolLabel(AntigravityStreamEvent.Step step) {
        JsonNode info = step.toolInfo();
        String name = firstNonBlank(step.toolName(), info.path("name").asText(null), "Antigravity tool");
        if ("call_mcp_tool".equals(name)) {
            JsonNode params = info.path("parameters");
            String tool = firstNonBlank(
                    params.path("tool_name").asText(null),
                    params.path("toolName").asText(null),
                    params.path("ToolName").asText(null),
                    params.path("name").asText(null));
            if (tool != null) {
                return "MCP: " + tool;
            }
        }
        return name;
    }

    private static boolean isMcpTool(AntigravityStreamEvent.Step step) {
        String name = firstNonBlank(step.toolName(), step.toolInfo().path("name").asText(null), "");
        return "call_mcp_tool".equals(name) || name.toLowerCase().contains("mcp");
    }

    private static String toolDetails(JsonNode info, boolean active) {
        JsonNode value = active ? info.path("parameters")
                : toolError(info) != null ? info.path("error") : info.path("output");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String message = value.path("message").asText(null);
        if (message != null && !message.isBlank()) {
            return abbreviate(message, MAX_DETAILS);
        }
        return value.isTextual() ? abbreviate(value.asText(), MAX_DETAILS) : abbreviate(value.toString(), MAX_DETAILS);
    }

    private static String toolError(JsonNode info) {
        JsonNode error = info.path("error");
        if (error.isMissingNode() || error.isNull()) {
            return null;
        }
        return error.isTextual() ? error.asText() : error.toString();
    }

    private static String compact(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                ? null
                : abbreviate(value.toString(), MAX_DETAILS);
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return "completed";
        }
        return switch (status.toUpperCase()) {
            case "SUCCESS" -> "completed";
            case "CANCELED", "CANCELLED", "INTERRUPTED" -> "interrupted";
            default -> status.toLowerCase();
        };
    }

    private static String title(String text) {
        return abbreviate(text.strip().replaceAll("\\s+", " "), 42);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).stripTrailing() + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static final class ConversationState {
        private final String uiId;
        private final List<AntigravityConversationItem> items = new ArrayList<>();
        private String antigravityConversationId;
        private String title = "New Antigravity chat";
        private String preview = "No messages yet";
        private Instant updatedAt = Instant.now();
        private String activeTurnId;
        private AntigravityTurnHandle activeHandle;
        private String permissionMode;
        private StringBuilder assistantText = new StringBuilder();
        private ConversationState(String uiId) {
            this.uiId = uiId;
        }
    }
}
