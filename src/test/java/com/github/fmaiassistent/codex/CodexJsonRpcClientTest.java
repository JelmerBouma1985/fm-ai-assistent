package com.github.fmaiassistent.codex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexJsonRpcClientTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private Harness harness;

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void initializesBeforeRequestsAndSerializesUniqueIds() throws Exception {
        harness = new Harness(mapper);
        harness.initialize();

        CompletableFuture<JsonNode> first = harness.client.request("thread/list", mapper.createObjectNode());
        CompletableFuture<JsonNode> second = harness.client.request("thread/read", mapper.createObjectNode());
        JsonNode firstRequest = harness.readClientMessage();
        JsonNode secondRequest = harness.readClientMessage();

        assertEquals("thread/list", firstRequest.path("method").asText());
        assertEquals("thread/read", secondRequest.path("method").asText());
        assertNotEquals(firstRequest.path("id").asLong(), secondRequest.path("id").asLong());
        assertFalse(firstRequest.has("jsonrpc"));

        harness.respond(secondRequest, mapper.createObjectNode().put("value", "second"));
        harness.respond(firstRequest, mapper.createObjectNode().put("value", "first"));
        assertEquals("first", first.get(1, TimeUnit.SECONDS).path("value").asText());
        assertEquals("second", second.get(1, TimeUnit.SECONDS).path("value").asText());
    }

    @Test
    void dispatchesNotificationsAndSurvivesMalformedAndUnknownNotifications() throws Exception {
        harness = new Harness(mapper);
        harness.initialize();
        List<String> methods = new CopyOnWriteArrayList<>();
        harness.client.onNotification(notification -> methods.add(notification.method()));

        harness.serverOutput.write("not-json\n");
        harness.serverOutput.write("{\"method\":\"future/event\",\"params\":{\"value\":1}}\n");
        harness.serverOutput.flush();

        waitUntil(() -> methods.size() == 1);
        assertEquals(List.of("future/event"), methods);
        assertTrue(harness.process.isAlive());
    }

    @Test
    void completesErrorResponsesExceptionally() throws Exception {
        harness = new Harness(mapper);
        harness.initialize();
        CompletableFuture<JsonNode> request = harness.client.request("thread/read", mapper.createObjectNode());
        JsonNode outbound = harness.readClientMessage();

        harness.serverOutput.write(mapper.writeValueAsString(mapper.createObjectNode()
                .set("id", outbound.path("id"))
                .set("error", mapper.createObjectNode().put("code", -32000).put("message", "broken"))));
        harness.serverOutput.newLine();
        harness.serverOutput.flush();

        Exception error = assertThrows(Exception.class, () -> request.get(1, TimeUnit.SECONDS));
        assertTrue(rootCause(error) instanceof CodexRpcException);
        assertEquals("broken", rootCause(error).getMessage());
    }

    @Test
    void processExitFailsPendingRequests() throws Exception {
        harness = new Harness(mapper);
        harness.initialize();
        CompletableFuture<JsonNode> request = harness.client.request("thread/read", mapper.createObjectNode());
        harness.readClientMessage();

        harness.process.exit(9);

        Exception error = assertThrows(Exception.class, () -> request.get(1, TimeUnit.SECONDS));
        assertTrue(rootCause(error).getMessage().contains("exit code 9"));
    }

    private static Throwable rootCause(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null) {
            value = value.getCause();
        }
        return value;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class Harness implements AutoCloseable {
        private final ObjectMapper mapper;
        private final FakeProcess process = new FakeProcess();
        private final CodexJsonRpcClient client;
        private final BufferedReader clientInput;
        private final BufferedWriter serverOutput;

        private Harness(ObjectMapper mapper) throws IOException {
            this.mapper = mapper;
            CodexProperties properties = new CodexProperties(
                    true, "codex", ".",
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50));
            CodexProcessManager manager = new CodexProcessManager(
                    properties,
                    (command, cwd) -> process,
                    new CodexWorkspaceResolver(properties),
                    new CodexExecutableResolver(properties));
            client = new CodexJsonRpcClient(mapper, manager, properties, "test-client", "1.0");
            clientInput = process.serverInput;
            serverOutput = process.serverOutput;
        }

        private void initialize() throws Exception {
            CompletableFuture<JsonNode> initialized = client.startAndInitialize();
            JsonNode request = readClientMessage();
            assertEquals("initialize", request.path("method").asText());
            assertEquals("test-client", request.path("params").path("clientInfo").path("name").asText());
            respond(request, mapper.createObjectNode()
                    .put("userAgent", "codex-test")
                    .put("codexHome", "/tmp/codex")
                    .put("platformFamily", "unix")
                    .put("platformOs", "linux"));
            initialized.get(1, TimeUnit.SECONDS);
            assertEquals("initialized", readClientMessage().path("method").asText());
        }

        private JsonNode readClientMessage() throws Exception {
            CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
                try {
                    return clientInput.readLine();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
            return mapper.readTree(line.get(1, TimeUnit.SECONDS));
        }

        private void respond(JsonNode request, JsonNode result) throws IOException {
            serverOutput.write(mapper.writeValueAsString(mapper.createObjectNode()
                    .set("id", request.path("id"))
                    .set("result", result)));
            serverOutput.newLine();
            serverOutput.flush();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class FakeProcess implements CodexManagedProcess {
        private final PipedWriter clientStdout = new PipedWriter();
        private final BufferedReader stdout;
        private final BufferedWriter serverOutput;
        private final PipedWriter clientStdin = new PipedWriter();
        private final BufferedWriter stdin = new BufferedWriter(clientStdin);
        private final BufferedReader serverInput;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private volatile boolean alive = true;

        private FakeProcess() throws IOException {
            stdout = new BufferedReader(new PipedReader(clientStdout));
            serverOutput = new BufferedWriter(clientStdout);
            serverInput = new BufferedReader(new PipedReader(clientStdin));
        }

        @Override
        public BufferedReader stdout() {
            return stdout;
        }

        @Override
        public BufferedReader stderr() {
            return new BufferedReader(new StringReader(""));
        }

        @Override
        public BufferedWriter stdin() {
            return stdin;
        }

        @Override
        public long pid() {
            return 42;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return exit;
        }

        @Override
        public void closeInput() throws IOException {
            stdin.close();
        }

        @Override
        public void terminate(Duration timeout) {
            exit(0);
        }

        private void exit(int code) {
            alive = false;
            exit.complete(code);
        }
    }
}
