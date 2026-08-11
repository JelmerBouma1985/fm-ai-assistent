package com.github.fmaiassistent.antigravity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Component
class AntigravityStreamParser {
    private static final Logger log = LoggerFactory.getLogger(AntigravityStreamParser.class);
    private final ObjectMapper mapper;

    AntigravityStreamParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Optional<AntigravityStreamEvent> parse(String line) {
        try {
            JsonNode root = mapper.readTree(line);
            return switch (root.path("event").asText()) {
                case "init" -> Optional.of(parseInit(root));
                case "step_update" -> Optional.of(parseStep(root.path("step_update")));
                case "result" -> Optional.of(parseResult(root.path("result")));
                default -> {
                    log.trace("Ignoring unknown Antigravity stream event: {}", root.path("event").asText());
                    yield Optional.empty();
                }
            };
        } catch (RuntimeException ex) {
            throw new AntigravityException(
                    AntigravityException.Code.STREAM_PARSE_FAILED,
                    "Antigravity returned malformed stream JSON", ex);
        }
    }

    private static AntigravityStreamEvent.Init parseInit(JsonNode root) {
        JsonNode init = root.path("init");
        return new AntigravityStreamEvent.Init(
                text(root, "conversation_id"),
                text(init, "cwd"),
                text(init, "permission_mode"),
                init.path("tools"));
    }

    private static AntigravityStreamEvent.Step parseStep(JsonNode step) {
        return new AntigravityStreamEvent.Step(
                text(step, "conversation_id"),
                step.path("step_index").asInt(-1),
                text(step, "state"),
                text(step, "step_type"),
                text(step, "text_delta"),
                text(step, "tool_name"),
                step.path("tool_info"),
                step.path("subagent_info"),
                step.path("duration_seconds").asDouble(0),
                step.path("usage").path("total_tokens").asLong(0));
    }

    private static AntigravityStreamEvent.Result parseResult(JsonNode result) {
        return new AntigravityStreamEvent.Result(
                text(result, "conversation_id"),
                text(result, "status"),
                text(result, "response"),
                errorText(result.path("error")),
                result.path("duration_seconds").asDouble(0),
                result.path("usage").path("total_tokens").asLong(0));
    }

    private static String errorText(JsonNode error) {
        if (error.isMissingNode() || error.isNull()) {
            return null;
        }
        if (error.isTextual()) {
            return error.asText();
        }
        String message = error.path("message").asText(null);
        return message == null ? error.toString() : message;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
