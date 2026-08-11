package com.github.fmaiassistent.codex;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class CodexConversationService {
    private static final Logger log = LoggerFactory.getLogger(CodexConversationService.class);

    private final CodexAppServerClient client;
    private final CodexProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, CopyOnWriteArrayList<Consumer<CodexEvent>>> listeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<CodexAvailability>> availabilityListeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> activeTurns = new ConcurrentHashMap<>();
    private final Set<String> completedTurns = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();

    private volatile CodexAvailability availability;
    private volatile String activeLoginId;

    CodexConversationService(CodexAppServerClient client, CodexProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        availability = properties.enabled()
                ? new CodexAvailability(CodexAvailability.State.STARTING, "Codex is starting")
                : new CodexAvailability(CodexAvailability.State.DISABLED, "Codex integration is disabled");
        client.onNotification(this::handleNotification);
        client.onServerRequest(this::handleServerRequest);
        client.onFailure(this::handleTransportFailure);
    }

    @PostConstruct
    void start() {
        if (!properties.enabled()) {
            return;
        }
        Thread.ofVirtual().name("codex-app-server-startup").start(() -> beginStartup(client::start));
    }

    public CodexAvailability availability() {
        return availability;
    }

    public CodexSubscription subscribeAvailability(Consumer<CodexAvailability> listener) {
        availabilityListeners.add(listener);
        return () -> availabilityListeners.remove(listener);
    }

    public CodexSubscription subscribe(String threadId, Consumer<CodexEvent> listener) {
        listeners.computeIfAbsent(threadId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<CodexEvent>> threadListeners = listeners.get(threadId);
            if (threadListeners != null) {
                threadListeners.remove(listener);
                if (threadListeners.isEmpty()) {
                    listeners.remove(threadId, threadListeners);
                }
            }
        };
    }

    public CompletableFuture<CodexConversationSnapshot> newConversation() {
        return readyThen(client::startThread)
                .thenApply(result -> snapshot(result.path("thread")));
    }

    public CompletableFuture<List<CodexConversation>> listConversations() {
        return readyThen(client::listThreads).thenApply(result -> {
            List<CodexConversation> conversations = new ArrayList<>();
            result.path("data").forEach(thread -> conversations.add(conversation(thread)));
            return List.copyOf(conversations);
        });
    }

    public CompletableFuture<CodexConversationSnapshot> openConversation(String threadId) {
        return readyThen(() -> client.resumeThread(threadId))
                .thenCompose(ignored -> client.readThread(threadId))
                .thenApply(result -> snapshot(result.path("thread")));
    }

    public CompletableFuture<String> sendMessage(String threadId, String text) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Message cannot be empty"));
        }
        String messageId = UUID.randomUUID().toString();
        String reservation = "starting:" + messageId;
        if (activeTurns.putIfAbsent(threadId, reservation) != null) {
            return CompletableFuture.failedFuture(new CodexException("A turn is already active in this conversation"));
        }
        CompletableFuture<String> turn = readyThen(() -> client.startTurn(threadId, text, messageId)).thenApply(result -> {
            String turnId = result.path("turn").path("id").asText();
            if (completedTurns.remove(turnId)) {
                activeTurns.remove(threadId, reservation);
            } else {
                activeTurns.compute(threadId, (ignored, current) -> reservation.equals(current) ? turnId : current);
            }
            log.info("Started Codex turn threadId={} turnId={}", threadId, turnId);
            return turnId;
        });
        turn.whenComplete((ignored, error) -> {
            if (error != null) {
                activeTurns.remove(threadId, reservation);
            }
        });
        return turn;
    }

    public CompletableFuture<Void> interrupt(String threadId) {
        String turnId = activeTurns.get(threadId);
        if (turnId == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (turnId.startsWith("starting:")) {
            return CompletableFuture.failedFuture(new CodexException("The Codex turn is still starting"));
        }
        return client.interruptTurn(threadId, turnId).thenApply(ignored -> {
            log.info("Interrupted Codex turn threadId={} turnId={}", threadId, turnId);
            return null;
        });
    }

    public void decideApproval(String requestKey, ApprovalDecision decision) {
        PendingApproval pending = approvals.remove(requestKey);
        if (pending == null) {
            throw new CodexException("This approval request is no longer active");
        }
        ObjectNode result;
        if ("item/permissions/requestApproval".equals(pending.method())) {
            JsonNode granted = decision == ApprovalDecision.ALLOW_ONCE
                    || decision == ApprovalDecision.ALLOW_SESSION
                    ? pending.params().path("permissions")
                    : mapper.createObjectNode();
            result = mapper.createObjectNode()
                    .put("scope", decision == ApprovalDecision.ALLOW_SESSION ? "session" : "turn")
                    .set("permissions", granted);
        } else {
            result = mapper.createObjectNode().put("decision", switch (decision) {
                case ALLOW_ONCE -> "accept";
                case ALLOW_SESSION -> "acceptForSession";
                case DENY -> "decline";
                case DENY_AND_STOP -> "cancel";
            });
        }
        client.respond(pending.id(), result);
        if (decision == ApprovalDecision.DENY_AND_STOP
                && "item/permissions/requestApproval".equals(pending.method())) {
            client.interruptTurn(pending.threadId(), pending.params().path("turnId").asText());
        }
        log.info("Resolved Codex approval method={} decision={} threadId={}",
                pending.method(), decision, pending.threadId());
    }

    public CompletableFuture<Void> restart() {
        setAvailability(new CodexAvailability(CodexAvailability.State.STARTING, "Codex is restarting"));
        CompletableFuture<Void> restart = invoke(client::restart)
                .thenCompose(ignored -> client.account())
                .thenAccept(this::accountReady);
        restart.whenComplete((ignored, error) -> {
            if (error != null) {
                handleStartupFailure(unwrap(error));
            }
        });
        return restart;
    }

    public CompletableFuture<CodexLogin> startChatGptLogin() {
        setAvailability(new CodexAvailability(
                CodexAvailability.State.AUTHENTICATING,
                "Waiting for ChatGPT sign-in"));
        CompletableFuture<CodexLogin> login = invoke(client::startChatGptLogin).thenApply(response -> {
            String loginId = response.path("loginId").asText();
            String authUrl = response.path("authUrl").asText();
            if (loginId.isBlank() || authUrl.isBlank()) {
                throw new CodexException("Codex did not return a ChatGPT sign-in URL");
            }
            activeLoginId = loginId;
            return new CodexLogin(loginId, authUrl);
        });
        login.whenComplete((ignored, error) -> {
            if (error != null) {
                setAuthenticationRequired("Could not start ChatGPT sign-in: " + safeMessage(unwrap(error)));
            }
        });
        return login;
    }

    public CompletableFuture<Void> cancelLogin(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return invoke(() -> client.cancelLogin(loginId)).thenAccept(ignored -> {
            activeLoginId = null;
            setAuthenticationRequired("Sign in with ChatGPT to use Codex.");
        });
    }

    public CompletableFuture<Void> refreshAccount() {
        return invoke(client::account).thenAccept(this::accountReady);
    }

    public enum ApprovalDecision {
        ALLOW_ONCE,
        ALLOW_SESSION,
        DENY,
        DENY_AND_STOP
    }

    private <T> CompletableFuture<T> readyThen(Supplier<CompletableFuture<T>> operation) {
        if (!availability.ready()) {
            return CompletableFuture.failedFuture(new CodexException(availability.message()));
        }
        return invoke(operation);
    }

    private void beginStartup(Supplier<CompletableFuture<JsonNode>> startup) {
        invoke(startup)
                .thenCompose(ignored -> client.account())
                .thenAccept(this::accountReady)
                .exceptionally(error -> {
                    handleStartupFailure(unwrap(error));
                    return null;
                });
    }

    private static <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private void accountReady(JsonNode response) {
        JsonNode account = response.path("account");
        if (account.isMissingNode() || account.isNull()) {
            setAuthenticationRequired("Codex is not authenticated. Sign in with ChatGPT to continue.");
            return;
        }
        String type = account.path("type").asText();
        if (!"chatgpt".equals(type)) {
            setAuthenticationRequired("Codex is not signed in with ChatGPT. Sign in to continue.");
            return;
        }
        activeLoginId = null;
        String plan = account.path("planType").asText("");
        setAvailability(new CodexAvailability(CodexAvailability.State.READY,
                plan.isBlank() ? "Codex ready" : "Codex ready · " + plan));
    }

    private void setAuthenticationRequired(String message) {
        setAvailability(new CodexAvailability(CodexAvailability.State.AUTHENTICATION_REQUIRED, message));
    }

    private void handleStartupFailure(Throwable error) {
        String message = safeMessage(error);
        CodexAvailability.State state = message.contains("not installed")
                ? CodexAvailability.State.UNAVAILABLE
                : CodexAvailability.State.ERROR;
        setAvailability(new CodexAvailability(state, message));
    }

    private void handleTransportFailure(Throwable error) {
        String message = safeMessage(error);
        setAvailability(new CodexAvailability(CodexAvailability.State.ERROR, message));
        activeTurns.clear();
        completedTurns.clear();
        listeners.forEach((threadId, threadListeners) ->
                emit(new CodexEvent.Failure(threadId, message)));
    }

    private void setAvailability(CodexAvailability value) {
        availability = value;
        availabilityListeners.forEach(listener -> safelyAccept(listener, value));
    }

    private void handleNotification(CodexJsonRpcClient.Notification notification) {
        JsonNode params = notification.params();
        String threadId = params.path("threadId").asText(null);
        switch (notification.method()) {
            case "account/login/completed" -> handleLoginCompleted(params);
            case "account/updated" -> refreshAccount().exceptionally(error -> {
                handleStartupFailure(unwrap(error));
                return null;
            });
            case "turn/started" -> {
                String turnId = params.path("turn").path("id").asText();
                activeTurns.put(threadId, turnId);
                emit(new CodexEvent.TurnStarted(threadId, turnId));
            }
            case "item/started" -> emitItemStarted(threadId, params.path("turnId").asText(), params.path("item"));
            case "item/agentMessage/delta" -> emit(new CodexEvent.AssistantTextDelta(
                    threadId,
                    params.path("turnId").asText(),
                    params.path("itemId").asText(),
                    params.path("delta").asText()));
            case "item/completed" -> emitItemCompleted(threadId, params.path("turnId").asText(), params.path("item"));
            case "turn/completed" -> emitTurnCompleted(threadId, params.path("turn"));
            case "mcpServer/startupStatus/updated" -> emit(new CodexEvent.McpStatusChanged(
                    threadId,
                    params.path("name").asText(),
                    params.path("status").asText(),
                    params.path("error").asText(null)));
            case "error" -> {
                String message = params.path("error").path("message").asText("Codex reported an error");
                if (threadId != null) {
                    emit(new CodexEvent.Failure(threadId, message));
                }
            }
            default -> log.trace("Ignoring Codex notification method={}", notification.method());
        }
    }

    private void handleLoginCompleted(JsonNode params) {
        String loginId = params.path("loginId").asText(null);
        if (activeLoginId != null && loginId != null && !activeLoginId.equals(loginId)) {
            log.debug("Ignoring completion for an older Codex login loginId={}", loginId);
            return;
        }
        if (!params.path("success").asBoolean(false)) {
            activeLoginId = null;
            setAuthenticationRequired(params.path("error").asText("ChatGPT sign-in was not completed."));
            return;
        }
        refreshAccount().exceptionally(error -> {
            handleStartupFailure(unwrap(error));
            return null;
        });
    }

    private void emitItemStarted(String threadId, String turnId, JsonNode item) {
        String type = item.path("type").asText();
        String itemId = item.path("id").asText();
        if ("agentMessage".equals(type)) {
            emit(new CodexEvent.AssistantStarted(threadId, turnId, itemId));
        } else if (isTool(type)) {
            emit(new CodexEvent.ToolStarted(threadId, turnId, itemId, toolLabel(item), toolDetails(item)));
        }
    }

    private void emitItemCompleted(String threadId, String turnId, JsonNode item) {
        String type = item.path("type").asText();
        String itemId = item.path("id").asText();
        if ("agentMessage".equals(type)) {
            emit(new CodexEvent.AssistantCompleted(
                    threadId, turnId, itemId, item.path("text").asText()));
        } else if (isTool(type)) {
            emit(new CodexEvent.ToolCompleted(
                    threadId,
                    turnId,
                    itemId,
                    toolLabel(item),
                    item.path("status").asText("completed"),
                    toolDetails(item)));
        }
    }

    private void emitTurnCompleted(String threadId, JsonNode turn) {
        String turnId = turn.path("id").asText();
        activeTurns.computeIfPresent(threadId, (ignored, current) -> {
            if (current.startsWith("starting:")) {
                completedTurns.add(turnId);
                return null;
            }
            return current.equals(turnId) ? null : current;
        });
        String status = turn.path("status").asText("completed");
        String error = turn.path("error").path("message").asText(null);
        emit(new CodexEvent.TurnCompleted(threadId, turnId, status, error));
        log.info("Completed Codex turn threadId={} turnId={} status={}", threadId, turnId, status);
    }

    private void handleServerRequest(CodexJsonRpcClient.ServerRequest request) {
        JsonNode params = request.params();
        String method = request.method();
        CodexEvent.ApprovalKind kind;
        String summary;
        if ("item/commandExecution/requestApproval".equals(method)) {
            kind = CodexEvent.ApprovalKind.COMMAND;
            summary = params.path("command").asText("Codex wants to run a command");
        } else if ("item/fileChange/requestApproval".equals(method)) {
            kind = CodexEvent.ApprovalKind.FILE_CHANGE;
            summary = "Codex wants to modify files";
        } else if ("item/permissions/requestApproval".equals(method)) {
            kind = CodexEvent.ApprovalKind.PERMISSIONS;
            summary = "Codex requests additional permissions";
        } else {
            client.respondError(request.id(), -32601,
                    "This client does not support the app-server request: " + method);
            log.warn("Safely rejected unsupported Codex server request method={}", method);
            return;
        }
        String threadId = params.path("threadId").asText();
        String requestKey = request.id().toString();
        String details = approvalDetails(params);
        approvals.put(requestKey, new PendingApproval(request.id(), method, threadId, params));
        emit(new CodexEvent.ApprovalRequested(
                threadId,
                params.path("turnId").asText(),
                requestKey,
                kind,
                summary,
                details));
    }

    private CodexConversationSnapshot snapshot(JsonNode thread) {
        List<CodexConversationItem> items = new ArrayList<>();
        String activeTurnId = null;
        for (JsonNode turn : thread.path("turns")) {
            if ("inProgress".equals(turn.path("status").asText())) {
                activeTurnId = turn.path("id").asText();
            }
            for (JsonNode item : turn.path("items")) {
                CodexConversationItem converted = historyItem(item);
                if (converted != null) {
                    items.add(converted);
                }
            }
        }
        String threadId = thread.path("id").asText();
        if (activeTurnId != null) {
            activeTurns.put(threadId, activeTurnId);
        }
        return new CodexConversationSnapshot(conversation(thread), List.copyOf(items), activeTurnId);
    }

    private CodexConversationItem historyItem(JsonNode item) {
        String type = item.path("type").asText();
        String id = item.path("id").asText(UUID.randomUUID().toString());
        return switch (type) {
            case "userMessage" -> new CodexConversationItem(
                    id, CodexConversationItem.Kind.USER, userText(item), "completed", null);
            case "agentMessage" -> new CodexConversationItem(
                    id, CodexConversationItem.Kind.ASSISTANT, item.path("text").asText(), "completed", null);
            case "mcpToolCall", "commandExecution", "fileChange", "dynamicToolCall", "webSearch" ->
                    new CodexConversationItem(
                            id,
                            CodexConversationItem.Kind.TOOL,
                            toolLabel(item),
                            item.path("status").asText("completed"),
                            toolDetails(item));
            default -> null;
        };
    }

    private CodexConversation conversation(JsonNode thread) {
        String preview = thread.path("preview").asText("");
        String name = thread.path("name").asText("");
        String title = !name.isBlank() ? name : firstLine(preview);
        if (title.isBlank()) {
            title = "New conversation";
        }
        return new CodexConversation(
                thread.path("id").asText(),
                abbreviate(title, 55),
                abbreviate(preview, 120),
                Instant.ofEpochSecond(thread.path("updatedAt").asLong(0)));
    }

    private static String userText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : item.path("content")) {
            if ("text".equals(content.path("type").asText())) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(content.path("text").asText());
            }
        }
        return text.toString();
    }

    private static boolean isTool(String type) {
        return switch (type) {
            case "mcpToolCall", "commandExecution", "fileChange", "dynamicToolCall", "webSearch" -> true;
            default -> false;
        };
    }

    private static String toolLabel(JsonNode item) {
        return switch (item.path("type").asText()) {
            case "mcpToolCall" -> item.path("server").asText() + " · " + item.path("tool").asText();
            case "commandExecution" -> "Command · " + abbreviate(item.path("command").asText(), 100);
            case "fileChange" -> "File changes";
            case "dynamicToolCall" -> "Tool · " + item.path("tool").asText();
            case "webSearch" -> "Web search · " + item.path("query").asText();
            default -> "Tool activity";
        };
    }

    private static String toolDetails(JsonNode item) {
        return switch (item.path("type").asText()) {
            case "mcpToolCall" -> mcpToolDetails(item);
            case "dynamicToolCall" -> compactJson(item.path("arguments"));
            case "commandExecution" -> item.path("cwd").asText();
            case "fileChange" -> item.path("changes").size() + " file change(s)";
            default -> "";
        };
    }

    private static String approvalDetails(JsonNode params) {
        List<String> details = new ArrayList<>();
        if (!params.path("cwd").asText("").isBlank()) {
            details.add("Working directory: " + params.path("cwd").asText());
        }
        if (!params.path("reason").asText("").isBlank()) {
            details.add("Reason: " + params.path("reason").asText());
        }
        if (!params.path("grantRoot").asText("").isBlank()) {
            details.add("Requested write root: " + params.path("grantRoot").asText());
        }
        JsonNode network = params.path("networkApprovalContext");
        if (!network.isMissingNode()) {
            String host = network.path("host").asText("");
            String protocol = network.path("protocol").asText("");
            String port = network.path("port").asText("");
            if (!host.isBlank()) {
                details.add("Network: " + (protocol.isBlank() ? "" : protocol + "://")
                        + host + (port.isBlank() ? "" : ":" + port));
            }
        }
        if (!params.path("permissions").isMissingNode()) {
            details.add("Requested permissions: " + compactJson(params.path("permissions")));
        }
        return String.join("\n", details);
    }

    private static String mcpToolDetails(JsonNode item) {
        List<String> details = new ArrayList<>();
        String arguments = compactJson(item.path("arguments"));
        if (!arguments.isBlank()) {
            details.add("Arguments: " + arguments);
        }
        JsonNode error = item.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            details.add("Error: " + compactJson(error));
        } else {
            JsonNode result = item.path("result");
            if (!result.isMissingNode() && !result.isNull()) {
                details.add("Result: " + compactJson(result));
            }
        }
        return abbreviate(String.join("\n", details), 600);
    }

    private void emit(CodexEvent event) {
        if (event.threadId() == null) {
            return;
        }
        List<Consumer<CodexEvent>> threadListeners = listeners.get(event.threadId());
        if (threadListeners != null) {
            threadListeners.forEach(listener -> safelyAccept(listener, event));
        }
    }

    private static <T> void safelyAccept(Consumer<T> listener, T value) {
        try {
            listener.accept(value);
        } catch (RuntimeException ex) {
            log.warn("Codex conversation listener failed", ex);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Codex app-server failed" : message;
    }

    private static String compactJson(JsonNode node) {
        String value = node == null || node.isMissingNode() ? "" : node.toString();
        return abbreviate(value, 600);
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private record PendingApproval(JsonNode id, String method, String threadId, JsonNode params) {
    }
}
