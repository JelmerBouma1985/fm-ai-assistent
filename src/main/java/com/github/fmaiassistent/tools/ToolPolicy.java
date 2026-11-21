package com.github.fmaiassistent.tools;

import com.github.fmaiassistent.service.AppSettingsService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Global preferences only; conversation selections are owned by their backend session. */
@Service
public class ToolPolicy {
    public enum Access { ALLOW, ASK, DISABLED }
    private final AppSettingsService settings;
    private final ObjectMapper json;
    private volatile Map<String, Access> global = Map.of();
    private volatile Set<String> custom = Set.of();
    private volatile String customName = "My selection";
    private volatile String notice = "";
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ToolPolicy(AppSettingsService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        try {
            String saved = settings.toolPolicy();
            if (saved == null) return;
            if (saved.length() > 64 * 1024) throw new IllegalArgumentException();
            var root = json.readTree(saved);
            if (root.path("version").asInt() != 1 || !root.path("access").isObject()) throw new IllegalArgumentException();
            Map<String, Access> modes = new LinkedHashMap<>();
            root.path("access").properties().forEach(entry -> {
                validateId(entry.getKey());
                modes.put(entry.getKey(), Access.valueOf(entry.getValue().asString()));
            });
            if (modes.size() > 256) throw new IllegalArgumentException();
            Set<String> selection = new LinkedHashSet<>();
            for (var id : root.path("custom")) { validateId(id.asString()); selection.add(id.asString()); }
            if (selection.size() > 256) throw new IllegalArgumentException();
            String name = root.path("customName").asString("My selection");
            validateName(name);
            global = Map.copyOf(modes);
            custom = Set.copyOf(selection);
            customName = name;
        } catch (RuntimeException exception) {
            notice = "Tool preferences could not be read. Defaults are active; a recovery copy is kept when you save.";
        }
    }
    public String notice() { return notice; }
    public Access global(String id, ToolInfo.Effect effect) {
        return global.getOrDefault(id, effect == ToolInfo.Effect.UNKNOWN ? Access.DISABLED : Access.ALLOW);
    }
    public Map<String, Access> globalOverrides() { return global; }
    public Set<String> custom() { return custom; }
    public String customName() { return customName; }

    public synchronized void replaceGlobal(Map<String, Access> replacement) {
        validateModes(replacement);
        persist(replacement, custom, customName);
        global = Map.copyOf(replacement);
        changed();
    }
    public synchronized void setGlobal(String id, Access access) {
        var next = new LinkedHashMap<>(global);
        next.put(id, Objects.requireNonNull(access));
        replaceGlobal(next);
    }
    public synchronized void updateGlobal(java.util.function.UnaryOperator<Map<String, Access>> update) {
        replaceGlobal(update.apply(new LinkedHashMap<>(global)));
    }
    public synchronized void saveCustom(String name, Set<String> ids) {
        validateName(name);
        if (ids.size() > 256) throw new IllegalArgumentException("Choose at most 256 tools.");
        ids.forEach(ToolPolicy::validateId);
        persist(global, ids, name.strip());
        custom = Set.copyOf(ids);
        customName = name.strip();
        changed();
    }
    public AutoCloseable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    private void changed() {
        for (var listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Detached observer. */ }
        }
    }
    private void persist(Map<String, Access> modes, Set<String> ids, String name) {
        var root = json.createObjectNode().put("version", 1).put("customName", name);
        root.set("access", json.valueToTree(modes));
        root.set("custom", json.valueToTree(ids.stream().sorted().toList()));
        String encoded = json.writeValueAsString(root);
        if (encoded.length() > 64 * 1024) throw new IllegalArgumentException("Tool preferences exceed size limit.");
        settings.saveToolPolicy(encoded, !notice.isEmpty());
        notice = "";
    }
    private static void validateModes(Map<String, Access> modes) {
        if (modes.size() > 256) throw new IllegalArgumentException("Too many tool preferences.");
        modes.forEach((id, access) -> { validateId(id); Objects.requireNonNull(access); });
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid tool ID.");
    }
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 60 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a preset name of 1-60 characters without control characters.");
    }
}
