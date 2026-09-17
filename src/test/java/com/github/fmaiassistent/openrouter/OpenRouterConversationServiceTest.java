package com.github.fmaiassistent.openrouter;

import com.github.fmaiassistent.ai.AiPromptContext;
import com.github.fmaiassistent.mcp.FmAiAssistentTools;
import com.github.fmaiassistent.mcp.FmDecisionTools;
import com.github.fmaiassistent.mcp.FmSnapshotTools;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenRouterConversationServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final List<String> catalogKeys = new CopyOnWriteArrayList<>();
    private volatile String catalogBody = "{\"data\":[]}";
    private HttpServer server;
    private OpenRouterConversationService service;
    private String result = "{\"updated\":true}";
    private volatile CountDownLatch toolEntered;
    private volatile CountDownLatch toolRelease;
    private final CountDownLatch streamRelease = new CountDownLatch(1);

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            try {
                Reply reply = replies.poll(5, TimeUnit.SECONDS);
                if (reply == null) throw new IllegalStateException("Missing test reply");
                byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(reply.status, reply.stall ? 0 : bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().flush();
                if (reply.stall) streamRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/models/user", exchange -> {
            catalogKeys.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = catalogBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        service = session();
        service.connect("test-secret-key");
    }
    private OpenRouterConversationService session() {
        return session("fm26_update_recruitment");
    }
    private OpenRouterConversationService session(String toolName) {
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(toolName).description("Update recruitment")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}").build();
            }
            @Override public String call(String arguments) {
                writes.incrementAndGet();
                if (toolEntered != null) {
                    toolEntered.countDown();
                    try { toolRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return result;
            }
        };
        var client = new OpenRouterClient(json, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"));
        return new OpenRouterConversationService(json, client, () -> new ToolCallback[] {tool},
                (id, text) -> "Enabled club context\n" + text, "Existing MCP instructions");
    }
    @AfterEach void tearDown() { streamRelease.countDown(); service.close(); server.stop(0); }

    @Test void streamsExecutesWritesAndRetainsFollowUpContextInFree() throws Exception {
        replies.add(new Reply(200, tool("fm26_update_recruitment", "{\"id\":7}")));
        replies.add(new Reply(200, answer("Updated")));
        String id = service.newConversation();
        service.send(id, "Update recruitment");
        awaitDone(id);
        assertEquals(1, writes.get());
        assertEquals("provider/actual-model", service.get(id).model());
        assertTrue(service.get(id).items().stream().anyMatch(i -> i.text().equals("Updated")));
        replies.add(new Reply(200, answer("Follow-up")));
        service.send(id, "What changed?");
        awaitDone(id);
        assertEquals(3, requests.size());
        for (JsonNode request : requests) {
            assertEquals("openrouter/free", request.path("model").asString());
            assertFalse(request.has("models"));
            assertFalse(request.has("plugins"));
            assertFalse(request.has("provider"));
            assertEquals("fm26_update_recruitment", request.path("tools").get(0).path("function").path("name").asString());
        }
        String history = requests.getLast().path("messages").toString();
        assertTrue(history.contains("Enabled club context"));
        assertTrue(history.contains("Existing MCP instructions"));
        assertTrue(history.contains("tool_call_id"));
        assertTrue(history.contains("Updated"));
    }
    @Test void autoRequiresConfirmationAndNewChatResetsFree() throws Exception {
        String id = service.newConversation();
        assertThrows(IllegalArgumentException.class, () -> service.selectMode(id, OpenRouterConversationService.Mode.AUTO, false));
        assertEquals(OpenRouterConversationService.Mode.FREE, service.get(id).mode());
        service.selectMode(id, OpenRouterConversationService.Mode.AUTO, true);
        replies.add(new Reply(200, answer("Paid routing selected")));
        service.send(id, "Hi");
        awaitDone(id);
        assertEquals("openrouter/auto", requests.getFirst().path("model").asString());
        assertEquals(OpenRouterConversationService.Mode.FREE, service.get(service.newConversation()).mode());
    }
    @Test void catalogOnlyOffersToolCapableModelsAndPaidSelectionNeedsConfirmation() throws Exception {
        catalogBody = """
                {"data":[
                  {"id":"example/fast:free","name":"Fast Free","supported_parameters":["tools"],"pricing":{"prompt":"0","completion":"0","request":"0"}},
                  {"id":"example/strong","name":"Strong","supported_parameters":["tools"],"pricing":{"prompt":"0.000001","completion":"0.000002","request":"0"}},
                  {"id":"example/no-tools","name":"No tools","supported_parameters":["temperature"],"pricing":{"prompt":"0","completion":"0","request":"0"}},
                  {"id":"example/mispriced:free","name":"Mispriced","supported_parameters":["tools"],"pricing":{"prompt":"1","completion":"0","request":"0"}}
                ]}
                """;
        var options = service.loadModels();
        assertEquals(List.of("example/fast:free", "example/strong"), options.stream().map(OpenRouterConversationService.ModelOption::id).toList());
        assertTrue(options.getFirst().free());
        assertFalse(options.getLast().free());
        assertEquals(List.of("Bearer test-secret-key"), catalogKeys);
        String id = service.newConversation();
        assertThrows(IllegalArgumentException.class, () -> service.selectModel(id, "example/no-tools", true));
        assertThrows(IllegalArgumentException.class, () -> service.selectModel(id, "example/strong", false));
        assertEquals(OpenRouterConversationService.Mode.FREE, service.get(id).mode());
        service.selectModel(id, "example/fast:free", false);
        replies.add(new Reply(200, tool("fm26_update_recruitment", "{\"id\":7}")));
        replies.add(new Reply(200, answer("Done")));
        service.send(id, "Update");
        awaitDone(id);
        assertEquals(2, requests.size());
        for (JsonNode request : requests) assertEquals("example/fast:free", request.path("model").asString());
        service.selectModel(id, "example/strong", true);
        replies.add(new Reply(200, answer("Paid")));
        service.send(id, "Follow up");
        awaitDone(id);
        assertEquals("example/strong", requests.getLast().path("model").asString());
        assertEquals("example/strong", service.get(id).requestedModel());
        assertEquals("provider/actual-model", service.get(id).model());
        assertEquals(OpenRouterConversationService.Mode.FREE, service.get(service.newConversation()).mode());
        service.disconnect();
        service.connect("another-key");
        assertThrows(IllegalArgumentException.class,
                () -> service.selectModel(service.newConversation(), "example/strong", true));
    }
    @Test void rejectsUnknownAndMalformedToolsBeforeWrites() throws Exception {
        for (String response : List.of(tool("shell", "{\"id\":7}"),
                tool("fm26_update_recruitment", "{\"id\":\"wrong\"}"),
                tool("fm26_update_recruitment", "{broken"),
                tool("fm26_update_recruitment", "{}"),
                tool("fm26_update_recruitment", "{\"id\":7,\"extra\":true}"))) {
            replies.add(new Reply(200, response));
            String id = service.newConversation();
            service.send(id, "test");
            awaitDone(id);
            assertTrue(service.get(id).items().stream().anyMatch(i -> i.role().equals("Error")));
        }
        assertEquals(0, writes.get());
    }
    @Test void errorsAreActionableAndNeverExposeProviderBodiesOrRetry() throws Exception {
        for (int status : List.of(401, 402, 403, 429, 503, 502)) {
            replies.add(new Reply(status, "secret provider details test-secret-key"));
            String id = service.newConversation();
            service.send(id, "test");
            awaitDone(id);
            String error = service.get(id).items().getLast().text();
            assertFalse(error.contains("test-secret-key"));
            assertFalse(error.contains("secret provider details"));
            assertTrue(error.contains("OpenRouter"));
            assertThrows(IllegalStateException.class, () -> service.send(id, "retry"));
        }
        assertEquals(6, requests.size());
    }
    @Test void brokenStreamsAndMidStreamErrorsFailWithoutExecutingTools() throws Exception {
        for (String response : List.of("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                "data: {\"error\":{\"code\":429,\"message\":\"test-secret-key\"}}\n\n",
                "data: invalid json\n\n", "data: [DONE]\n\n")) {
            replies.add(new Reply(200, response));
            String id = service.newConversation();
            service.send(id, "test");
            awaitDone(id);
            assertEquals("Error", service.get(id).items().getLast().role());
        }
        assertEquals(0, writes.get());
    }
    @Test void stopDuringLocalWritePreventsFurtherRequestsAndModeChangesWhileRunning() throws Exception {
        toolEntered = new CountDownLatch(1);
        toolRelease = new CountDownLatch(1);
        replies.add(new Reply(200, tool("fm26_update_recruitment", "{\"id\":7}")));
        String id = service.newConversation();
        service.send(id, "write");
        assertTrue(toolEntered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> service.send(id, "overlap"));
        assertThrows(IllegalStateException.class, () -> service.selectMode(id, OpenRouterConversationService.Mode.AUTO, true));
        assertThrows(IllegalStateException.class, () -> service.selectModel(id, "example/strong", true));
        service.stop(id);
        toolRelease.countDown();
        awaitDone(id);
        assertEquals(1, writes.get());
        assertEquals(1, requests.size());
        assertTrue(service.get(id).items().getLast().text().contains("stopped"));
    }
    @Test void sessionsAreIsolatedAndCredentialsAndHistoryAreCleared() {
        String id = service.newConversation();
        try (var other = session()) {
            assertFalse(other.connected());
            other.connect("other-key");
            assertThrows(IllegalArgumentException.class, () -> other.get(id));
            assertThrows(IllegalArgumentException.class, () -> other.send(id, "hello"));
            assertTrue(other.list().isEmpty());
        }
        service.disconnect();
        assertFalse(service.connected());
        assertTrue(service.list().isEmpty());
        assertThrows(IllegalStateException.class, service::newConversation);
        service.connect("new-key");
        service.newConversation();
        service.close();
        assertFalse(service.connected());
        assertTrue(service.list().isEmpty());
        assertThrows(IllegalStateException.class, () -> service.connect("again"));
    }
    @Test void stopInterruptsAStalledHttpStream() throws Exception {
        replies.add(new Reply(200, frame("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"), true));
        String id = service.newConversation();
        service.send(id, "test");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.get(id).items().size() < 2 && System.nanoTime() < end) Thread.sleep(10);
        assertEquals("partial", service.get(id).items().getLast().text());
        service.stop(id);
        awaitDone(id);
        assertEquals(1, requests.size());
    }
    @Test void timeoutBoundsEntireStreamRead() throws Exception {
        replies.add(new Reply(200, ": keepalive\n\n", true));
        try (var client = new OpenRouterClient(json, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"), java.time.Duration.ofMillis(200))) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
                var failure = assertThrows(OpenRouterClient.Failure.class,
                        () -> client.stream("test-key", "{}", ignored -> { }));
                assertTrue(failure.getMessage().contains("timed out"));
            });
        }
    }
    @Test void oversizedResultsAndRequestsAreExplicit() throws Exception {
        String id = service.newConversation();
        assertThrows(RuntimeException.class, () -> service.send(id, "x".repeat(512 * 1024)));
        assertTrue(requests.isEmpty());
        result = "x".repeat(64 * 1024 + 1);
        replies.add(new Reply(200, tool("fm26_update_recruitment", "{\"id\":7}")));
        replies.add(new Reply(200, answer("Please narrow your question.")));
        service.send(id, "test");
        awaitDone(id);
        assertTrue(service.get(id).items().stream().anyMatch(i -> i.text().contains("64 KiB")));
        assertEquals("Please narrow your question.", service.get(id).items().getLast().text());
        assertEquals(2, requests.size());
        JsonNode history = requests.getLast().path("messages");
        JsonNode notice = json.readTree(history.get(history.size() - 1).path("content").asString());
        assertEquals("result_omitted", notice.path("status").asString());
        assertTrue(notice.path("execution_completed").asBoolean());
        assertTrue(notice.path("message").asString().contains("Do not repeat a completed write"));
        assertFalse(requests.getLast().toString().contains("x".repeat(100)));
        replies.add(new Reply(200, answer("Narrower answer")));
        service.send(id, "Focus on one player");
        awaitDone(id);
        assertEquals("Narrower answer", service.get(id).items().getLast().text());
        assertEquals(1, writes.get());
    }
    @Test void oversizedFormattedJsonIsCompactedWithoutDroppingData() throws Exception {
        result = "{\n" + " ".repeat(64 * 1024) + "\"updated\":true,\"name\":\"José\"\n}";
        replies.add(new Reply(200, tool("fm26_update_recruitment", "{\"id\":7}")));
        replies.add(new Reply(200, answer("Updated")));
        String id = service.newConversation();
        service.send(id, "test");
        awaitDone(id);
        assertEquals("Updated", service.get(id).items().getLast().text());
        JsonNode history = requests.getLast().path("messages");
        String sent = history.get(history.size() - 1).path("content").asString();
        assertEquals(json.readTree(result), json.readTree(sent));
        assertTrue(sent.getBytes(StandardCharsets.UTF_8).length < 64 * 1024);
        assertEquals(1, writes.get());
    }
    @Test void largeSquadAnalysisKeepsDecisionsAndFitsWithinToolLimit() throws Exception {
        var squad = json.createObjectNode().put("club", "Test FC").put("squad_size", 32);
        var depth = squad.putArray("position_depth");
        for (int position = 0; position < 14; position++) {
            var row = depth.addObject().put("position", "P" + position).put("coverage", "healthy")
                    .put("viable_options", 3).put("available_options", 3);
            var options = row.putArray("best_options");
            for (int player = 0; player < 3; player++)
                options.add(detailedPlayer(position * 3 + player).put("position_score", 18));
        }
        var slots = squad.putArray("tactic_slots");
        var lineup = squad.putObject("optimized_lineup").put("filled_slots", 11).putArray("lineup");
        for (int slot = 0; slot < 11; slot++) {
            var row = slots.addObject().put("slot", slot + 1).put("coverage", "healthy");
            var options = row.putArray("best_options");
            for (int player = 0; player < 3; player++) {
                var option = detailedPlayer(slot * 3 + player);
                option.set("fit", detailedFit(slot + 1));
                options.add(option);
            }
            var assignment = lineup.addObject().put("tactic_slot", slot + 1);
            assignment.set("player", detailedPlayer(slot));
            assignment.set("fit", detailedFit(slot + 1));
            var alternatives = assignment.putArray("alternatives");
            for (int player = 0; player < 3; player++) {
                var alternative = detailedPlayer(slot * 3 + player);
                alternative.set("fit", detailedFit(slot + 1));
                alternatives.add(alternative);
            }
        }
        squad.putArray("loaned_out").add(detailedPlayer(99));
        result = json.writeValueAsString(squad);
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length > 64 * 1024);

        service.close();
        service = session("fm26_analyze_squad");
        service.connect("test-secret-key");
        replies.add(new Reply(200, tool("fm26_analyze_squad", "{\"id\":7}")));
        replies.add(new Reply(200, answer("Here is your squad analysis.")));
        String id = service.newConversation();
        service.send(id, "Analyze my squad");
        awaitDone(id);

        assertEquals("Here is your squad analysis.", service.get(id).items().getLast().text());
        JsonNode history = requests.getLast().path("messages");
        String sent = history.get(history.size() - 1).path("content").asString();
        JsonNode summary = json.readTree(sent);
        assertTrue(sent.getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);
        assertTrue(summary.path("_openrouter_response").path("compacted").asBoolean());
        assertEquals(14, summary.path("position_depth").size());
        assertEquals(11, summary.path("tactic_slots").size());
        assertEquals(11, summary.path("optimized_lineup").path("lineup").size());
        assertEquals("healthy", summary.path("position_depth").get(0).path("coverage").asString());
        assertEquals(18, summary.path("position_depth").get(0).path("best_options").get(0).path("position_score").asInt());
        assertTrue(summary.path("optimized_lineup").path("lineup").get(0).path("fit").has("overall"));
        assertFalse(summary.path("optimized_lineup").path("lineup").get(0).path("player").has("nationality"));
        assertTrue(summary.path("loaned_out").get(0).has("nationality"));
        assertTrue(json.readTree(result).path("optimized_lineup").path("lineup").get(0)
                .path("player").has("nationality"));
        assertEquals(2, requests.size());
    }
    private tools.jackson.databind.node.ObjectNode detailedPlayer(int id) {
        return json.createObjectNode().put("player_unique_id", id).put("name", "Player " + id)
                .put("age", 24).put("position_text", "DC").put("ca", 150).put("pa", 170)
                .put("injured", false).put("contract_end", "2030-01-01")
                .put("nationality", "x".repeat(800)).put("club", "Test FC")
                .put("playing_club", "Test FC").put("salary_weekly", 30000);
    }
    private tools.jackson.databind.node.ObjectNode detailedFit(int slot) {
        var fit = json.createObjectNode().put("slot", slot).put("position_fit", 18)
                .put("role_fit", 80).put("overall", 84).put("viable", true);
        fit.putObject("in_possession").put("role", "Centre Back").put("strengths", "passing, tackling");
        fit.putObject("out_of_possession").put("role", "Centre Back").put("strengths", "marking, heading");
        return fit;
    }
    @Test void modelRequestBudgetStopsToolLoop() throws Exception {
        for (int i = 0; i < 20; i++) replies.add(new Reply(200, tool("fm26_update_recruitment", "{\"id\":7}")));
        String id = service.newConversation();
        service.send(id, "test");
        awaitDone(id);
        assertEquals(20, requests.size());
        assertTrue(service.get(id).items().getLast().text().contains("20 model requests"));
    }
    @Test void existingFmToolSchemasAndWriteCallbacksAreReusedUnchanged() throws Exception {
        var fm = mock(FmAiAssistentTools.class);
        var decisions = mock(FmDecisionTools.class);
        var snapshots = mock(FmSnapshotTools.class);
        var provider = MethodToolCallbackProvider.builder().toolObjects(fm, decisions, snapshots).build();
        service.close();
        service = new OpenRouterConversationService(json, new OpenRouterClient(json, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat")),
                provider, AiPromptContext.none(), "instructions");
        service.connect("test-key");
        replies.add(new Reply(200, tool("fm26_refresh_data", "{\"force\":true}")));
        replies.add(new Reply(200, tool("fm26_update_recruitment_case", "{\"playerUniqueId\":7,\"interestStatus\":\"interested\"}")));
        replies.add(new Reply(200, tool("fm26_create_shortlist_file", "{\"shortlistName\":\"Targets\",\"playerUniqueIds\":[7]}")));
        replies.add(new Reply(200, answer("Done")));
        String id = service.newConversation();
        service.send(id, "Update and export");
        awaitDone(id);
        assertEquals("Done", service.get(id).items().getLast().text());
        verify(snapshots).refreshData(true);
        verify(decisions).updateRecruitmentCase(7L, "interested", null, null, null, null, null, null, null);
        verify(fm).createShortlistFile("Targets", List.of(7L));
        JsonNode sentTools = requests.getFirst().path("tools");
        assertEquals(provider.getToolCallbacks().length, sentTools.size());
        for (ToolCallback callback : provider.getToolCallbacks()) {
            var definition = callback.getToolDefinition();
            JsonNode sent = java.util.stream.StreamSupport.stream(sentTools.spliterator(), false)
                    .map(t -> t.path("function")).filter(t -> t.path("name").asString().equals(definition.name()))
                    .findFirst().orElseThrow();
            assertEquals(json.readTree(definition.inputSchema()), sent.path("parameters"));
            assertEquals(definition.description(), sent.path("description").asString());
        }
    }
    @Test void cumulativeToolBudgetStopsBeforeExecutingTheExcessBatch() throws Exception {
        var chunk = json.createObjectNode();
        var choice = chunk.putArray("choices").addObject().put("finish_reason", "tool_calls");
        var calls = choice.putObject("delta").putArray("tool_calls");
        for (int i = 0; i < 3; i++) {
            calls.addObject().put("index", i).put("id", "call-" + i).put("type", "function")
                    .putObject("function").put("name", "fm26_update_recruitment").put("arguments", "{\"id\":7}");
        }
        for (int i = 0; i < 14; i++) replies.add(new Reply(200, frame(chunk.toString()) + "data: [DONE]\n\n"));
        String id = service.newConversation();
        service.send(id, "test");
        awaitDone(id);
        assertEquals(14, requests.size());
        assertEquals(39, writes.get());
        assertTrue(service.get(id).items().getLast().text().contains("40 tool calls"));
    }

    @Test void missingManagedClubIsReportedAndSameChatCanContinueWithExplicitClub() throws Exception {
        var managed = mock(com.github.fmaiassistent.managedclub.ManagedClubContextService.class);
        when(managed.current()).thenReturn(com.github.fmaiassistent.managedclub.ManagedClubContext.notLoaded(0));
        var players = mock(com.github.fmaiassistent.service.PlayerDatabaseService.class);
        when(players.findAllPlayerEntities()).thenReturn(List.of());
        var tactics = mock(com.github.fmaiassistent.tactic.TacticContextService.class);
        when(tactics.current()).thenReturn(new com.github.fmaiassistent.tactic.TacticContext(
                0, "No tactic", null, null, List.of(), List.of()));
        var snapshots = mock(com.github.fmaiassistent.snapshot.SnapshotStatusService.class);
        when(snapshots.reference()).thenReturn(java.util.Map.of());
        var decisions = new FmDecisionTools(players,
                mock(com.github.fmaiassistent.service.ClubDatabaseService.class), managed, tactics,
                mock(com.github.fmaiassistent.decision.RoleFitService.class),
                mock(com.github.fmaiassistent.decision.LineupOptimizerService.class),
                mock(com.github.fmaiassistent.recruitment.RecruitmentCaseService.class), snapshots);
        useTools(decisions);
        replies.add(new Reply(200, tool("fm26_analyze_squad", "{}")));
        replies.add(new Reply(200, answer("Please open your FM26 save and select Load data.")));
        String id = service.newConversation();
        service.send(id, "Analyze my squad");
        awaitDone(id);
        assertFalse(service.get(id).items().stream().anyMatch(i -> i.role().equals("Error")));
        assertTrue(service.get(id).items().stream().anyMatch(i -> i.text().contains(
                "fm26_analyze_squad · Needs attention: managingClub is required")));
        JsonNode messages = requests.getLast().path("messages");
        JsonNode error = json.readTree(messages.get(messages.size() - 1).path("content").asString());
        assertEquals("validation_error", error.path("status").asString());
        assertEquals("managingClub is required because the current managed club is unavailable", error.path("message").asString());
        assertTrue(error.path("guidance").asString().contains("Load data"));
        assertEquals(2, requests.size());
        replies.add(new Reply(200, tool("fm26_analyze_squad", "{\"managingClub\":\"Test FC\"}")));
        replies.add(new Reply(200, answer("Analysis completed")));
        service.send(id, "I loaded data. My club is Test FC.");
        awaitDone(id);
        assertEquals("Analysis completed", service.get(id).items().getLast().text());
        messages = requests.getLast().path("messages");
        JsonNode analysis = json.readTree(messages.get(messages.size() - 1).path("content").asString());
        assertEquals("Test FC", analysis.path("club").asString());
        assertEquals(0, analysis.path("squad_size").asInt(-1));
        verify(players, times(1)).findAllPlayerEntities();
    }

    @Test void failedWriteIsNotReplayedAndItsValidationMessageIsRedacted() throws Exception {
        var fm = mock(FmAiAssistentTools.class);
        when(fm.createShortlistFile(anyString(), anyList()))
                .thenThrow(new IllegalArgumentException("Unknown player: test-secret-key"));
        useTools(fm);
        replies.add(new Reply(200, tool("fm26_create_shortlist_file", "{\"shortlistName\":\"Targets\",\"playerUniqueIds\":[7]}")));
        String id = service.newConversation();
        service.send(id, "Create shortlist");
        awaitDone(id);
        assertTrue(service.get(id).items().getLast().text().contains("fm26_create_shortlist_file failed: Unknown player: [redacted]"));
        assertFalse(service.get(id).items().getLast().text().contains("test-secret-key"));
        assertThrows(IllegalStateException.class, () -> service.send(id, "retry"));
        verify(fm, times(1)).createShortlistFile("Targets", List.of(7L));
        assertEquals(1, requests.size());
    }

    @Test void unexpectedToolFailuresDoNotExposeInternalExceptionDetails() throws Exception {
        var decisions = mock(FmDecisionTools.class);
        when(decisions.analyzeSquad(null, null, null))
                .thenThrow(new IllegalStateException("private SQL and test-secret-key"));
        useTools(decisions);
        replies.add(new Reply(200, tool("fm26_analyze_squad", "{}")));
        String id = service.newConversation();
        service.send(id, "Analyze my squad");
        awaitDone(id);
        String error = service.get(id).items().getLast().text();
        assertTrue(error.contains("fm26_analyze_squad failed"));
        assertFalse(error.contains("private SQL"));
        assertFalse(error.contains("test-secret-key"));
        assertEquals(1, requests.size());
    }

    private void useTools(Object... toolObjects) {
        service.close();
        service = new OpenRouterConversationService(json, new OpenRouterClient(json, HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat")),
                MethodToolCallbackProvider.builder().toolObjects(toolObjects).build(), AiPromptContext.none(), "instructions");
        service.connect("test-secret-key");
    }

    private String answer(String text) {
        return ": keepalive\n\n" + frame("{\"model\":\"provider/actual-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}]}") + "data: [DONE]\n\n";
    }
    private String tool(String name, String arguments) {
        // Name and arguments deliberately split across SSE chunks.
        var first = json.createObjectNode();
        var delta = first.putArray("choices").addObject().putObject("delta");
        var call = delta.putArray("tool_calls").addObject().put("index", 0).put("id", "call-1").put("type", "function");
        call.putObject("function").put("name", name.substring(0, 2)).put("arguments", "");
        var second = json.createObjectNode();
        var choice = second.putArray("choices").addObject().put("finish_reason", "tool_calls");
        choice.putObject("delta").putArray("tool_calls").addObject().put("index", 0)
                .putObject("function").put("name", name.substring(2)).put("arguments", arguments);
        return frame(first.toString()) + frame(second.toString()) + "data: [DONE]\n\n";
    }
    private static String frame(String data) { return "data: " + data + "\n\n"; }
    private void awaitDone(String id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.get(id).running() && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(service.get(id).running(), "Turn did not finish");
    }
    private record Reply(int status, String body, boolean stall) {
        Reply(int status, String body) { this(status, body, false); }
    }
}
