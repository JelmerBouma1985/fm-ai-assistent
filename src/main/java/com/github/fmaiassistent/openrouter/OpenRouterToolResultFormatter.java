package com.github.fmaiassistent.openrouter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small, explicit views of FM results for OpenRouter. Public MCP responses stay untouched. */
final class OpenRouterToolResultFormatter {
    private static final int RESULT_LIMIT = 64 * 1024;
    private static final Set<String> PLAYER_SUMMARY = Set.of(
            "player_unique_id", "name", "age", "position_text", "ca", "pa", "injured", "contract_end");
    private static final Set<String> SEARCH_PLAYER_SUMMARY = Set.of(
            "unique_id", "name", "age", "gender", "nationality", "club", "playing_club", "position_text",
            "ca", "pa", "asking_price", "salary_weekly_raw", "contract_end_date", "transfer_listed",
            "listed_for_loan", "transfer_agreed", "injured");
    private static final Set<String> FIT_SUMMARY = Set.of("slot", "position_fit", "role_fit", "overall", "viable");
    private static final Set<String> COACHING_SUMMARY = Set.of("role", "label", "score_20", "stars", "quality");

    private OpenRouterToolResultFormatter() { }

    static String compact(ObjectMapper json, String toolName, String result) {
        JsonNode parsed;
        try { parsed = json.readTree(result); }
        catch (RuntimeException ignored) { return result; }
        if (!(parsed instanceof ObjectNode root)) return result;

        String detail = switch (toolName) {
            case "fm26_analyze_squad" -> compactSquad(root);
            case "fm26_optimize_lineup" -> compactLineup(root)
                    ? "Repeated player fields and detailed fit subfields were removed. Lineup choices, scores and warnings remain."
                    : null;
            case "fm26_plan_squad_moves" -> compactPlan(root);
            case "fm26_get_role_attributes" -> compactRoleAttributes(json, root);
            case "fm26_find_players" -> compactPlayerSearch(root);
            case "fm26_find_staff" -> compactStaffSearch(root);
            case "fm26_get_club_context" -> compactClubContext(root);
            default -> null;
        };
        if (detail != null) root.putObject("_openrouter_response").put("compacted", true).put("detail", detail);
        if (bytes(json.writeValueAsString(root)) > RESULT_LIMIT) fitLargeLists(json, root);
        return detail == null && !root.has("_openrouter_response") ? result : json.writeValueAsString(root);
    }

    private static String compactSquad(ObjectNode squad) {
        if (!squad.has("position_depth") || !squad.has("tactic_slots") || !squad.has("optimized_lineup")) return null;
        compactPositionDepth(squad.path("position_depth"));
        compactTacticSlots(squad.path("tactic_slots"));
        compactLineup(squad.path("optimized_lineup"));
        return "Repeated candidate player fields and detailed fit subfields were removed. "
                + "Coverage, scores, player IDs, named risk lists and other top-level sections remain. "
                + "Use fm26_get_player_details for a player's full profile.";
    }

    private static String compactPlan(ObjectNode plan) {
        if (!plan.has("position_depth_before") || !plan.has("position_depth_after")) return null;
        compactPositionDepth(plan.path("position_depth_before"));
        compactPositionDepth(plan.path("position_depth_after"));
        for (String phase : new String[] {"tactic_coverage_before", "tactic_coverage_after"}) {
            compactTacticSlots(plan.path(phase).path("slots"));
        }
        compactLineup(plan.path("optimized_lineup_before"));
        compactLineup(plan.path("optimized_lineup_after"));
        return "Repeated candidate player fields and detailed fit subfields were removed. "
                + "Before/after coverage, finances, lineup scores, player IDs and warnings remain.";
    }

    private static String compactRoleAttributes(ObjectMapper json, ObjectNode root) {
        if (!root.path("roles").isArray()) return null;
        for (JsonNode role : root.path("roles")) {
            for (String priority : new String[] {"primary_attributes", "secondary_attributes"}) {
                if (!(role.path(priority) instanceof ArrayNode attributes)) continue;
                for (int index = 0; index < attributes.size(); index++) {
                    JsonNode attribute = attributes.get(index);
                    if (!attribute.isObject()) continue;
                    String column = attribute.path("player_attribute_column").asString();
                    if (column.isBlank()) column = attribute.path("name").asString();
                    attributes.set(index, json.valueToTree(column));
                }
            }
        }
        return "Primary and secondary attribute arrays contain FM player column names. "
                + "Repeated display labels and sort metadata were removed; role and phase are unchanged.";
    }

    private static String compactPlayerSearch(ObjectNode root) {
        if (!root.path("players").isArray()) return null;
        for (JsonNode player : root.path("players")) retain(player, SEARCH_PLAYER_SUMMARY);
        return "Search rows contain player identity, club, position, ability, price, wage and availability. "
                + "Use fm26_get_player_details with unique_id for the omitted fields.";
    }

    private static String compactStaffSearch(ObjectNode root) {
        if (!root.path("staff").isArray()) return null;
        for (JsonNode staff : root.path("staff")) retain(staff.path("best_coaching_role"), COACHING_SUMMARY);
        return "Detailed coaching attribute breakdowns were removed from search rows. "
                + "Role, score and stars remain; use fm26_get_staff_details for full attributes.";
    }

    private static String compactClubContext(ObjectNode root) {
        if (!root.path("squad").isArray()) return null;
        for (JsonNode player : root.path("squad")) retain(player, SEARCH_PLAYER_SUMMARY);
        return "Squad rows contain the main player fields. Use fm26_get_player_details for full profiles.";
    }

    private static void compactPositionDepth(JsonNode positions) {
        for (JsonNode position : positions) {
            for (JsonNode candidate : position.path("best_options")) {
                retain(candidate, PLAYER_SUMMARY, "position_score");
            }
        }
    }

    private static void compactTacticSlots(JsonNode slots) {
        for (JsonNode slot : slots) {
            for (JsonNode candidate : slot.path("best_options")) {
                retain(candidate, PLAYER_SUMMARY, "fit");
                compactFit(candidate.path("fit"));
            }
        }
    }

    private static boolean compactLineup(JsonNode lineup) {
        if (!lineup.isObject() || !lineup.has("lineup")) return false;
        for (JsonNode assignment : lineup.path("lineup")) {
            retain(assignment.path("player"), PLAYER_SUMMARY);
            compactFit(assignment.path("fit"));
            compactAlternatives(assignment.path("alternatives"));
        }
        for (JsonNode slot : lineup.path("unfilled_slots")) compactAlternatives(slot.path("alternatives"));
        return true;
    }

    private static void compactAlternatives(JsonNode alternatives) {
        for (JsonNode candidate : alternatives) {
            retain(candidate, PLAYER_SUMMARY, "fit", "assigned_tactic_slot", "disrupts_another_slot");
            compactFit(candidate.path("fit"));
        }
    }

    private static void compactFit(JsonNode fit) { retain(fit, FIT_SUMMARY); }

    private static void fitLargeLists(ObjectMapper json, ObjectNode root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int maximum : new int[] {20, 8, 3, 1}) {
            for (String key : root.propertyNames()) {
                if (!(root.path(key) instanceof ArrayNode array) || array.size() <= maximum) continue;
                counts.putIfAbsent(key, array.size());
                while (array.size() > maximum) array.remove(array.size() - 1);
            }
            if (!counts.isEmpty()) {
                ObjectNode metadata = root.path("_openrouter_response") instanceof ObjectNode existing
                        ? existing : root.putObject("_openrouter_response");
                metadata.put("partial", true);
                metadata.put("detail", "Only the first rows of listed sections were sent. Counts show the original totals. "
                        + "Ask for a narrower query or use the tool's limit and offset where available.");
                ObjectNode sections = metadata.putObject("partial_arrays");
                counts.forEach((key, total) -> sections.putObject(key).put("total", total)
                        .put("sent", root.path(key).size()));
            }
            if (bytes(json.writeValueAsString(root)) <= RESULT_LIMIT) return;
        }
    }

    private static void retain(JsonNode value, Set<String> keep, String... extra) {
        if (!(value instanceof ObjectNode object)) return;
        Set<String> extras = Set.of(extra);
        for (String name : new ArrayList<>(object.propertyNames())) {
            if (!keep.contains(name) && !extras.contains(name)) object.remove(name);
        }
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
