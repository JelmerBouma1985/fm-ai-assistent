package com.github.fmaiassistent.openrouter;

import com.github.fmaiassistent.mcp.FmToolResultFormatter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterToolResultFormatterTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void roleCatalogKeepsAllRolesAndAttributePriorityWithinLimit() {
        var catalog = json.createObjectNode().put("count", 50);
        var roles = catalog.putArray("roles");
        for (int i = 0; i < 50; i++) {
            var role = roles.addObject().put("role_name", "Role " + i).put("phase", "In Possession");
            for (String priority : new String[] {"primary_attributes", "secondary_attributes"}) {
                var attributes = role.putArray(priority);
                for (int j = 0; j < 6; j++) attributes.addObject()
                        .put("name", "Attribute " + j).put("player_attribute_key", "attribute_" + j)
                        .put("player_attribute_column", "ATTRIBUTE_" + j).put("sort_order", j);
            }
        }
        String original = json.writeValueAsString(catalog);
        assertTrue(bytes(original) > 64 * 1024);
        JsonNode compact = json.readTree(compact(json, "fm26_get_role_attributes", original));
        assertEquals(50, compact.path("roles").size());
        assertEquals("ATTRIBUTE_0", compact.path("roles").get(0).path("primary_attributes").get(0).asString());
        assertEquals("ATTRIBUTE_5", compact.path("roles").get(49).path("secondary_attributes").get(5).asString());
        assertEquals("compact", compact.path("_response_detail").path("level").asString());
        assertTrue(bytes(compact.toString()) <= 64 * 1024);
        assertTrue(json.readTree(original).path("roles").get(0).path("primary_attributes").get(0).isObject());
    }

    @Test void playerAndStaffSearchesKeepIdsAndDecisionFields() {
        var players = json.createObjectNode().put("total_matches", 400);
        var playerRows = players.putArray("players");
        var staff = json.createObjectNode().put("total_matches", 200);
        var staffRows = staff.putArray("staff");
        for (int i = 0; i < 50; i++) {
            playerRows.addObject().put("unique_id", i + 1).put("name", "Player " + i)
                    .put("position_text", "DC").put("ca", 140).put("asking_price", 1000000)
                    .put("injury", "x".repeat(1600)).put("world_reputation", 1200);
            staffRows.addObject().put("staff_unique_id", i + 1).put("name", "Staff " + i)
                    .put("job", "Coach").put("club", "Test FC")
                    .putObject("best_coaching_role").put("role", "defending")
                    .put("stars", 4).put("attributes", "x".repeat(1600));
        }
        for (var entry : new Object[][] {{"fm26_find_players", players, "players"},
                {"fm26_find_staff", staff, "staff"}}) {
            String original = json.writeValueAsString(entry[1]);
            assertTrue(bytes(original) > 64 * 1024);
            String result = compact(json, (String) entry[0], original);
            JsonNode summary = json.readTree(result);
            assertEquals(50, summary.path((String) entry[2]).size());
            assertEquals("players".equals(entry[2]) ? 400 : 200,
                    summary.path("total_matches").asInt());
            assertTrue(bytes(result) <= 64 * 1024);
            if ("players".equals(entry[2])) {
                assertEquals(1, summary.path("players").get(0).path("unique_id").asInt());
                assertEquals(1000000, summary.path("players").get(0).path("asking_price").asInt());
                assertFalse(summary.path("players").get(0).has("injury"));
            } else {
                assertEquals(1, summary.path("staff").get(0).path("staff_unique_id").asInt());
                assertEquals(4, summary.path("staff").get(0).path("best_coaching_role").path("stars").asInt());
                assertFalse(summary.path("staff").get(0).path("best_coaching_role").has("attributes"));
            }
        }
    }

    @Test void squadMovePlanRetainsBeforeAfterAndFinances() {
        var plan = json.createObjectNode().put("club", "Test FC").put("optimized_team_score_delta", 4.5);
        plan.putObject("finances").put("known_remaining_budget", 1000000);
        for (String phase : new String[] {"before", "after"}) {
            var depth = plan.putArray("position_depth_" + phase);
            var coverage = plan.putObject("tactic_coverage_" + phase).putArray("slots");
            var lineup = plan.putObject("optimized_lineup_" + phase).putArray("lineup");
            for (int i = 0; i < 11; i++) {
                depth.addObject().put("position", "P" + i).put("coverage", "thin")
                        .putArray("best_options").add(detailedPlayer(i));
                var option = detailedPlayer(i);
                option.set("fit", fit());
                coverage.addObject().put("slot", i + 1).put("coverage", "thin")
                        .putArray("best_options").add(option);
                var assignment = lineup.addObject().put("tactic_slot", i + 1);
                assignment.set("player", detailedPlayer(i));
                assignment.set("fit", fit());
                assignment.putArray("alternatives").add(option.deepCopy());
            }
        }
        String original = json.writeValueAsString(plan);
        assertTrue(bytes(original) > 64 * 1024);
        String result = compact(json, "fm26_plan_squad_moves", original);
        JsonNode summary = json.readTree(result);
        assertTrue(bytes(result) <= 64 * 1024);
        assertEquals(1000000, summary.path("finances").path("known_remaining_budget").asInt());
        assertEquals(4.5, summary.path("optimized_team_score_delta").asDouble());
        assertEquals(11, summary.path("optimized_lineup_before").path("lineup").size());
        assertEquals(11, summary.path("optimized_lineup_after").path("lineup").size());
        assertEquals(11, summary.path("tactic_coverage_after").path("slots").size());
        assertFalse(summary.path("optimized_lineup_after").path("lineup").get(0)
                .path("player").has("nationality"));
    }

    @Test void standaloneLineupAndClubContextRetainIdentityAndScores() {
        var lineup = json.createObjectNode().put("team_score_total", 820);
        var assignment = lineup.putArray("lineup").addObject().put("tactic_slot", 1);
        assignment.set("player", detailedPlayer(7));
        assignment.set("fit", fit());
        assignment.putArray("alternatives").add(detailedPlayer(8).set("fit", fit()));
        JsonNode compactLineup = json.readTree(compact(json,
                "fm26_optimize_lineup", json.writeValueAsString(lineup)));
        assertEquals(820, compactLineup.path("team_score_total").asInt());
        assertEquals(8, compactLineup.path("lineup").get(0).path("player").path("player_unique_id").asInt());
        assertTrue(compactLineup.path("lineup").get(0).path("fit").has("overall"));
        assertFalse(compactLineup.path("lineup").get(0).path("fit").has("in_possession"));

        var context = json.createObjectNode();
        context.putObject("club").put("name", "Test FC").put("transfer_budget", 1000000);
        context.putObject("squad_summary").put("count", 1);
        context.putArray("squad").addObject().put("unique_id", 7).put("name", "Player 7")
                .put("ca", 150).put("injury", "minor");
        JsonNode compactContext = json.readTree(compact(json,
                "fm26_get_club_context", json.writeValueAsString(context)));
        assertEquals(1000000, compactContext.path("club").path("transfer_budget").asInt());
        assertEquals(7, compactContext.path("squad").get(0).path("unique_id").asInt());
        assertEquals(150, compactContext.path("squad").get(0).path("ca").asInt());
        assertFalse(compactContext.path("squad").get(0).has("injury"));
    }

    @Test void unexpectedLargeListsDeclareEveryOmittedRow() {
        var board = json.createObjectNode().put("count", 100);
        var cases = board.putArray("cases");
        for (int i = 0; i < 100; i++) cases.addObject().put("player_unique_id", i + 1)
                .put("note", "x".repeat(1000));
        String original = json.writeValueAsString(board);
        assertTrue(bytes(original) > 64 * 1024);
        String result = compact(json, "fm26_get_recruitment_board", original);
        JsonNode summary = json.readTree(result);
        assertTrue(bytes(result) <= 64 * 1024);
        assertEquals(100, summary.path("count").asInt());
        assertTrue(summary.path("cases").size() < 100);
        assertEquals(1, summary.path("cases").get(0).path("player_unique_id").asInt());
        assertEquals(100, summary.path("_openrouter_response").path("partial_arrays")
                .path("cases").path("total").asInt());
        assertEquals(summary.path("cases").size(), summary.path("_openrouter_response")
                .path("partial_arrays").path("cases").path("sent").asInt());
    }

    private ObjectNode detailedPlayer(int id) {
        return json.createObjectNode().put("player_unique_id", id + 1).put("name", "Player " + id)
                .put("age", 23).put("ca", 140).put("pa", 170).put("position_text", "DC")
                .put("nationality", "x".repeat(800)).put("club", "Test FC");
    }
    private ObjectNode fit() {
        var fit = json.createObjectNode().put("slot", 1).put("position_fit", 18)
                .put("role_fit", 16).put("overall", 80).put("viable", true);
        fit.putObject("in_possession").put("strengths", "x".repeat(400));
        fit.putObject("out_of_possession").put("gaps", "x".repeat(400));
        return fit;
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static String compact(JsonMapper json, String tool, String original) {
        return OpenRouterToolResultFormatter.limit(json, FmToolResultFormatter.compact(json, tool, original));
    }
}
