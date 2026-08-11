package com.github.fmaiassistent.codex;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
class CodexJsonRpcClient {
    record Notification(String method, JsonNode params) {
    }

    record ServerRequest(JsonNode id, String method, JsonNode params) {
    }

    private static final Logger log = LoggerFactory.getLogger(CodexJsonRpcClient.class);

    private final ObjectMapper mapper;
    private final CodexProcessManager processManager;
    private final CodexProperties properties;
    private final String clientName;
    private final String clientVersion;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Notification>> notificationListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ServerRequest>> requestListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Throwable>> failureListeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    private volatile CodexManagedProcess process;
    private volatile boolean initialized;
    private volatile boolean stopping;

    CodexJsonRpcClient(
            ObjectMapper mapper,
            CodexProcessManager processManager,
            CodexProperties properties,
            @Value("${spring.application.name:fm-ai-assistent}") String clientName,
            @Value("${project.version:0.4.0-SNAPSHOT}") String clientVersion) {
        this.mapper = mapper;
        this.processManager = processManager;
        this.properties = properties;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    synchronized CompletableFuture<JsonNode> startAndInitialize() {
        if (initialized && processManager.isAlive()) {
            return CompletableFuture.completedFuture(mapper.createObjectNode());
        }
        stopping = false;
        process = processManager.start();
        CodexManagedProcess started = process;
        Thread.ofVirtual().name("codex-app-server-stdout").start(() -> readStdout(started));
        started.onExit().thenAccept(exitCode -> processExited(started, exitCode));

        ObjectNode clientInfo = mapper.createObjectNode()
                .put("name", clientName)
                .put("title", "FM AI Assistent")
                .put("version", clientVersion);
        ObjectNode params = mapper.createObjectNode().set("clientInfo", clientInfo);
        CompletableFuture<JsonNode> initialization = requestInternal("initialize", params, properties.startupTimeout())
                .thenApply(result -> {
                    sendNotification("initialized", null);
                    initialized = true;
                    log.info("Initialized Codex app-server userAgent={} codexHome={}",
                            result.path("userAgent").asText("unknown"),
                            result.path("codexHome").asText("unknown"));
                    return result;
                });
        initialization.whenComplete((ignored, error) -> {
            if (error != null) {
                initialized = false;
                processManager.stop();
            }
        });
        return initialization;
    }

    CompletableFuture<JsonNode> request(String method, JsonNode params) {
        if (!initialized) {
            return CompletableFuture.failedFuture(new CodexException("Codex app-server is not initialized"));
        }
        return requestInternal(method, params, properties.requestTimeout());
    }

    private CompletableFuture<JsonNode> requestInternal(String method, JsonNode params, Duration timeout) {
        long id = requestIds.incrementAndGet();
        ObjectNode request = mapper.createObjectNode().put("method", method).put("id", id);
        if (params != null) {
            request.set("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> pending.remove(id, future));
        try {
            write(request);
            log.debug("Sent Codex app-server request method={} id={}", method, id);
        } catch (RuntimeException ex) {
            pending.remove(id);
            future.completeExceptionally(ex);
        }
        return future;
    }

    void sendNotification(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode().put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        write(notification);
    }

    void respond(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode().set("id", id);
        response.set("result", result == null ? mapper.createObjectNode() : result);
        write(response);
    }

    void respondError(JsonNode id, int code, String message) {
        ObjectNode error = mapper.createObjectNode().put("code", code).put("message", message);
        write(mapper.createObjectNode().set("id", id).set("error", error));
    }

    CodexSubscription onNotification(Consumer<Notification> listener) {
        notificationListeners.add(listener);
        return () -> notificationListeners.remove(listener);
    }

    CodexSubscription onServerRequest(Consumer<ServerRequest> listener) {
        requestListeners.add(listener);
        return () -> requestListeners.remove(listener);
    }

    CodexSubscription onFailure(Consumer<Throwable> listener) {
        failureListeners.add(listener);
        return () -> failureListeners.remove(listener);
    }

    boolean isReady() {
        return initialized && processManager.isAlive();
    }

    synchronized CompletableFuture<JsonNode> restart() {
        closeTransport();
        processManager.stop();
        return startAndInitialize();
    }

    private void readStdout(CodexManagedProcess running) {
        try {
            String line;
            while ((line = running.stdout().readLine()) != null) {
                receive(line);
            }
        } catch (IOException ex) {
            if (!stopping) {
                failTransport(new CodexException("Could not read Codex app-server output", ex));
            }
        }
    }

    void receive(String line) {
        try {
            JsonNode message = mapper.readTree(line);
            JsonNode id = message.get("id");
            String method = message.path("method").asText(null);
            if (id != null && method != null) {
                ServerRequest request = new ServerRequest(id, method, message.path("params"));
                if (requestListeners.isEmpty()) {
                    respondError(id, -32601, "Unsupported app-server request: " + method);
                } else {
                    requestListeners.forEach(listener -> safelyAccept(listener, request));
                }
            } else if (id != null) {
                handleResponse(id, message);
            } else if (method != null) {
                Notification notification = new Notification(method, message.path("params"));
                notificationListeners.forEach(listener -> safelyAccept(listener, notification));
            } else {
                log.warn("Ignoring unexpected Codex app-server message: {}", line);
            }
        } catch (RuntimeException ex) {
            log.warn("Ignoring malformed Codex app-server message", ex);
        }
    }

    private void handleResponse(JsonNode idNode, JsonNode message) {
        if (!idNode.isIntegralNumber()) {
            log.warn("Ignoring Codex response with unsupported id: {}", idNode);
            return;
        }
        long id = idNode.longValue();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            log.debug("Ignoring late or unknown Codex response id={}", id);
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new CodexRpcException(
                    error.path("message").asText("Codex app-server request failed"), error));
        } else {
            future.complete(message.path("result"));
        }
    }

    private <T> void safelyAccept(Consumer<T> listener, T value) {
        try {
            listener.accept(value);
        } catch (RuntimeException ex) {
            log.warn("Codex app-server listener failed", ex);
        }
    }

    private void write(JsonNode message) {
        CodexManagedProcess current = process;
        if (current == null || !current.isAlive()) {
            throw new CodexException("Codex app-server is not running");
        }
        synchronized (writeLock) {
            try {
                current.stdin().write(mapper.writeValueAsString(message));
                current.stdin().newLine();
                current.stdin().flush();
            } catch (IOException ex) {
                throw new CodexException("Could not write to Codex app-server", ex);
            }
        }
    }

    private void processExited(CodexManagedProcess exited, int exitCode) {
        if (!stopping && !processManager.isStopping() && process == exited) {
            failTransport(
                    new CodexException("The Codex process stopped unexpectedly (exit code " + exitCode + ")"),
                    exitCode == 0);
        }
    }

    private void failTransport(Throwable failure) {
        failTransport(failure, false);
    }

    private void failTransport(Throwable failure, boolean cleanExit) {
        initialized = false;
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        failureListeners.forEach(listener -> safelyAccept(listener, failure));
        if (cleanExit) {
            log.info("Codex app-server exited: {}", failure.getMessage());
        } else {
            log.error("Codex app-server transport failed", failure);
        }
    }

    private void closeTransport() {
        stopping = true;
        initialized = false;
        CodexException closed = new CodexException("Codex app-server client stopped");
        pending.values().forEach(future -> future.completeExceptionally(closed));
        pending.clear();
        process = null;
    }

    @PreDestroy
    void close() {
        closeTransport();
        processManager.stop();
    }
}
