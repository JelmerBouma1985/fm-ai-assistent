package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.repository.DatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.codex.enabled=false",
                "app.ai.antigravity.enabled=false",
                "app.ai.copilot.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:mcp-protocol-test;DB_CLOSE_DELAY=-1"
        })
class McpProtocolCompatibilityTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ObjectMapper mapper;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseService database;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void failedSnapshotReplacementRollsBackAndPreservesRecruitmentEvidence() {
        jdbc.update("INSERT INTO competitions (name) VALUES (?)", "Rollback League");
        jdbc.update("INSERT INTO recruitment_case (player_unique_id, deal_stage) VALUES (?, ?)",
                2099999999L, "monitoring");

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    database.clearAllTables();
                    throw new IllegalStateException("simulated RAM read failure");
                }));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM competitions WHERE name = 'Rollback League'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recruitment_case WHERE player_unique_id = 2099999999", Integer.class));

        database.clearAllTables();
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM competitions WHERE name = 'Rollback League'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recruitment_case WHERE player_unique_id = 2099999999", Integer.class));
        jdbc.update("DELETE FROM recruitment_case WHERE player_unique_id = 2099999999");
    }

    @Test
    void supportsAntigravityAndCodexProtocolVersions() throws Exception {
        for (String protocol : new String[] {"2025-11-25", "2025-06-18"}) {
            JsonNode initialize = mapper.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "initialize")
                    .set("params", mapper.createObjectNode()
                            .put("protocolVersion", protocol)
                            .set("capabilities", mapper.createObjectNode())
                            .set("clientInfo", mapper.createObjectNode()
                                    .put("name", "compatibility-test")
                                    .put("version", "1.0")));

            HttpResponse<String> initialized = post(mapper.writeValueAsString(initialize), null, null);

            assertEquals(200, initialized.statusCode());
            assertEquals(protocol, mapper.readTree(initialized.body())
                    .path("result").path("protocolVersion").asText());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (sessionId != null) {
                post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId, protocol);
            }

            HttpResponse<String> tools = post(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                    sessionId,
                    protocol);
            String data = tools.body().lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()))
                    .findFirst()
                    .orElse(tools.body());
            JsonNode listedTools = mapper.readTree(data).path("result").path("tools");
            assertEquals(17, listedTools.size());
            Set<String> names = listedTools.valueStream()
                    .map(tool -> tool.path("name").asText())
                    .collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of(
                    "fm26_find_clubs",
                    "fm26_find_players",
                    "fm26_get_club_context",
                    "fm26_get_player_details",
                    "fm26_get_role_attributes",
                    "fm26_transfer_shortlist",
                    "fm26_create_shortlist_file",
                    "fm26_get_data_status",
                    "fm26_refresh_data",
                    "fm26_analyze_squad",
                    "fm26_optimize_lineup",
                    "fm26_recruit_for_tactic_slot",
                    "fm26_compare_players",
                    "fm26_find_replacements",
                    "fm26_plan_squad_moves",
                    "fm26_update_recruitment_case",
                    "fm26_get_recruitment_board")));
            JsonNode createShortlist = listedTools.valueStream()
                    .filter(tool -> "fm26_create_shortlist_file".equals(tool.path("name").asText()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("string", createShortlist.path("inputSchema").path("properties")
                    .path("shortlistName").path("type").asText());
            assertEquals("array", createShortlist.path("inputSchema").path("properties")
                    .path("playerUniqueIds").path("type").asText());
            JsonNode scenario = listedTools.valueStream()
                    .filter(tool -> "fm26_plan_squad_moves".equals(tool.path("name").asText()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("array", scenario.path("inputSchema").path("properties")
                    .path("quotes").path("type").asText());
            assertEquals("integer", scenario.path("inputSchema").path("properties")
                    .path("quotes").path("items").path("properties")
                    .path("playerUniqueId").path("type").asText());
            JsonNode optimizer = listedTools.valueStream()
                    .filter(tool -> "fm26_optimize_lineup".equals(tool.path("name").asText()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("array", optimizer.path("inputSchema").path("properties")
                    .path("lockedAssignments").path("type").asText());
            assertEquals("integer", optimizer.path("inputSchema").path("properties")
                    .path("lockedAssignments").path("items").path("properties")
                    .path("playerUniqueId").path("type").asText());
        }
    }

    private HttpResponse<String> post(String body, String sessionId, String protocol) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (protocol != null) {
            request.header("MCP-Protocol-Version", protocol);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
