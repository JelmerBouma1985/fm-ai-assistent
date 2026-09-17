package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService.Mode;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService.ModelOption;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class OpenRouterChatView extends Div {
    private final OpenRouterConversationService conversations;
    private final PasswordField key = new PasswordField();
    private final Button connect = new Button("Connect");
    private final Button disconnect = new Button("Disconnect");
    private final Button newChat = new Button("New chat");
    private final Button send = new Button("Send");
    private final Button stop = new Button("Stop");
    private final ComboBox<String> modelChoice = new ComboBox<>();
    private final HorizontalLayout toolbarControls = new HorizontalLayout();
    private Map<String, ModelOption> modelOptions = Map.of();
    private List<String> displayedModels = List.of(Mode.FREE.model(), Mode.AUTO.model());
    private long modelLoadGeneration;
    private String catalogNotice;
    private final Span status = new Span();
    private final Div conversationList = new Div();
    private final MessageList messages = new MessageList();
    private final TextArea input = new TextArea();
    private final ChatCommandPicker commands = new ChatCommandPicker(input, this::send);
    private String selected;
    private AutoCloseable subscription;
    private Dialog confirmation;

    OpenRouterChatView(OpenRouterConversationService conversations) {
        this.conversations = conversations;
        setSizeFull();
        addClassName("codex-chat");
        key.setRevealButtonVisible(false);
        key.setPlaceholder("OpenRouter API key");
        key.getElement().setAttribute("autocomplete", "off");
        key.getElement().setAttribute("aria-label", "OpenRouter API key");
        connect.addClickListener(event -> act(() -> {
            String value = key.getValue();
            key.clear();
            conversations.connect(value);
            selected = conversations.newConversation();
            refresh();
            loadModels();
        }));
        disconnect.addClickListener(event -> {
            modelLoadGeneration++;
            modelOptions = Map.of();
            displayedModels = List.of(Mode.FREE.model(), Mode.AUTO.model());
            modelChoice.setItems(displayedModels);
            catalogNotice = null;
            modelChoice.getElement().removeAttribute("title");
            key.clear();
            conversations.disconnect();
            selected = null;
            refresh();
        });
        newChat.addClickListener(event -> act(() -> { selected = conversations.newConversation(); refresh(); }));
        send.addClickListener(event -> send());
        stop.addClickListener(event -> act(() -> conversations.stop(selected)));
        modelChoice.setItems(displayedModels);
        modelChoice.getElement().setAttribute("aria-label", "OpenRouter model");
        modelChoice.setItemLabelGenerator(this::modelLabel);
        modelChoice.setValue(Mode.FREE.model());
        modelChoice.addValueChangeListener(event -> {
            if (!event.isFromClient() || selected == null) return;
            String chosen = event.getValue();
            if (chosen == null) return;
            if (chosen.equals(Mode.FREE.model())) {
                act(() -> conversations.selectMode(selected, Mode.FREE, false));
                return;
            }
            String conversationId = selected;
            var option = modelOptions.get(chosen);
            if (option == null && !chosen.equals(Mode.AUTO.model())) {
                modelChoice.setValue(requestedModel(conversations.get(selected)));
                act(() -> { throw new IllegalStateException("Model list is still loading. Try again shortly."); });
                return;
            }
            if (option != null && option.free()) {
                act(() -> conversations.selectModel(conversationId, chosen, false));
                refresh();
                return;
            }
            modelChoice.setValue(requestedModel(conversations.get(selected)));
            confirmation = new Dialog();
            confirmation.setHeaderTitle(chosen.equals(Mode.AUTO.model()) ? "Auto may cost money" : "Model may cost money");
            confirmation.add(new Span(chosen.equals(Mode.AUTO.model())
                    ? "Auto can route messages and tool results to paid models and charge your OpenRouter account."
                    : "Using " + chosen + " can charge your OpenRouter account for messages and tool results."));
            confirmation.getFooter().add(new Button("Cancel", e -> confirmation.close()),
                    new Button(chosen.equals(Mode.AUTO.model()) ? "Use Auto" : "Use model", e -> {
                        act(() -> {
                            if (chosen.equals(Mode.AUTO.model())) conversations.selectMode(conversationId, Mode.AUTO, true);
                            else conversations.selectModel(conversationId, chosen, true);
                        });
                        confirmation.close();
                        refresh();
                    }));
            confirmation.open();
        });
        input.setPlaceholder("Ask OpenRouter about FM26 data…");
        input.setMinRows(2);
        input.setMaxRows(9);
        input.setWidthFull();
        input.setValueChangeMode(ValueChangeMode.EAGER);
        input.addClassName("codex-input");
        input.getElement().setAttribute("aria-label", "Message OpenRouter");
        messages.setMarkdown(true);
        messages.setAnnounceMessages(true);
        messages.setSizeFull();
        messages.addClassName("codex-messages");
        conversationList.addClassName("codex-conversation-list");
        Div sidebar = new Div(new Span("Conversations"), newChat, conversationList);
        sidebar.addClassName("codex-sidebar");
        Span privacy = new Span("Messages, enabled FM context and tool results are sent to OpenRouter and its providers. "
                + "All FM tools, including refreshes, recruitment updates and shortlist creation, run automatically. "
                + "Your key and chats stay in this server session only. Free capacity is limited.");
        Span modelLabel = new Span("Model");
        modelLabel.addClassName("ai-provider-label");
        toolbarControls.add(key, connect, modelLabel, modelChoice, disconnect);
        toolbarControls.setAlignItems(HorizontalLayout.Alignment.CENTER);
        toolbarControls.addClassName("ai-openrouter-controls");
        HorizontalLayout header = new HorizontalLayout(new Span("OpenRouter"), status);
        header.addClassName("codex-chat-header");
        Div composer = new Div(commands.inputGroup(), new HorizontalLayout(commands.menuButton(), stop, send));
        composer.addClassName("codex-composer");
        Div workspace = new Div(new Div(privacy, header), messages, composer);
        workspace.addClassName("codex-workspace");
        add(sidebar, workspace);
        refresh();
    }

    HorizontalLayout toolbarControls() { return toolbarControls; }

    @Override protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        var ui = event.getUI();
        subscription = conversations.subscribe(snapshot -> {
            if (ui.isAttached()) ui.access(() -> { if (isAttached()) refresh(); });
        });
        refresh();
        if (conversations.connected() && modelOptions.isEmpty()) loadModels();
    }
    @Override protected void onDetach(DetachEvent event) {
        modelLoadGeneration++;
        if (subscription != null) {
            try { subscription.close(); } catch (Exception ignored) { }
            subscription = null;
        }
        if (confirmation != null) confirmation.close();
        key.clear();
        super.onDetach(event);
    }
    private void send() {
        if (commands.expandBeforeSend() || input.isEmpty()) return;
        act(() -> {
            if (selected == null) selected = conversations.newConversation();
            conversations.send(selected, input.getValue());
            input.clear();
            refresh();
        });
    }
    private void loadModels() {
        long generation = ++modelLoadGeneration;
        var ui = getUI().orElse(null);
        if (ui == null || !ui.isAttached()) return;
        catalogNotice = null;
        modelChoice.getElement().removeAttribute("title");
        Thread.ofVirtual().start(() -> {
            try {
                List<ModelOption> options = conversations.loadModels();
                if (ui != null && ui.isAttached()) ui.access(() -> {
                    if (generation != modelLoadGeneration || !conversations.connected()) return;
                    modelOptions = options.stream().collect(Collectors.toMap(ModelOption::id, Function.identity()));
                    var ids = new ArrayList<String>();
                    ids.add(Mode.FREE.model());
                    ids.add(Mode.AUTO.model());
                    options.forEach(option -> ids.add(option.id()));
                    var snapshot = selected == null ? null : conversations.get(selected);
                    if (snapshot != null && snapshot.requestedModel() != null && !ids.contains(snapshot.requestedModel()))
                        ids.add(snapshot.requestedModel());
                    displayedModels = List.copyOf(ids);
                    modelChoice.setItems(displayedModels);
                    catalogNotice = null;
                    refresh();
                });
            } catch (RuntimeException e) {
                if (ui != null && ui.isAttached()) ui.access(() -> {
                    if (generation == modelLoadGeneration && conversations.connected()) {
                        catalogNotice = "Model list unavailable; reconnect to retry";
                        modelChoice.getElement().setAttribute("title", e.getMessage());
                        refresh();
                    }
                });
            }
        });
    }
    private String modelLabel(String id) {
        if (Mode.FREE.model().equals(id)) return "Free router — default";
        if (Mode.AUTO.model().equals(id)) return "Auto router — may cost money";
        ModelOption option = modelOptions.get(id);
        return option == null ? id : option.toString();
    }
    private static String requestedModel(OpenRouterConversationService.Snapshot snapshot) {
        return snapshot.mode() == Mode.SPECIFIC ? snapshot.requestedModel() : snapshot.mode().model();
    }
    private void refresh() {
        boolean connected = conversations.connected();
        var snapshots = conversations.list();
        if (snapshots.stream().noneMatch(c -> c.id().equals(selected))) selected = null;
        if (selected == null && !snapshots.isEmpty()) selected = snapshots.getLast().id();
        var snapshot = selected == null ? null : conversations.get(selected);
        boolean running = snapshot != null && snapshot.running();
        key.setVisible(!connected);
        connect.setVisible(!connected);
        disconnect.setVisible(connected);
        send.setEnabled(connected && !running);
        newChat.setEnabled(connected);
        stop.setVisible(running);
        modelChoice.setEnabled(connected && snapshot != null && !running);
        String requested = snapshot == null ? Mode.FREE.model() : requestedModel(snapshot);
        if (requested != null && !displayedModels.contains(requested)) {
            var expanded = new ArrayList<>(displayedModels);
            expanded.add(requested);
            displayedModels = List.copyOf(expanded);
            modelChoice.setItems(displayedModels);
        }
        modelChoice.setValue(requested);
        status.setText(!connected ? "Connect an API key" : (running ? "Working · " : "Ready · ")
                + modelLabel(modelChoice.getValue()) + (snapshot != null && snapshot.model() != null ? " · Responding: " + snapshot.model() : "")
                + (catalogNotice == null ? "" : " · " + catalogNotice));
        conversationList.removeAll();
        for (var c : snapshots) {
            String title = c.items().stream().filter(i -> i.role().equals("You")).map(OpenRouterConversationService.Item::text)
                    .findFirst().orElse("New chat");
            Button button = new Button(title.substring(0, Math.min(title.length(), 48)), e -> { selected = c.id(); refresh(); });
            button.setWidthFull();
            button.getElement().getClassList().set("selected", c.id().equals(selected));
            conversationList.add(button);
        }
        var rendered = new ArrayList<MessageListItem>();
        if (snapshot != null) for (var item : snapshot.items()) {
            MessageListItem message = new MessageListItem(sanitizeMarkdown(item.text()), item.time(), item.role());
            message.setUserAbbreviation(item.role().equals("You") ? "Y" : item.role().equals("Activity") ? "FM" : "OR");
            message.addClassNames(item.role().equals("You") ? "codex-user-message"
                    : item.role().equals("Activity") ? "codex-tool-message" : "codex-assistant-message");
            rendered.add(message);
        }
        messages.setItems(rendered);
        messages.getElement().executeJs("requestAnimationFrame(() => { this.scrollTop = this.scrollHeight; })");
    }
    private void act(Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) {
            var items = new ArrayList<>(messages.getItems());
            items.add(new MessageListItem(sanitizeMarkdown(e.getMessage()), Instant.now(), "Error"));
            messages.setItems(items);
        }
    }
    private static String sanitizeMarkdown(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("(?i)]\\s*\\((?:javascript|data|vbscript):", "](#blocked-");
    }
}
