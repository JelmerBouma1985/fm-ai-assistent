package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.github.fmaiassistent.tools.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Local chat control; never a prompt and never forwards private input to an agent. */
final class ToolsDialog extends Dialog {
    private final ToolRegistry registry;
    private final OpenRouterConversationService conversations;
    private final String conversation;
    private final BooleanSupplier trusted;
    private final boolean trustedDisplay;
    private final Select<String> scope = new Select<>();
    private final TextField search = new TextField("Search tools");
    private final Select<String> filter = new Select<>();
    private final Div rows = new Div();
    private final Span notice = new Span();
    private final Grid<ToolActivity.Event> history = new Grid<>();
    private final Select<String> historyScope = new Select<>();
    private final ComboBox<ToolActivity.Status> status = new ComboBox<>("Status");
    private final ComboBox<ToolInfo.Category> category = new ComboBox<>("Category");
    private final Select<Integer> limit = new Select<>();
    private final Select<String> time = new Select<>();
    private AutoCloseable subscription;
    private AutoCloseable policySubscription;
    private final java.util.concurrent.atomic.AtomicBoolean pendingRefresh = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean fullRefresh = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong refreshVersion = new java.util.concurrent.atomic.AtomicLong();

    ToolsDialog(ToolRegistry registry, OpenRouterConversationService conversations, String conversation) {
        this(registry, conversations, conversation, ToolsDialog::trustedLoopback);
    }
    ToolsDialog(ToolRegistry registry, OpenRouterConversationService conversations, String conversation, BooleanSupplier trusted) {
        this.registry = registry; this.conversations = conversations; this.conversation = conversation; this.trusted = trusted;
        this.trustedDisplay = trusted.getAsBoolean();
        setHeaderTitle("Tools");
        setWidth("min(1100px, calc(100vw - 24px))");
        setMaxHeight("calc(100vh - 32px)");
        addClassName("tools-dialog");
        scope.setLabel("Policy scope");
        scope.setItems(conversation == null ? List.of("Global MCP settings")
                : List.of("This conversation", "Global MCP settings"));
        scope.setValue(conversation == null ? "Global MCP settings" : "This conversation");
        scope.addValueChangeListener(event -> render());
        filter.setLabel("Show tools"); filter.setItems("All", "Enabled", "Disabled", "Unavailable"); filter.setValue("All");
        filter.addValueChangeListener(event -> renderRows());
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(event -> renderRows());
        Span explanation = new Span("Global settings control every MCP client and OpenRouter. Conversation selections further restrict OpenRouter only. "
                + "Codex, Antigravity and Copilot use global MCP settings and retain their own permission prompts. "
                + "These tools never modify the Football Manager process or save.");
        Span approval = new Span("Ask before use returns approval_required: these integrations cannot securely pause and resume a concrete invocation. "
                + "Review the effect and choose Allow before retrying. Running tools may finish after policy changes.");
        explanation.getStyle().set("display", "block").set("margin-bottom", "0.75rem");
        approval.getStyle().set("display", "block").set("margin-bottom", "0.75rem");
        HorizontalLayout filters = new HorizontalLayout(search, filter);
        filters.setWrap(true);
        Span scopeLabel = new Span(conversation == null ? "Global MCP / CLI scope" : "Active OpenRouter conversation: " + conversation);
        scopeLabel.getStyle().set("display", "block").set("font-weight", "600").set("margin-bottom", "0.75rem");
        Div tools = new Div(scopeLabel,
                explanation, approval, filters, notice, rows,
                new Button("Reset to defaults", event -> change(() -> replace(Map.of()))));
        tools.getStyle().set("min-width", "0").set("overflow-wrap", "anywhere");
        rows.setWidthFull();
        TabSheet tabs = new TabSheet();
        tabs.setWidthFull();
        tabs.getStyle().set("min-width", "0").set("max-width", "100%");
        tabs.add("Tools", tools);
        tabs.add("Presets", presets());
        tabs.add("Activity", activity());
        add(scope, tabs);
        getFooter().add(new Button("Close", event -> close()));
        addOpenedChangeListener(event -> {
            if (event.isOpened()) {
                render();
                // Dialog.open() fires before its automatic attachment to the current UI.
                getUI().or(() -> Optional.ofNullable(com.vaadin.flow.component.UI.getCurrent())).ifPresent(ui -> {
                    subscription = registry.activity().subscribe(ignored -> {
                        requestRefresh(ui, false);
                    });
                    policySubscription = registry.policy().subscribe(() -> requestRefresh(ui, true));
                });
            } else unsubscribe();
        });
        addDetachListener(event -> unsubscribe());
        render();
    }
    static boolean trustedLoopback() {
        if (!(VaadinService.getCurrentRequest() instanceof VaadinServletRequest request)) return false;
        String address = request.getHttpServletRequest().getRemoteAddr();
        return address != null && (address.equals("::1") || address.equals("0:0:0:0:0:0:0:1")
                || address.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"));
    }
    private boolean global() { return "Global MCP settings".equals(scope.getValue()); }
    private Map<String, ToolPolicy.Access> selection() {
        return global() ? registry.policy().globalOverrides() : conversations.toolSelection(conversation);
    }
    private ToolPolicy.Access selected(ToolRegistry.Descriptor tool) {
        return global() ? registry.policy().global(tool.id(), tool.effect())
                : selection().getOrDefault(tool.id(), ToolPolicy.Access.ALLOW);
    }
    private Map<String, ToolPolicy.Access> chat() {
        return global() || conversation == null ? Map.of() : conversations.toolSelection(conversation);
    }
    private void replace(Map<String, ToolPolicy.Access> modes) {
        if (global()) {
            requireTrusted();
            registry.policy().replaceGlobal(modes);
        } else conversations.setToolSelection(conversation, modes);
    }
    private void requireTrusted() {
        if (!trusted.getAsBoolean()) throw new IllegalStateException("Global preferences can only be changed from a loopback browser.");
    }
    private void change(Runnable operation) {
        try { operation.run(); notice.setText(""); }
        catch (RuntimeException failure) { notice.setText("Could not change tool settings. "
                + (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                && failure.getMessage().startsWith("Global preferences") ? failure.getMessage() : "Check local settings access.")); }
        renderRows(); renderHistory();
    }
    private void render() {
        notice.setText(registry.policy().notice().isEmpty() ? (global() && !trustedDisplay
                ? "Global controls are read-only outside loopback access." : "") : registry.policy().notice());
        renderRows(); renderHistory();
    }
    private String availability(ToolRegistry.Descriptor tool) {
        var access = registry.effective(tool.id(), chat());
        if (access == ToolPolicy.Access.DISABLED) return "Disabled by user policy";
        if (!global() && !conversations.supportsTools(conversation)) return "Unsupported by current agent: choose a tool-capable model.";
        String unavailable = registry.unavailable(tool.id());
        if (!unavailable.isEmpty()) return unavailable;
        return access == ToolPolicy.Access.ASK ? "Approval required" : "Available";
    }
    private void renderRows() {
        rows.removeAll();
        if (conversation != null && !conversations.conversationIds().contains(conversation)) {
            notice.setText("This conversation has expired or disconnected. Close Tools and reopen it from an active chat.");
            return;
        }
        if (scope.getValue() == null || filter.getValue() == null) return;
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        for (ToolInfo.Category group : ToolInfo.Category.values()) {
            var all = registry.descriptors().stream().filter(tool -> tool.category() == group).toList();
            var visible = all.stream().filter(tool -> (tool.title() + " " + tool.id() + " " + tool.description() + " " + tool.category())
                    .toLowerCase(Locale.ROOT).contains(query)).filter(tool -> switch (filter.getValue()) {
                        case "Enabled" -> registry.effective(tool.id(), chat()) != ToolPolicy.Access.DISABLED;
                        case "Disabled" -> registry.effective(tool.id(), chat()) == ToolPolicy.Access.DISABLED;
                        case "Unavailable" -> !availability(tool).equals("Available");
                        default -> true;
                    }).toList();
            if (visible.isEmpty()) continue;
            long enabled = all.stream().filter(t -> registry.effective(t.id(), chat()) != ToolPolicy.Access.DISABLED).count();
            Button enable = new Button("Enable category", event -> bulk(all, ToolPolicy.Access.ALLOW));
            Button disable = new Button("Disable category", event -> bulk(all, ToolPolicy.Access.DISABLED));
            enable.setEnabled(!global() || trustedDisplay); disable.setEnabled(enable.isEnabled());
            HorizontalLayout heading = new HorizontalLayout(new Span(group + " - " + enabled + "/" + all.size() + " enabled"), enable, disable);
            heading.setWrap(true); rows.add(heading);
            for (var tool : visible) {
                Checkbox enabledBox = new Checkbox(tool.title(), selected(tool) != ToolPolicy.Access.DISABLED);
                enabledBox.setEnabled(!global() || trustedDisplay);
                enabledBox.addValueChangeListener(event -> change(() -> setAccess(tool.id(),
                        event.getValue() ? ToolPolicy.Access.ALLOW : ToolPolicy.Access.DISABLED)));
                HorizontalLayout row = new HorizontalLayout(enabledBox, new Span(availability(tool)), new Span(tool.effect().label()),
                        new Button("Details: " + tool.title(), event -> details(tool)));
                row.setWrap(true); row.addClassName("tools-row");
                row.getStyle().set("gap", "0.75rem").set("min-width", "0").set("box-sizing", "border-box");
                if (tool.effect() != ToolInfo.Effect.READ_ONLY) {
                    Select<ToolPolicy.Access> mode = new Select<>();
                    mode.setLabel("Access for " + tool.title()); mode.setItems(ToolPolicy.Access.values()); mode.setValue(selected(tool));
                    mode.setEnabled(!global() || trustedDisplay);
                    mode.addValueChangeListener(event -> change(() -> setAccess(tool.id(), event.getValue())));
                    row.add(mode);
                }
                rows.add(row);
            }
        }
        if (rows.getChildren().findAny().isEmpty()) rows.add(new Span("No tools match these filters."));
    }
    private void bulk(List<ToolRegistry.Descriptor> tools, ToolPolicy.Access mode) {
        change(() -> {
            java.util.function.UnaryOperator<Map<String, ToolPolicy.Access>> update = next -> {
                tools.forEach(tool -> next.put(tool.id(), mode)); return next;
            };
            if (global()) { requireTrusted(); registry.policy().updateGlobal(update); }
            else conversations.updateToolSelection(conversation, update);
        });
    }
    private void setAccess(String id, ToolPolicy.Access mode) {
        registry.validateAccess(id, mode);
        if (global()) { requireTrusted(); registry.setGlobal(id, mode); }
        else conversations.updateToolSelection(conversation, next -> { next.put(id, mode); return next; });
    }
    private Div presets() {
        Select<String> preset = new Select<>();
        preset.setLabel("Preset"); preset.setItems("all", "scouting", "tactics", "staff", "custom"); preset.setValue("all");
        preset.setItemLabelGenerator(id -> switch (id) {
            case "all" -> "All available"; case "scouting" -> "Scouting"; case "tactics" -> "Tactical analysis";
            case "staff" -> "Staff"; default -> registry.policy().customName() + " (Custom)";
        });
        TextField name = new TextField("Custom preset name"); name.setMaxLength(60); name.setValue(registry.policy().customName());
        Div content = new Div(new Span("Applies to the scope selected above. Conversation presets cannot override global restrictions. "
                + "All available enables discovered classified tools. Scouting selects player/recruitment tools; Tactical analysis selects tactics/squad; Staff selects staff tools. "
                + "Custom stores one editable named selection, without chat data. Reset restores default Allow for classified tools and Disable for unclassified tools."),
                preset, new Button("Apply preset", event -> change(() -> replace(registry.preset(preset.getValue())))),
                name, new Button("Save current selection as custom", event -> change(() -> {
                    requireTrusted();
                    Set<String> ids = new LinkedHashSet<>();
                    registry.descriptors().stream().filter(tool -> selected(tool) != ToolPolicy.Access.DISABLED).forEach(tool -> ids.add(tool.id()));
                    registry.policy().saveCustom(name.getValue(), ids);
                })));
        content.getStyle().set("display", "flex").set("flex-direction", "column").set("align-items", "flex-start").set("gap", "0.75rem");
        return content;
    }
    private void details(ToolRegistry.Descriptor tool) {
        if (conversation != null && !conversations.conversationIds().contains(conversation)) {
            renderRows(); return;
        }
        Dialog detail = new Dialog(); detail.setHeaderTitle(tool.title());
        detail.setWidth("min(800px, calc(100vw - 32px))"); detail.setMaxHeight("calc(100vh - 32px)");
        TextField id = new TextField("API / MCP name (select to copy)"); id.setValue(tool.id()); id.setReadOnly(true); id.setWidthFull();
        List<String> requirements = new ArrayList<>();
        if (tool.snapshot()) requirements.add("Loaded FM27 snapshot");
        if (tool.tactic()) requirements.add("Loaded tactic");
        if (tool.managedClub()) requirements.add("Employed club manager");
        if (tool.career()) requirements.add("Loaded career identity");
        long count = conversation == null ? 0 : registry.activity().history(Set.of(conversation), false).stream().filter(e -> e.toolId().equals(tool.id())).count();
        Pre schema = new Pre(tool.schema());
        schema.getStyle().set("white-space", "pre-wrap").set("overflow-wrap", "anywhere").set("max-height", "50vh").set("overflow", "auto");
        detail.add(id, new Div(new Span(tool.description())), new Div(new Span("Category: " + tool.category() + ". " + tool.effect().label())),
                new Div(new Span("Output: structured JSON. Requirements: " + (requirements.isEmpty() ? "None" : String.join(", ", requirements)))),
                new Div(new Span("Selected scope: " + selected(tool) + ". Effective access: " + registry.effective(tool.id(), chat()) + ". " + availability(tool))),
                new Div(new Span("Observed calls in active chat: " + count)),
                new Div(new Span("Example: Use " + tool.title().toLowerCase(Locale.ROOT) + " and explain the result.")),
                new Details("Input JSON Schema (read-only)", schema));
        detail.getFooter().add(new Button("Close", event -> detail.close())); detail.open();
    }
    private Div activity() {
        historyScope.setLabel("Activity scope"); historyScope.setItems("This conversation", "This session and external MCP");
        historyScope.setValue(conversation == null ? "This session and external MCP" : "This conversation");
        status.setItems(ToolActivity.Status.values()); status.setClearButtonVisible(true);
        category.setItems(ToolInfo.Category.values()); category.setClearButtonVisible(true);
        limit.setLabel("Last events"); limit.setItems(25, 100, 200, 500); limit.setValue(100);
        time.setLabel("Time range"); time.setItems("This session", "Last hour", "Last 5 minutes"); time.setValue("This session");
        historyScope.addValueChangeListener(event -> renderHistory()); status.addValueChangeListener(event -> renderHistory());
        category.addValueChangeListener(event -> renderHistory()); limit.addValueChangeListener(event -> renderHistory()); time.addValueChangeListener(event -> renderHistory());
        history.addColumn(event -> DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(event.started())).setHeader("Local time");
        history.addColumn(ToolActivity.Event::toolId).setHeader("Tool"); history.addColumn(ToolActivity.Event::source).setHeader("Source");
        history.addColumn(event -> event.conversationId() == null ? "External / uncorrelated" : event.conversationId()).setHeader("Conversation");
        history.addColumn(ToolActivity.Event::status).setHeader("Status");
        history.addColumn(event -> event.durationMillis() == null ? "Running" : event.durationMillis() + " ms").setHeader("Duration");
        history.addColumn(ToolActivity.Event::summary).setHeader("Summary");
        history.setHeight("360px"); history.setWidthFull();
        HorizontalLayout controls = new HorizontalLayout(historyScope, status, category, time, limit); controls.setWrap(true);
        return new Div(new Span("Observed calls only. Metadata remains in memory: at most 200 events per conversation and 500 application-wide. "
                + "Arguments, results, keys and chat text are never recorded here. Other browser sessions' conversations are excluded. "
                + "External MCP events are visible only from loopback and are never attributed to a chat."), controls, history,
                new Button("Refresh activity", event -> renderHistory()), new Button("Clear session history", event -> {
                    registry.activity().clear(conversations.conversationIds(), trusted.getAsBoolean()); renderHistory();
                }));
    }
    private void renderHistory() {
        if (limit.getValue() == null || historyScope.getValue() == null || time.getValue() == null) return;
        boolean all = !"This conversation".equals(historyScope.getValue());
        Set<String> owned = all ? conversations.conversationIds() : conversation == null ? Set.of() : Set.of(conversation);
        Instant since = switch (time.getValue()) { case "Last hour" -> Instant.now().minusSeconds(3600); case "Last 5 minutes" -> Instant.now().minusSeconds(300); default -> Instant.EPOCH; };
        history.setItems(registry.activity().history(owned, all && trustedDisplay).stream()
                .filter(event -> status.getValue() == null || status.getValue() == event.status())
                .filter(event -> category.getValue() == null || category.getValue() == event.category())
                .filter(event -> !event.started().isBefore(since)).limit(limit.getValue()).toList());
    }
    private void unsubscribe() {
        if (subscription != null) { try { subscription.close(); } catch (Exception ignored) { } subscription = null; }
        if (policySubscription != null) { try { policySubscription.close(); } catch (Exception ignored) { } policySubscription = null; }
    }
    private void requestRefresh(com.vaadin.flow.component.UI ui, boolean full) {
        if (full) fullRefresh.set(true);
        refreshVersion.incrementAndGet();
        scheduleRefresh(ui);
    }
    private void scheduleRefresh(com.vaadin.flow.component.UI ui) {
        if (!ui.isAttached() || !pendingRefresh.compareAndSet(false, true)) return;
        try {
            ui.access(() -> {
                long version = refreshVersion.get();
                try {
                    if (isOpened() && isAttached()) {
                        if (fullRefresh.getAndSet(false)) render(); else renderHistory();
                    }
                } finally {
                    pendingRefresh.set(false);
                    if (isOpened() && version != refreshVersion.get()) scheduleRefresh(ui);
                }
            });
        } catch (com.vaadin.flow.component.UIDetachedException detached) { pendingRefresh.set(false); }
    }
}
