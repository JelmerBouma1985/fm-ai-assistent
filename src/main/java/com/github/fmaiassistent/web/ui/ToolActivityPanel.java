package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Discrete observed tool activity; no fake chat messages and no guessed CLI correlation. */
final class ToolActivityPanel extends Div {
    private final OpenRouterConversationService conversations;
    private final Supplier<String> selected;
    private final AtomicBoolean pending = new AtomicBoolean();
    private AutoCloseable subscription;
    private boolean expanded;
    ToolActivityPanel(OpenRouterConversationService conversations, Supplier<String> selected) {
        this.conversations = conversations; this.selected = selected;
        addClassName("tool-live-activity"); getElement().setAttribute("aria-label", "Observed tool activity");
    }
    @Override protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        var registry = conversations.toolRegistry();
        if (registry != null) subscription = registry.activity().subscribe(ignored -> {
            var ui = event.getUI();
            if (ui.isAttached() && pending.compareAndSet(false, true)) {
                try { ui.access(() -> { pending.set(false); if (isAttached()) refresh(); }); }
                catch (com.vaadin.flow.component.UIDetachedException detached) { pending.set(false); }
            }
        });
        refresh();
    }
    void refresh() {
        removeAll();
        var registry = conversations.toolRegistry();
        String id = selected.get();
        if (registry == null || id == null) return;
        var events = registry.activity().history(Set.of(id), false);
        for (var call : events.stream().limit(expanded ? 200 : 8).toList().reversed()) {
            add(new Div(new Span(registry.descriptor(call.toolId()).title() + " - " + call.status()
                    + (call.durationMillis() == null ? "" : " (" + call.durationMillis() + " ms)") + ": " + call.summary())));
        }
        if (events.size() > 8) add(new Button(expanded ? "Show recent calls" : "Show all " + events.size() + " calls", event -> {
            expanded = !expanded; refresh();
        }));
    }
    @Override protected void onDetach(DetachEvent event) {
        if (subscription != null) { try { subscription.close(); } catch (Exception ignored) { } subscription = null; }
        pending.set(false);
        super.onDetach(event);
    }
}
