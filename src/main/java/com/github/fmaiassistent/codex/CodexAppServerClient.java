package com.github.fmaiassistent.codex;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Component
class CodexAppServerClient {
    private final CodexJsonRpcClient rpc;
    private final ObjectMapper mapper;
    private final Path workingDirectory;

    CodexAppServerClient(CodexJsonRpcClient rpc, ObjectMapper mapper, CodexWorkspaceResolver workspaceResolver) {
        this.rpc = rpc;
        this.mapper = mapper;
        workingDirectory = workspaceResolver.workingDirectory();
    }

    CompletableFuture<JsonNode> start() {
        return rpc.startAndInitialize();
    }

    CompletableFuture<JsonNode> account() {
        return rpc.request("account/read", mapper.createObjectNode().put("refreshToken", false));
    }

    CompletableFuture<JsonNode> startChatGptLogin() {
        return rpc.request("account/login/start", mapper.createObjectNode()
                .put("type", "chatgpt")
                .put("useHostedLoginSuccessPage", true)
                .put("appBrand", "chatgpt"));
    }

    CompletableFuture<JsonNode> cancelLogin(String loginId) {
        return rpc.request("account/login/cancel", mapper.createObjectNode().put("loginId", loginId));
    }

    CompletableFuture<JsonNode> startThread() {
        ObjectNode params = mapper.createObjectNode().put("cwd", workingDirectory.toString());
        return rpc.request("thread/start", params);
    }

    CompletableFuture<JsonNode> resumeThread(String threadId) {
        return rpc.request("thread/resume", mapper.createObjectNode().put("threadId", threadId));
    }

    CompletableFuture<JsonNode> readThread(String threadId) {
        return rpc.request("thread/read", mapper.createObjectNode()
                .put("threadId", threadId)
                .put("includeTurns", true));
    }

    CompletableFuture<JsonNode> listThreads() {
        ObjectNode params = mapper.createObjectNode()
                .put("cwd", workingDirectory.toString())
                .put("limit", 100)
                .put("sortKey", "updated_at")
                .put("sortDirection", "desc");
        return rpc.request("thread/list", params);
    }

    CompletableFuture<JsonNode> startTurn(String threadId, String text, String clientUserMessageId) {
        ObjectNode textInput = mapper.createObjectNode().put("type", "text").put("text", text);
        ObjectNode params = mapper.createObjectNode()
                .put("threadId", threadId)
                .put("clientUserMessageId", clientUserMessageId)
                .set("input", mapper.createArrayNode().add(textInput));
        return rpc.request("turn/start", params);
    }

    CompletableFuture<JsonNode> interruptTurn(String threadId, String turnId) {
        return rpc.request("turn/interrupt", mapper.createObjectNode()
                .put("threadId", threadId)
                .put("turnId", turnId));
    }

    CodexSubscription onNotification(java.util.function.Consumer<CodexJsonRpcClient.Notification> listener) {
        return rpc.onNotification(listener);
    }

    CodexSubscription onServerRequest(java.util.function.Consumer<CodexJsonRpcClient.ServerRequest> listener) {
        return rpc.onServerRequest(listener);
    }

    CodexSubscription onFailure(java.util.function.Consumer<Throwable> listener) {
        return rpc.onFailure(listener);
    }

    void respond(JsonNode id, JsonNode result) {
        rpc.respond(id, result);
    }

    void respondError(JsonNode id, int code, String message) {
        rpc.respondError(id, code, message);
    }

    CompletableFuture<JsonNode> restart() {
        return rpc.restart();
    }
}
