package com.github.fmaiassistent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CompactFmToolCallbackTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void allSevenReadViewsKeepDecisionFieldsAndSnapshotIdentity() {
        for (String tool : new String[] {"fm26_analyze_squad", "fm26_optimize_lineup",
                "fm26_plan_squad_moves", "fm26_get_role_attributes", "fm26_find_players",
                "fm26_find_staff", "fm26_get_club_context"}) {
            ObjectNode original = fixture(tool);
            String full = json.writeValueAsString(original);
            CompactFmToolCallback callback = callback(tool, full);
            JsonNode compact = json.readTree(callback.call("{}"));
            assertEquals("test-snapshot", compact.path("snapshot").path("snapshot_id").asString(), tool);
            assertFalse(compact.path("snapshot").has("refresh_policy"), tool);
            assertEquals("compact", compact.path("_response_detail").path("level").asString(), tool);
            assertFalse(compact.path("_response_detail").path("omitted").asString().isBlank(), tool);
            assertTrue(json.writeValueAsString(compact).length() < full.length(), tool);
            assertEquals(full, callback.call("{\"responseDetail\":\"full\"}"), tool);
        }
    }

    @Test void playerDetailLevelFullWinsUnlessResponseDetailExplicitlyRequestsCompact() {
        String full = json.writeValueAsString(fixture("fm26_find_players"));
        CompactFmToolCallback callback = callback("fm26_find_players", full);
        assertEquals(full, callback.call("{\"detailLevel\":\"full\"}"));
        JsonNode compact = json.readTree(callback.call(
                "{\"detailLevel\":\"full\",\"responseDetail\":\"compact\"}"));
        assertFalse(compact.path("players").get(0).has("extra"));
    }

    @Test void invalidDetailIsRejectedBeforeExecution() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(new DefaultToolDefinition(
                "fm26_find_players", "search", "{\"type\":\"object\",\"properties\":{}}"));
        CompactFmToolCallback callback = new CompactFmToolCallback(delegate, json);
        assertThrows(IllegalArgumentException.class,
                () -> callback.call("{\"responseDetail\":\"brief\"}"));
        verify(delegate, never()).call(anyString());
        JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());
        assertEquals(2, schema.path("properties").path("responseDetail").path("enum").size());
    }

    @Test void serializedSizeAuditCoversEveryFmToolWithoutStoringResults() {
        String[] names = {"fm26_find_clubs", "fm26_find_players", "fm26_get_club_context",
                "fm26_get_player_details", "fm26_find_staff", "fm26_get_staff_details",
                "fm26_get_staff_coaching_roles", "fm26_get_role_attributes",
                "fm26_transfer_shortlist", "fm26_create_shortlist_file", "fm26_get_data_status",
                "fm26_refresh_data", "fm26_analyze_squad", "fm26_optimize_lineup",
                "fm26_recruit_for_tactic_slot", "fm26_compare_players",
                "fm26_find_replacements", "fm26_plan_squad_moves",
                "fm26_update_recruitment_case", "fm26_get_recruitment_board"};
        for (String name : names) {
            ObjectNode sample = FmToolResultFormatter.supports(name) ? fixture(name) : otherFixture(name);
            String full = json.writeValueAsString(sample);
            String compact = FmToolResultFormatter.compact(json, name, full);
            System.out.printf("FM_SIZE_AUDIT %s full=%d compact=%d%n", name,
                    bytes(full), bytes(compact));
            if (!FmToolResultFormatter.supports(name)) assertEquals(full, compact, name);
        }
    }

    @Test void localCompactViewNeverShortensLargeLists() {
        ObjectNode sample = fixture("fm26_find_players");
        var rows = (tools.jackson.databind.node.ArrayNode) sample.path("players");
        for (int i = 0; i < 300; i++) rows.addObject().put("unique_id", i + 10)
                .put("name", "Player " + i + "x".repeat(250)).put("ca", 140)
                .put("extra", "x".repeat(1000));
        JsonNode compact = json.readTree(FmToolResultFormatter.compact(json,
                "fm26_find_players", json.writeValueAsString(sample)));
        assertEquals(301, compact.path("players").size());
        assertTrue(bytes(json.writeValueAsString(compact)) > 64 * 1024);
    }

    private ObjectNode otherFixture(String name) {
        ObjectNode root = json.createObjectNode();
        root.putObject("snapshot").put("snapshot_id", "test-snapshot")
                .put("refresh_policy", "verbose policy ".repeat(40));
        switch (name) {
            case "fm26_find_clubs" -> {
                root.put("count", 20).put("total_matches", 30).put("offset", 0);
                var clubs = root.putArray("clubs");
                for (int i = 0; i < 20; i++) clubs.addObject().put("NAME", "Club " + i)
                        .put("REPUTATION", 6000 - i).put("TRANSFER_BUDGET", 1_000_000);
            }
            case "fm26_get_player_details" -> root.putArray("players").addObject()
                    .put("unique_id", 7).put("name", "Player").put("ca", 140)
                    .put("attributes", "technical ".repeat(50));
            case "fm26_get_staff_details" -> root.putArray("staff").addObject()
                    .put("staff_unique_id", 8).put("name", "Coach")
                    .put("attributes", "coaching ".repeat(50));
            case "fm26_get_staff_coaching_roles" -> root.putArray("roles").addObject()
                    .put("role", "attacking").put("score_20", 17).put("stars", 4);
            case "fm26_transfer_shortlist", "fm26_recruit_for_tactic_slot",
                    "fm26_find_replacements" -> root.putArray("candidates").addObject()
                    .put("player_unique_id", 7).put("score", 82).put("price_fit", "within_budget");
            case "fm26_compare_players" -> root.putArray("players").addObject()
                    .put("player_unique_id", 7).put("score", 82).put("strengths", "passing");
            case "fm26_get_data_status" -> root.put("state", "loaded").put("freshness", "verified_current");
            case "fm26_refresh_data" -> root.put("refreshed", true).put("players", 1000);
            case "fm26_create_shortlist_file" -> root.put("file", "shortlist.fmf").put("count", 1);
            case "fm26_update_recruitment_case" -> root.put("player_unique_id", 7)
                    .put("deal_stage", "monitoring");
            case "fm26_get_recruitment_board" -> root.putArray("cases").addObject()
                    .put("player_unique_id", 7).put("deal_stage", "monitoring");
            default -> fail(name);
        }
        return root;
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private CompactFmToolCallback callback(String name, String result) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(new DefaultToolDefinition(
                name, "test", "{\"type\":\"object\",\"properties\":{}}"));
        when(delegate.call(anyString())).thenReturn(result);
        return new CompactFmToolCallback(delegate, json);
    }

    private ObjectNode fixture(String tool) {
        ObjectNode root = json.createObjectNode();
        root.putObject("snapshot").put("snapshot_id", "test-snapshot")
                .put("refresh_policy", "verbose policy ".repeat(40));
        root.put("score", 82).put("warning", "Watch fatigue").put("coverage", "thin");
        switch (tool) {
            case "fm26_analyze_squad" -> {
                root.putArray("position_depth").addObject().put("coverage", "thin")
                        .putArray("best_options").add(player());
                root.putArray("tactic_slots").addObject().putArray("best_options")
                        .add(player().set("fit", fit()));
                root.set("optimized_lineup", lineup());
            }
            case "fm26_optimize_lineup" -> root.set("lineup", lineup().path("lineup"));
            case "fm26_plan_squad_moves" -> {
                root.putObject("finances").put("known_remaining_budget", 1_000_000);
                for (String phase : new String[] {"before", "after"}) {
                    root.putArray("position_depth_" + phase).addObject().putArray("best_options").add(player());
                    root.putObject("tactic_coverage_" + phase).putArray("slots")
                            .addObject().putArray("best_options").add(player().set("fit", fit()));
                    root.set("optimized_lineup_" + phase, lineup());
                }
            }
            case "fm26_get_role_attributes" -> root.putArray("roles").addObject().put("role", "DC")
                    .putArray("primary_attributes").addObject()
                    .put("name", "Heading").put("player_attribute_column", "HEADING");
            case "fm26_find_players" -> root.putArray("players").addObject()
                    .put("unique_id", 7).put("name", "A").put("ca", 140)
                    .put("asking_price", 1_000_000).put("extra", "omitted");
            case "fm26_find_staff" -> root.putArray("staff").addObject()
                    .put("staff_unique_id", 8).put("name", "Coach")
                    .putObject("best_coaching_role").put("stars", 4).put("attributes", "omitted");
            case "fm26_get_club_context" -> {
                root.putObject("club").put("transfer_budget", 1_000_000);
                root.putArray("squad").addObject().put("unique_id", 7).put("ca", 140)
                        .put("extra", "omitted");
            }
            default -> fail(tool);
        }
        return root;
    }

    private ObjectNode lineup() {
        ObjectNode lineup = json.createObjectNode();
        lineup.putArray("lineup").addObject().put("tactic_slot", 1)
                .set("player", player());
        ((ObjectNode) lineup.path("lineup").get(0)).set("fit", fit());
        return lineup;
    }

    private ObjectNode player() {
        return json.createObjectNode().put("player_unique_id", 7).put("name", "A")
                .put("ca", 140).put("position_score", 18).put("extra", "omitted");
    }

    private ObjectNode fit() {
        return json.createObjectNode().put("overall", 80).put("viable", true)
                .put("in_possession", "omitted");
    }
}
