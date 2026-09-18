package com.github.fmaiassistent.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Adds a response-detail choice at the shared callback boundary. */
public final class CompactFmToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ObjectMapper json;
    private final ToolDefinition definition;

    public CompactFmToolCallback(ToolCallback delegate, ObjectMapper json) {
        this.delegate = delegate;
        this.json = json;
        ToolDefinition original = delegate.getToolDefinition();
        ObjectNode schema = (ObjectNode) json.readTree(original.inputSchema());
        schema.withObject("properties").putObject("responseDetail")
                .put("type", "string")
                .put("description", "compact (default) or full. Full returns the original result.")
                .putArray("enum").add("compact").add("full");
        definition = new DefaultToolDefinition(original.name(), original.description(), json.writeValueAsString(schema));
    }

    @Override public ToolDefinition getToolDefinition() { return definition; }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return call(input, null); }

    @Override
    public String call(String input, ToolContext context) {
        ObjectNode arguments = (ObjectNode) json.readTree(input);
        String detail = arguments.path("responseDetail").asString();
        if (!detail.isEmpty() && !detail.equals("compact") && !detail.equals("full")) {
            throw new IllegalArgumentException("responseDetail must be compact or full");
        }
        arguments.remove("responseDetail");
        String result = context == null ? delegate.call(json.writeValueAsString(arguments))
                : delegate.call(json.writeValueAsString(arguments), context);
        boolean full = detail.equals("full") || detail.isEmpty()
                && definition.name().equals("fm26_find_players")
                && arguments.path("detailLevel").asString().equalsIgnoreCase("full");
        return full ? result : FmToolResultFormatter.compact(json, definition.name(), result);
    }
}
