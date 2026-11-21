package com.github.fmaiassistent.tools;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Metadata only: never receives arguments, results, exception messages or credentials. */
@Service
public class ToolActivity {
    public enum Status { RUNNING, SUCCEEDED, FAILED, DENIED, CANCELLED }
    public record Event(String id, String toolId, ToolInfo.Category category, String source,
                        String conversationId, String responseId, Status status,
                        Instant started, Long durationMillis, String summary) { }
    private final LinkedHashMap<String, Event> events = new LinkedHashMap<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    public Event start(String tool, ToolInfo.Category category, String conversation, String response) {
        Event event = new Event(UUID.randomUUID().toString(), tool, category,
                conversation == null ? "MCP (external/CLI)" : "OpenRouter", conversation, response,
                Status.RUNNING, Instant.now(), null, "Running");
        record(event);
        return event;
    }
    public void finish(Event event, Status status, String safeSummary) {
        record(new Event(event.id(), event.toolId(), event.category(), event.source(), event.conversationId(),
                event.responseId(), status, event.started(),
                Math.max(0, java.time.Duration.between(event.started(), Instant.now()).toMillis()), safeSummary));
    }
    private void record(Event event) {
        synchronized (this) {
            // A cleared/evicted running event must not reappear when its handler finishes.
            if (event.status() != Status.RUNNING && !events.containsKey(event.id())) return;
            events.put(event.id(), event);
            if (event.conversationId() != null) {
                var same = events.values().stream().filter(e -> event.conversationId().equals(e.conversationId())).toList();
                for (int i = 0; i < same.size() - 200; i++) events.remove(same.get(i).id());
            }
            while (events.size() > 500) events.remove(events.keySet().iterator().next());
        }
        for (var listener : listeners) {
            try { listener.accept(event); } catch (RuntimeException ignored) { /* Detached observer. */ }
        }
    }
    /** Null conversation selects external calls only, never another browser session. */
    public synchronized List<Event> history(Set<String> ownedConversations, boolean includeExternal) {
        return events.values().stream().filter(e -> e.conversationId() == null ? includeExternal
                : ownedConversations.contains(e.conversationId())).toList().reversed();
    }
    public synchronized void clear(Set<String> ownedConversations, boolean includeExternal) {
        events.values().removeIf(e -> e.conversationId() == null ? includeExternal
                : ownedConversations.contains(e.conversationId()));
    }
    public AutoCloseable subscribe(Consumer<Event> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
