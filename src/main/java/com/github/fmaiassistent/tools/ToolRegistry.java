package com.github.fmaiassistent.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;

/** The actual registered callbacks, wrapped once at the shared execution boundary. */
public final class ToolRegistry implements ToolCallbackProvider {
    public record Descriptor(String id, String title, String description, String schema,
                             ToolInfo.Category category, ToolInfo.Effect effect,
                             boolean snapshot, boolean tactic, boolean managedClub, boolean career) { }
    public record Invocation(String conversationId, String responseId,
                             Supplier<Map<String, ToolPolicy.Access>> selection) { }
    public static final class Denied extends RuntimeException {
        private final String code;
        public Denied(String code, String message) { super(code + ": " + message); this.code = code; }
        public String code() { return code; }
    }
    private final Map<String, ToolCallback> raw = new LinkedHashMap<>();
    private final Map<String, Descriptor> catalog = new LinkedHashMap<>();
    private final ToolCallback[] callbacks;
    private final ToolPolicy policy;
    private final ToolAvailability availability;
    private final ToolActivity activity;

    public ToolRegistry(ToolCallback[] tools, Object[] owners, ObjectMapper json, ToolPolicy policy,
                        ToolAvailability availability, ToolActivity activity) {
        this.policy = policy; this.availability = availability; this.activity = activity;
        Map<String, ToolInfo> info = new HashMap<>();
        for (Object owner : owners) for (var method : org.springframework.aop.support.AopUtils.getTargetClass(owner).getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && method.isAnnotationPresent(ToolInfo.class)) {
                String id = tool.name().isBlank() ? method.getName() : tool.name();
                if (info.putIfAbsent(id, method.getAnnotation(ToolInfo.class)) != null)
                    throw new IllegalArgumentException("Duplicate tool metadata ID.");
            }
        }
        if (tools.length > 256) throw new IllegalArgumentException("Tool catalog exceeds limit.");
        int schemaBytes = 0;
        for (ToolCallback callback : tools) {
            var definition = callback.getToolDefinition();
            String id = definition.name();
            if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,128}") || raw.putIfAbsent(id, callback) != null)
                throw new IllegalArgumentException("Invalid or duplicate tool ID.");
            String schema = definition.inputSchema();
            if (schema == null || schema.length() > 64 * 1024 || (schemaBytes += schema.length()) > 512 * 1024)
                throw new IllegalArgumentException("Tool schema exceeds limit.");
            var parsed = json.readTree(schema);
            if (parsed == null || !parsed.isObject() || !"object".equals(parsed.path("type").asString()))
                throw new IllegalArgumentException("Tool input schema must be an object.");
            ToolInfo metadata = info.get(id);
            String title = id.replaceFirst("^fm27_", "").replace('_', ' ');
            title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
            String description = Objects.requireNonNull(definition.description());
            if (description.length() > 16 * 1024) throw new IllegalArgumentException("Tool description exceeds limit.");
            catalog.put(id, new Descriptor(id, title, description, schema,
                    metadata == null ? ToolInfo.Category.OTHER : metadata.category(),
                    metadata == null ? ToolInfo.Effect.UNKNOWN : metadata.effect(),
                    metadata != null && metadata.snapshot(), metadata != null && metadata.tactic(),
                    metadata != null && metadata.managedClub(), metadata != null && metadata.career()));
        }
        callbacks = raw.entrySet().stream().map(entry -> new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return entry.getValue().getToolDefinition(); }
            @Override public ToolMetadata getToolMetadata() { return entry.getValue().getToolMetadata(); }
            @Override public String call(String input) { return execute(entry.getKey(), input, null, null); }
            @Override public String call(String input, ToolContext context) { return execute(entry.getKey(), input, null, context); }
        }).toArray(ToolCallback[]::new);
    }
    @Override public ToolCallback[] getToolCallbacks() { return callbacks.clone(); }
    public List<Descriptor> descriptors() { return List.copyOf(catalog.values()); }
    public Descriptor descriptor(String id) {
        Descriptor descriptor = catalog.get(id);
        if (descriptor == null) throw new Denied("unknown_tool", "Unknown tool. Refresh the tool catalog.");
        return descriptor;
    }
    public ToolPolicy policy() { return policy; }
    public ToolActivity activity() { return activity; }
    public String unavailable(String id) {
        try { return availability.unavailable(descriptor(id)); }
        catch (RuntimeException exception) { return "Unknown/temporarily unavailable: retry after loading data."; }
    }
    public ToolPolicy.Access effective(String id, Map<String, ToolPolicy.Access> chat) {
        var global = policy.global(id, descriptor(id).effect());
        var local = chat.getOrDefault(id, ToolPolicy.Access.ALLOW);
        if (global == ToolPolicy.Access.DISABLED || local == ToolPolicy.Access.DISABLED) return ToolPolicy.Access.DISABLED;
        if (global == ToolPolicy.Access.ASK || local == ToolPolicy.Access.ASK) return ToolPolicy.Access.ASK;
        return ToolPolicy.Access.ALLOW;
    }
    public boolean advertised(String id, Map<String, ToolPolicy.Access> chat) {
        return effective(id, chat) != ToolPolicy.Access.DISABLED && unavailable(id).isEmpty();
    }
    public void setGlobal(String id, ToolPolicy.Access mode) {
        validateAccess(id, mode);
        policy.setGlobal(id, mode);
    }
    public void validateAccess(String id, ToolPolicy.Access mode) {
        var tool = descriptor(id);
        Objects.requireNonNull(mode);
        if (mode == ToolPolicy.Access.ASK && tool.effect() == ToolInfo.Effect.READ_ONLY)
            throw new IllegalArgumentException("Read-only tools support Allow or Disable.");
    }
    public Map<String, ToolPolicy.Access> preset(String preset) {
        if (!Set.of("all", "scouting", "tactics", "staff", "custom").contains(preset))
            throw new IllegalArgumentException("Unknown preset.");
        Map<String, ToolPolicy.Access> modes = new LinkedHashMap<>();
        for (var tool : descriptors()) {
            boolean enabled = switch (preset) {
                case "all" -> tool.effect() != ToolInfo.Effect.UNKNOWN;
                case "scouting" -> Set.of(ToolInfo.Category.SCOUTING, ToolInfo.Category.RECRUITMENT).contains(tool.category());
                case "tactics" -> Set.of(ToolInfo.Category.TACTICS, ToolInfo.Category.SQUAD).contains(tool.category());
                case "staff" -> tool.category() == ToolInfo.Category.STAFF;
                default -> policy.custom().contains(tool.id());
            };
            modes.put(tool.id(), enabled ? ToolPolicy.Access.ALLOW : ToolPolicy.Access.DISABLED);
        }
        return Map.copyOf(modes);
    }
    public String invoke(String id, String input, Invocation context) { return execute(id, input, context, null); }
    /** The HTTP transport uses this before SDK dispatch, including tools removed from discovery. */
    public void checkExternalDiscoveryDenial(String id) {
        var tool = catalog.get(id);
        if (tool == null || policy.global(id, tool.effect()) != ToolPolicy.Access.DISABLED) return;
        var event = activity.start(id, tool.category(), null, null);
        var denied = new Denied("tool_disabled", "Tool disabled by user policy.");
        activity.finish(event, ToolActivity.Status.DENIED, denied.getMessage());
        throw denied;
    }
    private String execute(String id, String input, Invocation context, ToolContext nativeContext) {
        var descriptor = descriptor(id);
        var event = activity.start(id, descriptor.category(), context == null ? null : context.conversationId(),
                context == null ? null : context.responseId());
        try {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            // Latest policy is read at invocation start; an already-running handler may finish.
            var access = effective(id, context == null ? Map.of() : context.selection().get());
            if (access == ToolPolicy.Access.DISABLED) throw new Denied("tool_disabled", "Tool disabled by user policy.");
            if (access == ToolPolicy.Access.ASK) throw new Denied("approval_required",
                    "Approval required. This integration cannot securely pause a tool call. Review its effects in Tools and choose Allow before retrying.");
            String reason = unavailable(id);
            if (!reason.isEmpty()) throw new Denied("tool_unavailable", reason);
            String result = nativeContext == null ? raw.get(id).call(input) : raw.get(id).call(input, nativeContext);
            activity.finish(event, ToolActivity.Status.SUCCEEDED, "Completed");
            return result;
        } catch (Denied denial) {
            activity.finish(event, ToolActivity.Status.DENIED, denial.code() + ": " + denial.getMessage());
            throw denial;
        } catch (RuntimeException failure) {
            activity.finish(event, Thread.currentThread().isInterrupted() || failure instanceof java.util.concurrent.CancellationException
                            ? ToolActivity.Status.CANCELLED : ToolActivity.Status.FAILED,
                    "Tool failed. Check arguments and loaded application data.");
            // Protocol adapters must never publish arbitrary handler exception messages or paths.
            Throwable cause = failure;
            while (cause instanceof org.springframework.ai.tool.execution.ToolExecutionException && cause.getCause() != null)
                cause = cause.getCause();
            if (cause instanceof IllegalArgumentException && cause.getCause() == null)
                throw new IllegalArgumentException("Invalid tool arguments. Check the tool schema and loaded application data.");
            throw new IllegalStateException("Local tool execution failed. Check application data before starting a new chat.");
        }
    }
}
