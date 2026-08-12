package com.github.fmaiassistent.copilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.UserMessageEvent;
import com.github.copilot.generated.rpc.PermissionRule;
import com.github.copilot.generated.rpc.PermissionsModifyRulesScope;
import com.github.copilot.generated.rpc.SessionPermissionsModifyRulesParams;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.UserInputRequest;
import com.github.copilot.rpc.UserInputResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class CopilotConversationService {
    private static final Logger log = LoggerFactory.getLogger(CopilotConversationService.class);
    private static final int MAX_DETAILS = 2_000;

    private final CopilotProperties properties;
    private final CopilotExecutableResolver executableResolver;
    private final Path workingDirectory;
    private final CopilotSdkEventMapper eventMapper = new CopilotSdkEventMapper();
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("copilot-lifecycle-", 0).factory());
    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<CopilotEvent>>> listeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<CopilotAvailability>> availabilityListeners =
            new CopyOnWriteArrayList<>();
    private volatile CopilotAvailability availability;
    private volatile CopilotClient client;
    private volatile List<CopilotModel> models = List.of();
    private volatile boolean stopping;

    CopilotConversationService(
            CopilotProperties properties,
            CopilotWorkspaceResolver workspaceResolver,
            CopilotExecutableResolver executableResolver) {
        this.properties = properties;
        this.workingDirectory = workspaceResolver.workingDirectory();
        this.executableResolver = executableResolver;
        availability = properties.enabled()
                ? new CopilotAvailability(CopilotAvailability.State.STARTING, "GitHub Copilot starting…", null, 0)
                : new CopilotAvailability(CopilotAvailability.State.DISABLED, "GitHub Copilot is disabled", null, 0);
    }

    @PostConstruct
    void initialize() {
        if (properties.enabled()) {
            lifecycleExecutor.execute(this::startRuntime);
        }
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        conversations.values().forEach(state -> {
            rejectPendingRequests(state);
            closeQuietly(state.eventSubscription);
            if (state.session != null) {
                state.session.close();
            }
        });
        CopilotClient current = client;
        if (current != null) {
            try {
                current.stop().get(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                log.warn("Copilot did not stop gracefully; forcing shutdown: {}", rootMessage(ex));
                try {
                    current.forceStop().get(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception forceError) {
                    log.warn("Could not force-stop Copilot runtime: {}", rootMessage(forceError));
                }
            }
        }
        lifecycleExecutor.shutdownNow();
    }

    public CopilotAvailability availability() {
        return availability;
    }

    public List<CopilotModel> models() {
        return models;
    }

    public CopilotSubscription subscribeAvailability(Consumer<CopilotAvailability> listener) {
        availabilityListeners.add(listener);
        return () -> availabilityListeners.remove(listener);
    }

    public CopilotSubscription subscribe(String sessionId, Consumer<CopilotEvent> listener) {
        listeners.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<CopilotEvent>> current = listeners.get(sessionId);
            if (current != null) {
                current.remove(listener);
                if (current.isEmpty()) {
                    listeners.remove(sessionId, current);
                }
            }
        };
    }

    public CompletableFuture<CopilotConversationSnapshot> newConversation(String model) {
        if (!availability.ready() || client == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(availability.message()));
        }
        String sessionId = UUID.randomUUID().toString();
        ConversationState state = new ConversationState(sessionId);
        state.selectedModel = blankToNull(model) == null ? properties.model() : model;
        conversations.put(sessionId, state);
        return createSession(state).thenApply(session -> snapshot(state)).exceptionallyCompose(error -> {
            conversations.remove(sessionId);
            return CompletableFuture.failedFuture(unwrap(error));
        });
    }

    public CompletableFuture<List<CopilotConversation>> listConversations() {
        return CompletableFuture.completedFuture(conversations.values().stream()
                .map(this::conversation)
                .sorted(Comparator.comparing(CopilotConversation::updatedAt).reversed())
                .toList());
    }

    public CompletableFuture<CopilotConversationSnapshot> openConversation(String sessionId) {
        ConversationState state = conversations.get(sessionId);
        if (state == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Copilot conversation was not found"));
        }
        synchronized (state) {
            if (state.activeTurnId != null) {
                return CompletableFuture.completedFuture(snapshot(state));
            }
        }
        return ensureResumed(state).thenCompose(session -> session.getMessages()).thenApply(events -> {
            synchronized (state) {
                // Copilot's persisted session events are authoritative while idle. Re-reading
                // them also repairs a view if a live SDK callback was delayed or missed.
                rebuildHistory(state, events);
                state.historyLoaded = true;
            }
            return snapshot(state);
        });
    }

    public CompletableFuture<String> sendMessage(String sessionId, String text) {
        ConversationState state = requireConversation(sessionId);
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Message cannot be empty"));
        }
        String turnId = UUID.randomUUID().toString();
        synchronized (state) {
            if (state.activeTurnId != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("A Copilot turn is already active"));
            }
            state.activeTurnId = turnId;
            state.assistantText.clear();
            state.updatedAt = Instant.now();
            if (state.items.isEmpty()) {
                state.title = title(text);
                state.preview = abbreviate(text.strip(), 140);
            }
            state.items.add(new CopilotConversationItem(
                    "user-" + turnId, CopilotConversationItem.Kind.USER, text, "completed", null));
        }
        emit(new CopilotEvent.TurnStarted(sessionId, turnId));
        return ensureResumed(state).thenCompose(session -> session.send(new MessageOptions().setPrompt(text)))
                .thenApply(ignored -> turnId)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        failTurn(state, turnId, rootMessage(error));
                    }
                });
    }

    public CompletableFuture<Void> interrupt(String sessionId) {
        ConversationState state = requireConversation(sessionId);
        CopilotSession session;
        synchronized (state) {
            if (state.activeTurnId == null || state.session == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No GitHub Copilot turn is active"));
            }
            session = state.session;
        }
        return session.abort();
    }

    public CompletableFuture<Void> selectModel(String sessionId, String model) {
        ConversationState state = requireConversation(sessionId);
        String selected = blankToNull(model);
        state.selectedModel = selected;
        if (state.session == null || selected == null) {
            return CompletableFuture.completedFuture(null);
        }
        return state.session.setModel(selected);
    }

    public void resolvePermission(String sessionId, String requestId, boolean allow) {
        ConversationState state = requireConversation(sessionId);
        PendingPermission pending = state.permissions.remove(requestId);
        if (pending != null) {
            pending.decision().complete(allow
                    ? PermissionRequestResult.approveOnce()
                    : PermissionRequestResult.reject("Denied by user"));
        }
    }

    public CompletableFuture<Void> alwaysAllowApplicationMcpTool(String sessionId, String requestId) {
        ConversationState state = requireConversation(sessionId);
        PendingPermission pending = state.permissions.get(requestId);
        if (pending == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Permission request is no longer active"));
        }
        if (!pending.applicationMcp() || pending.mcpToolName() == null || state.session == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Persistent approval is only available for fm-ai-assistent MCP tools"));
        }
        PermissionRule rule = new CopilotMcpPermission("fm-ai-assistent", pending.mcpToolName()).rule();
        return state.session.getRpc().permissions.modifyRules(new SessionPermissionsModifyRulesParams(
                        state.sessionId, PermissionsModifyRulesScope.LOCATION, List.of(rule), null, false))
                .thenRun(() -> {
                    if (state.permissions.remove(requestId, pending)) {
                        pending.decision().complete(PermissionRequestResult.approveOnce());
                        log.info("Persisted Copilot MCP permission server=fm-ai-assistent tool={} cwd={}",
                                pending.mcpToolName(), workingDirectory);
                    }
                });
    }

    public void answerUserInput(String sessionId, String requestId, String answer, boolean freeform) {
        ConversationState state = requireConversation(sessionId);
        CompletableFuture<UserInputResponse> pending = state.userInputs.remove(requestId);
        if (pending != null) {
            pending.complete(new UserInputResponse().setAnswer(answer).setWasFreeform(freeform));
        }
    }

    public void dismissPendingUi(String sessionId) {
        ConversationState state = conversations.get(sessionId);
        if (state != null) {
            rejectPendingRequests(state);
        }
    }

    private void startRuntime() {
        String executable = executableResolver.resolve();
        if (executable == null) {
            setAvailability(new CopilotAvailability(CopilotAvailability.State.UNAVAILABLE,
                    "GitHub Copilot CLI unavailable · `" + properties.executable() + "` was not found", null, 0));
            return;
        }
        try {
            CopilotClientOptions options = new CopilotClientOptions()
                    .setCliPath(executable)
                    .setCwd(workingDirectory.toString())
                    .setEnvironment(cliEnvironment(executable))
                    .setUseLoggedInUser(true)
                    .setUseStdio(true)
                    .setAutoRestart(false);
            CopilotClient started = new CopilotClient(options);
            client = started;
            started.start().get(properties.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
            var status = started.getStatus().get(properties.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
            var auth = started.getAuthStatus().get(properties.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!auth.isAuthenticated()) {
                setAvailability(new CopilotAvailability(CopilotAvailability.State.AUTHENTICATION_REQUIRED,
                        "GitHub Copilot is not authenticated · run `copilot login`", status.getVersion(),
                        status.getProtocolVersion()));
                return;
            }
            try {
                models = started.listModels().get(properties.startupTimeout().toMillis(), TimeUnit.MILLISECONDS).stream()
                        .map(model -> new CopilotModel(model.getId(), model.getName(),
                                model.getSupportedReasoningEfforts() == null
                                        ? List.of()
                                        : model.getSupportedReasoningEfforts(),
                                model.getDefaultReasoningEffort()))
                        .toList();
            } catch (Exception ex) {
                log.warn("Copilot model discovery failed; default model remains usable: {}", rootMessage(ex));
            }
            try {
                loadSessionMetadata(started.listSessions().get(
                        properties.startupTimeout().toMillis(), TimeUnit.MILLISECONDS));
            } catch (Exception ex) {
                log.warn("Copilot session discovery failed; new conversations remain usable: {}", rootMessage(ex));
            }
            setAvailability(new CopilotAvailability(CopilotAvailability.State.READY,
                    "GitHub Copilot ready", status.getVersion(), status.getProtocolVersion()));
            log.info("GitHub Copilot ready cliVersion={} protocol={} cwd={}",
                    status.getVersion(), status.getProtocolVersion(), workingDirectory);
        } catch (Exception ex) {
            if (!stopping) {
                String message = rootMessage(ex);
                setAvailability(new CopilotAvailability(CopilotAvailability.State.ERROR,
                        friendlyStartupError(message), null, 0));
                log.error("Could not start GitHub Copilot SDK runtime", unwrap(ex));
            }
        }
    }

    private CompletableFuture<CopilotSession> createSession(ConversationState state) {
        SessionConfig config = new SessionConfig()
                .setSessionId(state.sessionId)
                .setClientName("fm-ai-assistent")
                .setWorkingDirectory(workingDirectory.toString())
                .setStreaming(true)
                .setEnableSessionStore(true)
                .setEnableConfigDiscovery(true)
                .setIncludeSubAgentStreamingEvents(true)
                .setOnPermissionRequest((request, invocation) -> requestPermission(state, request))
                .setOnUserInputRequest((request, invocation) -> requestUserInput(state, request));
        if (state.selectedModel != null) {
            config.setModel(state.selectedModel);
        }
        if (properties.reasoningEffort() != null) {
            config.setReasoningEffort(properties.reasoningEffort());
        }
        return client.createSession(config).thenApply(session -> attachSession(state, session));
    }

    private CompletableFuture<CopilotSession> ensureResumed(ConversationState state) {
        if (state.session != null) {
            return CompletableFuture.completedFuture(state.session);
        }
        ResumeSessionConfig config = new ResumeSessionConfig()
                .setClientName("fm-ai-assistent")
                .setWorkingDirectory(workingDirectory.toString())
                .setStreaming(true)
                .setEnableSessionStore(true)
                .setEnableConfigDiscovery(true)
                .setIncludeSubAgentStreamingEvents(true)
                .setOnPermissionRequest((request, invocation) -> requestPermission(state, request))
                .setOnUserInputRequest((request, invocation) -> requestUserInput(state, request));
        if (state.selectedModel != null) {
            config.setModel(state.selectedModel);
        }
        return client.resumeSession(state.sessionId, config).thenApply(session -> attachSession(state, session));
    }

    private CopilotSession attachSession(ConversationState state, CopilotSession session) {
        synchronized (state) {
            if (state.eventSubscription != null) {
                closeQuietly(state.eventSubscription);
            }
            state.session = session;
            state.eventSubscription = session.on(event -> handleSdkEvent(state, event));
        }
        return session;
    }

    private CompletableFuture<PermissionRequestResult> requestPermission(
            ConversationState state, PermissionRequest request) {
        String id = firstNonBlank(request.getToolCallId(), UUID.randomUUID().toString());
        CompletableFuture<PermissionRequestResult> decision = new CompletableFuture<>();
        CopilotMcpPermission mcpPermission = CopilotMcpPermission.applicationTool(
                request, state.toolNames.get(request.getToolCallId())).orElse(null);
        boolean applicationMcp = mcpPermission != null;
        String toolName = applicationMcp ? mcpPermission.toolName() : null;
        PendingPermission pending = new PendingPermission(decision, applicationMcp,
                applicationMcp ? toolName : null);
        state.permissions.put(id, pending);
        decision.completeOnTimeout(PermissionRequestResult.userNotAvailable(),
                        properties.permissionTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> state.permissions.remove(id, pending));
        emit(new CopilotEvent.PermissionRequested(
                state.sessionId, id, firstNonBlank(request.getKind(), "tool"), permissionDescription(request),
                applicationMcp, applicationMcp ? toolName : null));
        return decision;
    }

    private CompletableFuture<UserInputResponse> requestUserInput(
            ConversationState state, UserInputRequest request) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<UserInputResponse> answer = new CompletableFuture<>();
        state.userInputs.put(id, answer);
        answer.completeOnTimeout(new UserInputResponse().setAnswer("").setWasFreeform(false),
                        properties.permissionTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> state.userInputs.remove(id, answer));
        emit(new CopilotEvent.UserInputRequested(state.sessionId, id,
                firstNonBlank(request.getQuestion(), "Copilot needs more information"),
                request.getChoices() == null ? List.of() : List.copyOf(request.getChoices()),
                request.getAllowFreeform().orElse(true)));
        return answer;
    }

    private void handleSdkEvent(ConversationState state, SessionEvent event) {
        String turnId = state.activeTurnId;
        if (turnId == null) {
            log.trace("Ignoring Copilot session event without active turn type={}", event.getType());
            return;
        }
        CopilotSdkEventMapper.MappedEvent mapped = eventMapper.map(event, state.toolNames).orElse(null);
        if (mapped instanceof CopilotSdkEventMapper.TextDelta delta) {
            String addition;
            synchronized (state) {
                addition = state.assistantText.appendDelta(delta.messageId(), delta.text());
                upsert(state, new CopilotConversationItem("assistant-" + turnId,
                        CopilotConversationItem.Kind.ASSISTANT, state.assistantText.text(), "inProgress", null));
            }
            emit(new CopilotEvent.TextDelta(state.sessionId, turnId, "assistant-" + turnId, addition));
            return;
        }
        if (mapped instanceof CopilotSdkEventMapper.FinalText finalText) {
            String addition;
            synchronized (state) {
                addition = state.assistantText.appendFinal(finalText.messageId(), finalText.text());
                if (!addition.isEmpty()) {
                    upsert(state, new CopilotConversationItem("assistant-" + turnId,
                            CopilotConversationItem.Kind.ASSISTANT, state.assistantText.text(), "inProgress", null));
                }
            }
            if (!addition.isEmpty()) {
                emit(new CopilotEvent.TextDelta(
                        state.sessionId, turnId, "assistant-" + turnId, addition));
            }
            return;
        }
        if (mapped instanceof CopilotSdkEventMapper.ToolStarted started) {
            synchronized (state) {
                upsert(state, new CopilotConversationItem("tool-" + started.id(),
                        CopilotConversationItem.Kind.TOOL, started.name(), "inProgress", started.details()));
            }
            emit(new CopilotEvent.ToolStarted(
                    state.sessionId, turnId, "tool-" + started.id(), started.name(), started.details(), started.mcp()));
            return;
        }
        if (mapped instanceof CopilotSdkEventMapper.ToolCompleted completed) {
            synchronized (state) {
                upsert(state, new CopilotConversationItem("tool-" + completed.id(),
                        CopilotConversationItem.Kind.TOOL, completed.name(), completed.status(), completed.details()));
            }
            emit(new CopilotEvent.ToolCompleted(
                    state.sessionId, turnId, "tool-" + completed.id(), completed.name(), completed.status(),
                    completed.details(), completed.mcp()));
            return;
        }
        if (mapped instanceof CopilotSdkEventMapper.TurnIdle idle) {
            completeTurn(state, turnId, idle.interrupted(), null);
            return;
        }
        if (mapped instanceof CopilotSdkEventMapper.Failure failed) {
            failTurn(state, turnId, failed.message());
            return;
        }
        log.trace("Ignoring Copilot session event type={}", event.getType());
    }

    private void completeTurn(ConversationState state, String turnId, boolean interrupted, String fallback) {
        synchronized (state) {
            if (!turnId.equals(state.activeTurnId)) {
                return;
            }
            if (fallback != null && state.assistantText.isEmpty()) {
                state.assistantText.appendFinal("fallback-" + turnId, fallback);
                upsert(state, new CopilotConversationItem("assistant-" + turnId,
                        CopilotConversationItem.Kind.ASSISTANT, fallback, "completed", null));
            } else {
                markCompleted(state, "assistant-" + turnId);
            }
            state.activeTurnId = null;
            state.updatedAt = Instant.now();
        }
        emit(new CopilotEvent.TurnCompleted(state.sessionId, turnId, interrupted, fallback));
    }

    private void failTurn(ConversationState state, String turnId, String message) {
        synchronized (state) {
            if (!turnId.equals(state.activeTurnId)) {
                return;
            }
            state.activeTurnId = null;
            state.updatedAt = Instant.now();
            state.items.add(new CopilotConversationItem("error-" + turnId,
                    CopilotConversationItem.Kind.SYSTEM, message, "failed", null));
        }
        emit(new CopilotEvent.Failure(state.sessionId, turnId, message));
    }

    private void rebuildHistory(ConversationState state, List<SessionEvent> events) {
        state.items.clear();
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (SessionEvent event : events) {
            if (event instanceof UserMessageEvent user && user.getData() != null) {
                state.items.add(new CopilotConversationItem(eventId(event), CopilotConversationItem.Kind.USER,
                        user.getData().content(), "completed", null));
            } else if (event instanceof AssistantMessageEvent assistant && assistant.getData() != null
                    && assistant.getData().content() != null && !assistant.getData().content().isBlank()) {
                state.items.add(new CopilotConversationItem(eventId(event), CopilotConversationItem.Kind.ASSISTANT,
                        assistant.getData().content(), "completed", null));
            } else if (event instanceof ToolExecutionStartEvent tool && tool.getData() != null) {
                String id = firstNonBlank(tool.getData().toolCallId(), eventId(event));
                String name = CopilotSdkEventMapper.toolName(
                        tool.getData().toolName(), tool.getData().mcpServerName(), tool.getData().mcpToolName());
                toolNames.put(id, name);
                upsert(state, new CopilotConversationItem("tool-" + id, CopilotConversationItem.Kind.TOOL,
                        name, "inProgress", abbreviate(String.valueOf(tool.getData().arguments()), MAX_DETAILS)));
            } else if (event instanceof ToolExecutionCompleteEvent tool && tool.getData() != null) {
                String id = firstNonBlank(tool.getData().toolCallId(), eventId(event));
                upsert(state, new CopilotConversationItem("tool-" + id, CopilotConversationItem.Kind.TOOL,
                        toolNames.getOrDefault(id, "Copilot tool"),
                        Boolean.TRUE.equals(tool.getData().success()) ? "completed" : "failed", null));
            }
        }
    }

    private void loadSessionMetadata(List<SessionMetadata> metadata) {
        for (SessionMetadata session : metadata) {
            if (session.getSessionId() == null || session.getSessionId().isBlank()) {
                continue;
            }
            conversations.computeIfAbsent(session.getSessionId(), id -> {
                ConversationState state = new ConversationState(id);
                state.title = firstNonBlank(session.getSummary(), "Copilot conversation");
                state.preview = state.title;
                state.updatedAt = parseInstant(session.getModifiedTime());
                state.historyLoaded = false;
                return state;
            });
        }
    }

    private void rejectPendingRequests(ConversationState state) {
        state.permissions.values().forEach(pending ->
                pending.decision().complete(PermissionRequestResult.userNotAvailable()));
        state.permissions.clear();
        state.userInputs.values().forEach(future -> future.complete(
                new UserInputResponse().setAnswer("").setWasFreeform(false)));
        state.userInputs.clear();
    }

    private ConversationState requireConversation(String sessionId) {
        ConversationState state = conversations.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Copilot conversation was not found");
        }
        return state;
    }

    private CopilotConversationSnapshot snapshot(ConversationState state) {
        synchronized (state) {
            return new CopilotConversationSnapshot(
                    conversation(state), List.copyOf(state.items), state.activeTurnId, state.selectedModel);
        }
    }

    private CopilotConversation conversation(ConversationState state) {
        return new CopilotConversation(state.sessionId, state.title, state.preview, state.updatedAt);
    }

    private void emit(CopilotEvent event) {
        for (Consumer<CopilotEvent> listener : listeners.getOrDefault(
                event.sessionId(), new CopyOnWriteArrayList<>())) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                log.warn("Copilot conversation listener failed", ex);
            }
        }
    }

    private void setAvailability(CopilotAvailability value) {
        availability = value;
        availabilityListeners.forEach(listener -> listener.accept(value));
    }

    private static void upsert(ConversationState state, CopilotConversationItem item) {
        for (int i = 0; i < state.items.size(); i++) {
            if (state.items.get(i).id().equals(item.id())) {
                state.items.set(i, item);
                return;
            }
        }
        state.items.add(item);
    }

    private static void markCompleted(ConversationState state, String itemId) {
        for (int i = 0; i < state.items.size(); i++) {
            CopilotConversationItem item = state.items.get(i);
            if (item.id().equals(itemId)) {
                state.items.set(i, new CopilotConversationItem(
                        item.id(), item.kind(), item.text(), "completed", item.details()));
                return;
            }
        }
    }

    private static String permissionDescription(PermissionRequest request) {
        String details = request.getExtensionData() == null ? "" : request.getExtensionData().toString();
        return abbreviate(firstNonBlank(details, request.getKind(), "Copilot tool request"), MAX_DETAILS);
    }

    private static String friendlyStartupError(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("auth") || lower.contains("login") || lower.contains("credential")) {
            return "GitHub Copilot is not authenticated · run `copilot login`";
        }
        if (lower.contains("protocol") || lower.contains("incompatible")) {
            return "GitHub Copilot CLI and Java SDK are incompatible · " + message;
        }
        return "GitHub Copilot unavailable · " + message;
    }

    private static Map<String, String> cliEnvironment(String executable) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        Path executableDirectory = Path.of(executable).toAbsolutePath().normalize().getParent();
        String inheritedPath = environment.getOrDefault("PATH", "");
        String path = executableDirectory + (inheritedPath.isBlank()
                ? ""
                : java.io.File.pathSeparator + inheritedPath);
        environment.put("PATH", path);
        return environment;
    }

    private static String title(String text) {
        String singleLine = text.strip().replaceAll("\\s+", " ");
        return abbreviate(singleLine, 42);
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static String eventId(SessionEvent event) {
        return event.getId() == null ? UUID.randomUUID().toString() : event.getId().toString();
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while ((result instanceof java.util.concurrent.CompletionException
                || result instanceof java.util.concurrent.ExecutionException) && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = unwrap(error);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Subscription cleanup is best effort.
        }
    }

    private static final class ConversationState {
        private final String sessionId;
        private final List<CopilotConversationItem> items = new ArrayList<>();
        private final CopilotAssistantTextAccumulator assistantText = new CopilotAssistantTextAccumulator();
        private final Map<String, String> toolNames = new ConcurrentHashMap<>();
        private final Map<String, PendingPermission> permissions = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<UserInputResponse>> userInputs = new ConcurrentHashMap<>();
        private volatile CopilotSession session;
        private volatile Closeable eventSubscription;
        private volatile String activeTurnId;
        private volatile String selectedModel;
        private volatile String title = "New Copilot chat";
        private volatile String preview = "No messages yet";
        private volatile Instant updatedAt = Instant.now();
        private volatile boolean historyLoaded = true;

        private ConversationState(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private record PendingPermission(
            CompletableFuture<PermissionRequestResult> decision,
            boolean applicationMcp,
            String mcpToolName) { }
}
