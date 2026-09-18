package com.github.fmaiassistent.openrouter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenRouter's separate 64 KiB transport limit, applied after shared formatting. */
final class OpenRouterToolResultFormatter {
    private static final int RESULT_LIMIT = 64 * 1024;

    private OpenRouterToolResultFormatter() { }

    static String limit(ObjectMapper json, String result) {
        if (bytes(result) <= RESULT_LIMIT) return result;
        try {
            if (!(json.readTree(result) instanceof ObjectNode root)) return result;
            fitLargeLists(json, root);
            return root.has("_openrouter_response") ? json.writeValueAsString(root) : result;
        } catch (RuntimeException ignored) {
            return result;
        }
    }

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

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
