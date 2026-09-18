package com.github.fmaiassistent.openrouter;

import com.github.fmaiassistent.mcp.FmToolResultFormatter;

import com.github.fmaiassistent.ai.AiPromptContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One instance per backend session. No Vaadin, database, or browser state. */
public final class OpenRouterConversationService implements AutoCloseable {
    public enum Mode {
        FREE("openrouter/free", "Free — default"), AUTO("openrouter/auto", "Auto — may cost money"),
        SPECIFIC(null, "Specific model");
        final String model;
        final String label;
        Mode(String model, String label) { this.model = model; this.label = label; }
        public String model() { return model; }
        @Override public String toString() { return label; }
    }
    public record Item(String role, String text, Instant time) { }
    public record ModelOption(String id, String name, boolean free) {
        @Override public String toString() { return name + " (" + id + ") — " + (free ? "Free" : "may cost money"); }
    }
    public record Snapshot(String id, Mode mode, String requestedModel, String model, boolean running, List<Item> items) { }
    private static final int HISTORY_LIMIT = 512 * 1024;
    private static final Set<String> WRITE_TOOLS = Set.of(
            "fm26_refresh_data", "fm26_update_recruitment_case", "fm26_create_shortlist_file");
    private final ObjectMapper json;
    private final OpenRouterClient client;
    private final AiPromptContext context;
    private final String instructions;
    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();
    private final List<ObjectNode> definitions = new ArrayList<>();
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    private final Map<String, ModelOption> catalog = new LinkedHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private String key;
    private boolean closed;
    private long credentialGeneration;

    public OpenRouterConversationService(ObjectMapper json, OpenRouterClient client,
            ToolCallbackProvider provider, AiPromptContext context, String instructions) {
        this.json = json;
        this.client = client;
        this.context = context;
        this.instructions = instructions;
        for (ToolCallback tool : provider.getToolCallbacks()) {
            var definition = tool.getToolDefinition();
            tools.put(definition.name(), tool);
            ObjectNode function = json.createObjectNode().put("name", definition.name())
                    .put("description", definition.description());
            function.set("parameters", json.readTree(definition.inputSchema()));
            ObjectNode entry = json.createObjectNode().put("type", "function");
            entry.set("function", function);
            definitions.add(entry);
        }
    }

    public synchronized void connect(String value) {
        if (closed) throw new IllegalStateException("Session expired. Reload the application.");
        if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Enter a valid OpenRouter API key.");
        disconnect();
        key = value;
    }
    public synchronized boolean connected() { return key != null && !closed; }
    public synchronized String newConversation() {
        requireConnected();
        Conversation c = new Conversation();
        c.history.add(message("system", instructions + "\nOpenRouter tool responses are limited to 64 KiB. "
                + "Prefer compact task-level tools and small result limits for searches. "
                + "If a result is omitted for size, do not infer its contents or repeat completed writes. "
                + "Use a narrower read-only query or ask the user to narrow the question. "
                + "When a read-only tool reports a validation error, explain it and ask for missing information "
                + "or correct the arguments; do not repeat an unchanged failing call. "
                + "If FM data has not been loaded, ask the user to open their save and select Load data. "
                + "If data is loaded but the managed club is unavailable, ask for the club name."));
        conversations.put(c.id, c);
        return c.id;
    }
    public synchronized List<Snapshot> list() { return conversations.values().stream().map(this::snapshot).toList(); }
    public synchronized Snapshot get(String id) { return snapshot(conversation(id)); }
    public AutoCloseable subscribe(Consumer<Snapshot> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    public synchronized void selectMode(String id, Mode mode, boolean confirmed) {
        Conversation c = conversation(id);
        if (c.running) throw new IllegalStateException("Wait for the response before changing mode.");
        if (mode == null || mode == Mode.SPECIFIC || mode == Mode.AUTO && !confirmed)
            throw new IllegalArgumentException("Confirm that Auto may cost money.");
        c.mode = mode;
        c.requestedModel = null;
        c.model = null;
        publish(c);
    }
    public List<ModelOption> loadModels() {
        String credential;
        long generation;
        synchronized (this) {
            requireConnected();
            if (!catalog.isEmpty()) return List.copyOf(catalog.values());
            credential = key;
            generation = credentialGeneration;
        }
        JsonNode models = client.models(credential);
        if (!models.path("data").isArray()) throw new OpenRouterClient.Failure("OpenRouter model catalog was malformed. Try again later.");
        Map<String, ModelOption> found = new TreeMap<>();
        for (JsonNode entry : models.path("data")) {
            String id = entry.path("id").asString("");
            if (!id.matches("[A-Za-z0-9_.:/-]{1,160}") || id.equals(Mode.FREE.model)
                    || id.equals(Mode.AUTO.model)) continue;
            boolean toolsSupported = false;
            for (JsonNode parameter : entry.path("supported_parameters"))
                if ("tools".equals(parameter.asString())) toolsSupported = true;
            if (!toolsSupported) continue;
            if (!supportsText(entry.path("architecture").path("input_modalities"))
                    || !supportsText(entry.path("architecture").path("output_modalities"))) continue;
            boolean free = id.endsWith(":free");
            if (free && !zeroPricing(entry.path("pricing"))) continue;
            String name = entry.path("name").asString(id);
            found.put(id, new ModelOption(id, name.substring(0, Math.min(100, name.length())), free));
        }
        synchronized (this) {
            if (closed || key == null || credentialGeneration != generation)
                throw new IllegalStateException("OpenRouter session changed while loading models.");
            catalog.clear();
            found.values().stream().sorted(Comparator.comparing(ModelOption::free).reversed()
                    .thenComparing(ModelOption::name, String.CASE_INSENSITIVE_ORDER)).forEach(option -> catalog.put(option.id(), option));
            return List.copyOf(catalog.values());
        }
    }
    private static boolean supportsText(JsonNode modalities) {
        if (!modalities.isArray()) return true;
        for (JsonNode value : modalities) if ("text".equals(value.asString())) return true;
        return false;
    }
    private static boolean zeroPricing(JsonNode pricing) {
        for (String field : List.of("prompt", "completion", "request")) {
            try { if (new BigDecimal(pricing.path(field).asString()).signum() != 0) return false; }
            catch (RuntimeException e) { return false; }
        }
        return true;
    }
    public synchronized void selectModel(String id, String modelId, boolean confirmed) {
        Conversation c = conversation(id);
        if (c.running) throw new IllegalStateException("Wait for the response before changing model.");
        ModelOption option = catalog.get(modelId);
        if (option == null) throw new IllegalArgumentException("Choose a tool-capable model from the current OpenRouter catalog.");
        if (!option.free() && !confirmed) throw new IllegalArgumentException("Confirm that this model may cost money.");
        c.mode = Mode.SPECIFIC;
        c.requestedModel = option.id();
        c.model = null;
        publish(c);
    }
    public synchronized void send(String id, String text) {
        requireConnected();
        Conversation c = conversation(id);
        if (c.running) throw new IllegalStateException("A response is already active.");
        if (c.failed) throw new IllegalStateException("Start a new chat after an interrupted or failed turn. Completed writes remain completed.");
        if (text == null || text.isBlank()) return;
        if (bytes(text) > HISTORY_LIMIT) throw new IllegalArgumentException("Message too large. Ask a narrower question or start a new chat.");
        ObjectNode user = message("user", context.enrich("openrouter:" + c.id, text));
        c.history.add(user);
        try { request(c); } catch (RuntimeException e) { c.history.removeLast(); throw e; }
        c.items.add(new Item("You", text, Instant.now()));
        c.running = true;
        c.cancelled = false;
        c.deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        publish(c);
        c.task = Thread.ofVirtual().unstarted(() -> run(c));
        c.task.start();
        c.timer = deadlines.schedule(() -> stop(c.id, "Five-minute turn deadline reached."), 5, TimeUnit.MINUTES);
    }
    public synchronized void stop(String id) { stop(id, "Response stopped. Completed local writes remain completed."); }
    private synchronized void stop(String id, String reason) {
        Conversation c = conversations.get(id);
        if (c == null || !c.running) return;
        c.cancelled = true;
        c.failed = true;
        c.items.add(new Item("Status", reason, Instant.now()));
        if (c.task != null) c.task.interrupt();
        // Keep running until the worker exits: a local write may still be finishing.
        publish(c);
    }
    public synchronized void disconnect() {
        key = null;
        credentialGeneration++;
        catalog.clear();
        for (Conversation c : conversations.values()) {
            c.cancelled = true;
            if (c.task != null) c.task.interrupt();
            if (c.timer != null) c.timer.cancel(false);
            c.history.clear();
            c.items.clear();
        }
        conversations.clear();
    }
    @Override public synchronized void close() {
        disconnect();
        closed = true;
        listeners.clear();
        deadlines.shutdownNow();
        client.close();
    }

    private void run(Conversation c) {
        try {
            int toolCount = 0;
            for (int round = 0; round < 20; round++) {
                String credential;
                String body;
                synchronized (this) { check(c); credential = key; body = request(c); }
                Response response = new Response();
                client.stream(credential, body, chunk -> accept(c, response, chunk));
                synchronized (this) { check(c); }
                if (!Set.of("stop", "tool_calls").contains(response.finish))
                    throw new OpenRouterClient.Failure("OpenRouter returned an incomplete response. Start a new chat.");
                ObjectNode assistant = message("assistant", response.text.toString());
                if (response.calls.isEmpty()) {
                    if (!"stop".equals(response.finish)) throw malformed();
                    synchronized (this) { check(c); c.history.add(assistant); request(c); }
                    return;
                }
                if (!"tool_calls".equals(response.finish)) throw malformed();
                toolCount += response.calls.size();
                if (toolCount > 40) throw new OpenRouterClient.Failure("Turn reached 40 tool calls. Ask a narrower question in a new chat.");
                var calls = assistant.putArray("tool_calls");
                Set<String> ids = new HashSet<>();
                // Validate the entire batch before executing any local write.
                for (Call call : response.calls.values()) {
                    ToolCallback tool = tools.get(call.name.toString());
                    if (tool == null || call.id.isBlank() || !ids.add(call.id)) throw malformed();
                    JsonNode args;
                    try { args = json.readTree(call.arguments.toString()); }
                    catch (RuntimeException e) { throw malformed(); }
                    validate(args, json.readTree(tool.getToolDefinition().inputSchema()));
                    ObjectNode entry = calls.addObject().put("id", call.id).put("type", "function");
                    entry.putObject("function").put("name", call.name.toString()).put("arguments", call.arguments.toString());
                }
                synchronized (this) { check(c); c.history.add(assistant); request(c); }
                for (Call call : response.calls.values()) {
                    synchronized (this) {
                        check(c);
                        c.items.add(new Item("Activity", call.name + " · Running", Instant.now()));
                        publish(c);
                    }
                    String result;
                    String validationError = null;
                    try { result = tools.get(call.name.toString()).call(call.arguments.toString()); }
                    catch (Exception e) {
                        synchronized (this) {
                            check(c);
                            validationError = validationError(e);
                            if (validationError == null || WRITE_TOOLS.contains(call.name.toString())) {
                                throw new OpenRouterClient.Failure(call.name + " failed: "
                                        + (validationError == null ? "local tool execution error" : validationError)
                                        + ". Check local data before starting a new chat; completed writes are not replayed.");
                            }
                            result = json.createObjectNode().put("status", "validation_error")
                                    .put("tool", call.name.toString()).put("message", validationError)
                                    .put("guidance", "No analysis result is available. Correct the arguments or ask the user "
                                            + "for the missing information. If FM data has not been loaded, ask the user to "
                                            + "open their save and select Load data. Do not repeat the same failing call.")
                                    .toString();
                        }
                    }
                    synchronized (this) {
                        check(c);
                        if (result == null) result = "null";
                        if (bytes(result) > 64 * 1024) {
                            // Formatting is expendable; rows and fields must never be silently truncated.
                            try {
                                result = json.writer().without(SerializationFeature.INDENT_OUTPUT)
                                        .writeValueAsString(json.readTree(result));
                            } catch (RuntimeException ignored) { /* Non-JSON tool result. */ }
                        }
                        if (!WRITE_TOOLS.contains(call.name.toString())) {
                            JsonNode options = json.readTree(call.arguments.toString());
                            boolean full = "full".equals(options.path("responseDetail").asString())
                                    || !options.has("responseDetail")
                                    && "fm26_find_players".equals(call.name.toString())
                                    && "full".equalsIgnoreCase(options.path("detailLevel").asString());
                            if (!full) result = FmToolResultFormatter.compact(json, call.name.toString(), result);
                            result = OpenRouterToolResultFormatter.limit(json, result);
                        }
                        boolean omitted = bytes(result) > 64 * 1024;
                        if (omitted) {
                            result = json.createObjectNode().put("status", "result_omitted")
                                    .put("tool", call.name.toString()).put("result_bytes", bytes(result))
                                    .put("limit_bytes", 64 * 1024).put("execution_completed", true)
                                    .put("message", "The tool completed, but its result exceeds 64 KiB and was not sent. "
                                            + "Do not infer missing data. Do not repeat a completed write or refresh. "
                                            + "Use a narrower read-only query with fewer rows or fields, or ask the user to narrow the question.")
                                    .toString();
                        }
                        c.history.add(message("tool", result).put("tool_call_id", call.id));
                        c.items.add(new Item("Activity", call.name + (validationError != null
                                ? " · Needs attention: " + validationError : omitted
                                ? " · Completed; result exceeds 64 KiB and was omitted. Requesting a narrower answer."
                                : " · Completed"), Instant.now()));
                        request(c);
                        publish(c);
                    }
                }
            }
            throw new OpenRouterClient.Failure("Turn reached 20 model requests. Ask a narrower question in a new chat.");
        } catch (Exception e) {
            synchronized (this) {
                if (!c.cancelled) {
                    c.failed = true;
                    String message = e instanceof OpenRouterClient.Failure ? e.getMessage()
                            : "OpenRouter response failed. Start a new chat; completed local writes remain completed.";
                    c.items.add(new Item("Error", redact(message), Instant.now()));
                }
            }
        } finally {
            synchronized (this) {
                c.running = false;
                if (c.timer != null) c.timer.cancel(false);
                if (conversations.containsKey(c.id)) publish(c);
            }
        }
    }

    private String validationError(Exception error) {
        Throwable cause = error;
        // Spring wraps exceptions thrown by the actual FM method.
        while (cause instanceof ToolExecutionException && cause.getCause() != null) cause = cause.getCause();
        if (!(cause instanceof IllegalArgumentException) || cause.getCause() != null
                || cause.getMessage() == null || cause.getMessage().isBlank()) return null;
        String message = redact(cause.getMessage()).replaceAll("\\p{Cntrl}", " ");
        return message.substring(0, Math.min(message.length(), 1024));
    }

    private synchronized void accept(Conversation c, Response response, JsonNode chunk) {
        check(c);
        if (chunk.path("model").isString()) c.model = redact(chunk.path("model").asString());
        for (JsonNode choice : chunk.path("choices")) {
            if (choice.path("index").asInt(0) != 0) continue;
            if (choice.path("finish_reason").isString()) response.finish = choice.path("finish_reason").asString();
            JsonNode delta = choice.path("delta");
            if (delta.path("content").isString()) response.text.append(delta.path("content").asString());
            if (bytes(response.text.toString()) > HISTORY_LIMIT) throw new OpenRouterClient.Failure("Response exceeds history limit. Start a narrower chat.");
            for (JsonNode node : delta.path("tool_calls")) {
                int index = node.path("index").asInt(-1);
                if (index < 0 || index >= 40) throw malformed();
                Call call = response.calls.computeIfAbsent(index, ignored -> new Call());
                if (node.has("id")) call.id += node.path("id").asString("");
                if (node.has("type") && !node.path("type").asString().equals("function")) throw malformed();
                call.name.append(node.path("function").path("name").asString(""));
                call.arguments.append(node.path("function").path("arguments").asString(""));
                if (call.arguments.length() > HISTORY_LIMIT || call.name.length() > 256 || call.id.length() > 256) throw malformed();
            }
        }
        if (!response.text.isEmpty()) {
            Item item = new Item("OpenRouter", redact(response.text.toString()), Instant.now());
            if (response.itemIndex < 0) { response.itemIndex = c.items.size(); c.items.add(item); }
            else c.items.set(response.itemIndex, item);
        }
        publish(c);
    }
    private String request(Conversation c) {
        ObjectNode body = json.createObjectNode().put("model", c.mode == Mode.SPECIFIC ? c.requestedModel : c.mode.model).put("stream", true);
        body.set("messages", json.valueToTree(c.history));
        body.set("tools", json.valueToTree(definitions));
        // No fallback models, plugins, retries, or account privacy overrides.
        String value = json.writeValueAsString(body);
        if (bytes(value) > HISTORY_LIMIT) throw new OpenRouterClient.Failure("Request history exceeds 512 KiB. Ask a narrower question or start a new chat.");
        return value;
    }
    private static void validate(JsonNode value, JsonNode schema) {
        validate(value, schema, schema);
    }
    private static void validate(JsonNode value, JsonNode schema, JsonNode root) {
        if (value == null) throw malformed();
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asString();
            if (!ref.startsWith("#/")) throw malformed();
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) throw malformed();
            validate(value, target, root);
            return;
        }
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            boolean matches = false;
            for (JsonNode option : type) if (matches(value, option.asString())) matches = true;
            if (!matches) throw malformed();
        } else if (type.isString() && !matches(value, type.asString())) throw malformed();
        if (schema.path("enum").isArray()) {
            boolean found = false;
            for (JsonNode allowed : schema.path("enum")) if (allowed.equals(value)) found = true;
            if (!found) throw malformed();
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) if (!value.has(required.asString())) throw malformed();
            for (String name : value.propertyNames()) {
                if (schema.path("properties").has(name)) {
                    validate(value.path(name), schema.path("properties").path(name), root);
                } else if (schema.path("additionalProperties").isObject()) {
                    validate(value.path(name), schema.path("additionalProperties"), root);
                } else if (!schema.path("additionalProperties").asBoolean(false)) throw malformed();
            }
        } else if (value.isArray()) {
            for (JsonNode element : value) validate(element, schema.path("items"), root);
        }
    }
    private static boolean matches(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isString(); case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber(); case "number" -> value.isNumber();
            case "null" -> value.isNull(); default -> false;
        };
    }
    private static OpenRouterClient.Failure malformed() {
        return new OpenRouterClient.Failure("OpenRouter requested an unknown tool or malformed tool arguments. No further tools were run. Start a new chat.");
    }
    private void check(Conversation c) {
        if (c.cancelled || closed || key == null || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (System.nanoTime() >= c.deadline) throw new OpenRouterClient.Failure("Five-minute turn deadline reached. Start a new chat.");
    }
    private void requireConnected() { if (!connected()) throw new IllegalStateException("Connect an OpenRouter API key first."); }
    private Conversation conversation(String id) {
        Conversation c = conversations.get(id);
        if (c == null) throw new IllegalArgumentException("Conversation is not in this session.");
        return c;
    }
    private Snapshot snapshot(Conversation c) { return new Snapshot(c.id, c.mode, c.requestedModel, c.model, c.running, List.copyOf(c.items)); }
    private void publish(Conversation c) {
        Snapshot value = snapshot(c);
        for (Consumer<Snapshot> listener : listeners) {
            try { listener.accept(value); } catch (RuntimeException ignored) { /* Detached UI. */ }
        }
    }
    private String redact(String value) { return key == null ? value : value.replace(key, "[redacted]"); }
    private ObjectNode message(String role, String text) { return json.createObjectNode().put("role", role).put("content", text); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static final class Conversation {
        final String id = UUID.randomUUID().toString();
        final List<ObjectNode> history = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        Mode mode = Mode.FREE;
        String requestedModel;
        String model;
        boolean running, cancelled, failed;
        long deadline;
        Thread task;
        ScheduledFuture<?> timer;
    }
    private static final class Response {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, Call> calls = new TreeMap<>();
        String finish = "";
        int itemIndex = -1;
    }
    private static final class Call {
        String id = "";
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
