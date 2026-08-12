package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversation;
import com.github.fmaiassistent.copilot.CopilotConversationItem;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.github.fmaiassistent.copilot.CopilotConversationSnapshot;
import com.github.fmaiassistent.copilot.CopilotEvent;
import com.github.fmaiassistent.copilot.CopilotModel;
import com.github.fmaiassistent.copilot.CopilotSubscription;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

final class CopilotChatView extends Div {
    private final CopilotConversationService conversations;
    private final Div conversationList = new Div();
    private final MessageList messages = new MessageList();
    private final TextArea input = new TextArea();
    private final Button send = new Button("Send", VaadinIcon.PAPERPLANE.create());
    private final Button stop = new Button("Stop", VaadinIcon.STOP.create());
    private final Button newChat = new Button("New chat", VaadinIcon.PLUS.create());
    private final Span agentStatus = new Span();
    private final Span mcpStatus = new Span("Application MCP · inherited from Copilot configuration");
    private final ComboBox<CopilotModel> model = new ComboBox<>("Model");
    private final List<MessageListItem> messageItems = new ArrayList<>();
    private final Map<String, MessageListItem> itemsById = new LinkedHashMap<>();
    private final Map<String, StringBuilder> assistantBuffers = new LinkedHashMap<>();
    private final Map<String, Button> conversationButtons = new LinkedHashMap<>();
    private final Set<String> completedTurns = ConcurrentHashMap.newKeySet();
    private final List<Dialog> pendingDialogs = new ArrayList<>();

    private CopilotSubscription availabilitySubscription = () -> { };
    private CopilotSubscription conversationSubscription = () -> { };
    private volatile UI attachedUi;
    private String selectedConversationId;
    private String activeTurnId;
    private boolean turnPending;
    private boolean conversationSubscribed;

    CopilotChatView(CopilotConversationService conversations) {
        this.conversations = conversations;
        addClassNames("codex-chat", "copilot-chat");
        setSizeFull();

        messages.setMarkdown(true);
        messages.setAnnounceMessages(true);
        messages.addClassName("codex-messages");
        messages.setSizeFull();
        configureInput();
        configureActions();
        configureModel();
        conversationList.addClassName("codex-conversation-list");
        agentStatus.addClassName("codex-status");
        mcpStatus.addClassName("codex-mcp-status");
        add(sidebar(), workspace());
        updateAvailability(conversations.availability());
        setRunning(false);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        attachedUi = event.getUI();
        availabilitySubscription.close();
        availabilitySubscription = conversations.subscribeAvailability(value -> access(() -> {
            updateAvailability(value);
            refreshModels();
            if (value.ready()) {
                refreshConversations();
            }
        }));
        refreshModels();
        refreshConversations();
        if (selectedConversationId != null) {
            openConversation(selectedConversationId);
        }
    }

    @Override
    protected void onDetach(DetachEvent event) {
        availabilitySubscription.close();
        conversationSubscription.close();
        if (selectedConversationId != null) {
            conversations.dismissPendingUi(selectedConversationId);
        }
        pendingDialogs.forEach(Dialog::close);
        pendingDialogs.clear();
        conversationSubscribed = false;
        attachedUi = null;
        availabilitySubscription = () -> { };
        conversationSubscription = () -> { };
        super.onDetach(event);
    }

    private Div sidebar() {
        Span title = new Span("Conversations");
        title.addClassName("codex-sidebar-title");
        newChat.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        newChat.setWidthFull();
        Div sidebar = new Div(title, newChat, conversationList);
        sidebar.addClassName("codex-sidebar");
        return sidebar;
    }

    private Div workspace() {
        Span heading = new Span("GitHub Copilot");
        heading.addClassName("codex-heading");
        Div statusCopy = new Div(agentStatus, mcpStatus);
        statusCopy.addClassName("codex-status-copy");
        HorizontalLayout header = new HorizontalLayout(heading, statusCopy, model);
        header.setAlignItems(HorizontalLayout.Alignment.CENTER);
        header.expand(statusCopy);
        header.setWidthFull();
        header.addClassName("codex-chat-header");

        HorizontalLayout actions = new HorizontalLayout(stop, send);
        actions.setAlignItems(HorizontalLayout.Alignment.END);
        actions.addClassName("codex-input-actions");
        Div composer = new Div(input, actions);
        composer.addClassName("codex-composer");
        Div workspace = new Div(header, messages, composer);
        workspace.addClassName("codex-workspace");
        return workspace;
    }

    private void configureInput() {
        input.setPlaceholder("Ask GitHub Copilot about FM26 data or application…");
        input.setMinRows(2);
        input.setMaxRows(9);
        input.setWidthFull();
        input.setValueChangeMode(ValueChangeMode.EAGER);
        input.addClassName("codex-input");
        input.getElement().setAttribute("aria-label", "Message GitHub Copilot");
        Shortcuts.addShortcutListener(input, this::sendMessage, Key.ENTER).listenOn(input);
    }

    private void configureActions() {
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(event -> sendMessage());
        stop.addThemeVariants(ButtonVariant.LUMO_ERROR);
        stop.addClickListener(event -> stopTurn());
        newChat.addClickListener(event -> createConversation());
    }

    private void configureModel() {
        model.setItemLabelGenerator(value -> value.name() == null ? value.id() : value.name());
        model.setPlaceholder("Default");
        model.setClearButtonVisible(false);
        model.setWidth("14rem");
        model.addValueChangeListener(event -> {
            if (event.isFromClient() && selectedConversationId != null && activeTurnId == null) {
                conversations.selectModel(selectedConversationId,
                        event.getValue() == null ? null : event.getValue().id()).exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                });
            }
        });
    }

    private void refreshModels() {
        CopilotModel selected = model.getValue();
        model.setItems(conversations.models());
        if (selected != null) {
            conversations.models().stream().filter(value -> value.id().equals(selected.id())).findFirst()
                    .ifPresent(model::setValue);
        }
    }

    private void updateAvailability(CopilotAvailability value) {
        agentStatus.setText(value.message());
        agentStatus.getElement().setAttribute("data-state", value.state().name().toLowerCase());
        newChat.setEnabled(value.ready());
        send.setEnabled(value.ready() && activeTurnId == null && !turnPending);
        model.setEnabled(value.ready() && activeTurnId == null && !turnPending);
        if (value.ready()) {
            mcpStatus.setText("Local MCP config · Copilot CLI " + value.cliVersion()
                    + " · protocol " + value.protocolVersion());
        }
    }

    private void refreshConversations() {
        conversations.listConversations()
                .thenAccept(values -> access(() -> renderConversationList(values)))
                .exceptionally(error -> { access(() -> showError(error)); return null; });
    }

    private void renderConversationList(List<CopilotConversation> values) {
        conversationList.removeAll();
        conversationButtons.clear();
        if (values.isEmpty()) {
            Span empty = new Span("No Copilot conversations yet");
            empty.addClassName("codex-empty-conversations");
            conversationList.add(empty);
            return;
        }
        for (CopilotConversation conversation : values) {
            Button button = new Button(conversation.title());
            button.addClassName("codex-conversation-button");
            button.setTooltipText(conversation.preview());
            button.setWidthFull();
            button.addClickListener(event -> openConversation(conversation.sessionId()));
            button.getElement().getClassList().set("selected",
                    conversation.sessionId().equals(selectedConversationId));
            conversationButtons.put(conversation.sessionId(), button);
            conversationList.add(button);
        }
    }

    private void createConversation() {
        String modelId = model.getValue() == null ? null : model.getValue().id();
        conversations.newConversation(modelId)
                .thenAccept(snapshot -> access(() -> {
                    displaySnapshot(snapshot);
                    refreshConversations();
                    input.focus();
                }))
                .exceptionally(error -> { access(() -> showError(error)); return null; });
    }

    private void openConversation(String sessionId) {
        if (sessionId.equals(selectedConversationId) && conversationSubscribed) {
            return;
        }
        conversations.openConversation(sessionId)
                .thenAccept(snapshot -> access(() -> displaySnapshot(snapshot)))
                .exceptionally(error -> { access(() -> showError(error)); return null; });
    }

    private void displaySnapshot(CopilotConversationSnapshot snapshot) {
        conversationSubscription.close();
        conversationSubscribed = false;
        selectedConversationId = snapshot.conversation().sessionId();
        activeTurnId = snapshot.activeTurnId();
        turnPending = false;
        messageItems.clear();
        itemsById.clear();
        assistantBuffers.clear();
        snapshot.items().forEach(this::addHistoryItem);
        refreshMessages(true);
        conversationButtons.forEach((id, button) ->
                button.getElement().getClassList().set("selected", id.equals(selectedConversationId)));
        conversationSubscription = conversations.subscribe(
                selectedConversationId, event -> access(() -> handleEvent(event)));
        conversationSubscribed = true;
        if (snapshot.selectedModel() != null) {
            conversations.models().stream().filter(value -> value.id().equals(snapshot.selectedModel()))
                    .findFirst().ifPresent(model::setValue);
        } else {
            model.clear();
        }
        setRunning(activeTurnId != null);
        input.focus();
    }

    private void addHistoryItem(CopilotConversationItem item) {
        MessageListItem rendered = switch (item.kind()) {
            case USER -> userItem(item.text());
            case ASSISTANT -> assistantItem(item.text());
            case TOOL -> activityItem(item.text(), item.status(), item.details(), "⚙");
            case SYSTEM -> systemItem(item.text(), "failed".equals(item.status()));
        };
        addMessage(item.id(), rendered);
    }

    private void sendMessage() {
        String text = input.getValue();
        if (activeTurnId != null || turnPending || text == null || text.isBlank()) {
            return;
        }
        input.clear();
        if (selectedConversationId == null) {
            createConversationAndSend(text);
        } else {
            submitMessage(text);
        }
    }

    private void createConversationAndSend(String text) {
        turnPending = true;
        setRunning(true);
        String modelId = model.getValue() == null ? null : model.getValue().id();
        conversations.newConversation(modelId)
                .thenAccept(snapshot -> access(() -> {
                    displaySnapshot(snapshot);
                    refreshConversations();
                    submitMessage(text);
                }))
                .exceptionally(error -> {
                    access(() -> {
                        turnPending = false;
                        input.setValue(text);
                        setRunning(false);
                        showError(error);
                    });
                    return null;
                });
    }

    private void submitMessage(String text) {
        addMessage("local-" + UUID.randomUUID(), userItem(text));
        refreshMessages(true);
        turnPending = true;
        setRunning(true);
        conversations.sendMessage(selectedConversationId, text)
                .thenAccept(turnId -> access(() -> {
                    turnPending = false;
                    if (completedTurns.remove(turnId)) {
                        activeTurnId = null;
                        setRunning(false);
                    } else {
                        activeTurnId = turnId;
                        setRunning(true);
                    }
                    refreshConversations();
                }))
                .exceptionally(error -> {
                    access(() -> {
                        turnPending = false;
                        activeTurnId = null;
                        setRunning(false);
                        showError(error);
                    });
                    return null;
                });
    }

    private void stopTurn() {
        if (selectedConversationId == null || activeTurnId == null) {
            return;
        }
        stop.setEnabled(false);
        conversations.interrupt(selectedConversationId).exceptionally(error -> {
            access(() -> { stop.setEnabled(true); showError(error); });
            return null;
        });
    }

    private void handleEvent(CopilotEvent event) {
        switch (event) {
            case CopilotEvent.TurnStarted started -> {
                turnPending = false;
                activeTurnId = started.turnId();
                setRunning(true);
            }
            case CopilotEvent.TextDelta delta -> {
                MessageListItem item = itemsById.computeIfAbsent(delta.itemId(), id -> {
                    MessageListItem created = assistantItem("");
                    messageItems.add(created);
                    return created;
                });
                StringBuilder text = assistantBuffers.computeIfAbsent(delta.itemId(), ignored -> new StringBuilder());
                text.append(delta.delta());
                item.setText(sanitizeMarkdown(text.toString()));
                // Copilot can emit explanatory text, run several tools, then continue the
                // same assistant item. Keep its latest response below those activities.
                messageItems.remove(item);
                messageItems.add(item);
                refreshMessages(true);
            }
            case CopilotEvent.ToolStarted started -> {
                addMessage(started.itemId(), activityItem(started.name(), "inProgress", started.details(),
                        started.mcp() ? "M" : "⚙"));
                refreshMessages(true);
                if (started.mcp()) {
                    mcpStatus.setText("Application MCP · tool active");
                }
            }
            case CopilotEvent.ToolCompleted completed -> {
                replaceMessage(completed.itemId(), activityItem(completed.name(), completed.status(),
                        completed.details(), completed.mcp() ? "M" : "⚙"));
                if (completed.mcp()) {
                    mcpStatus.setText("Application MCP · " + completed.status());
                }
            }
            case CopilotEvent.PermissionRequested permission -> showPermission(permission);
            case CopilotEvent.UserInputRequested request -> showUserInput(request);
            case CopilotEvent.TurnCompleted completed -> {
                if (turnPending) {
                    completedTurns.add(completed.turnId());
                }
                if (completed.fallbackResponse() != null) {
                    addMessage("assistant-" + completed.turnId(), assistantItem(completed.fallbackResponse()));
                }
                if (completed.interrupted()) {
                    addMessage("stopped-" + completed.turnId(), systemItem("Response stopped", false));
                }
                assistantBuffers.remove("assistant-" + completed.turnId());
                activeTurnId = null;
                turnPending = false;
                setRunning(false);
                refreshMessages(true);
                refreshConversations();
                reloadSelectedConversation();
            }
            case CopilotEvent.Failure failure -> {
                if (turnPending) {
                    completedTurns.add(failure.turnId());
                }
                activeTurnId = null;
                turnPending = false;
                setRunning(false);
                addMessage("error-" + UUID.randomUUID(), systemItem(failure.message(), true));
                refreshMessages(true);
            }
        }
    }

    private void showPermission(CopilotEvent.PermissionRequested permission) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("GitHub Copilot permission");
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        Pre details = new Pre(permission.description());
        details.getStyle().set("white-space", "pre-wrap").set("max-width", "42rem");
        Button deny = new Button("Deny", event -> {
            conversations.resolvePermission(permission.sessionId(), permission.requestId(), false);
            closeDialog(dialog);
        });
        Button allow = new Button("Allow once", event -> {
            conversations.resolvePermission(permission.sessionId(), permission.requestId(), true);
            closeDialog(dialog);
        });
        allow.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(new Span("Copilot requests: " + permission.kind()), details));
        dialog.getFooter().add(deny);
        if (permission.applicationMcp()) {
            Button alwaysAllow = new Button("Always allow this MCP tool", event -> {
                deny.setEnabled(false);
                allow.setEnabled(false);
                event.getSource().setEnabled(false);
                conversations.alwaysAllowApplicationMcpTool(permission.sessionId(), permission.requestId())
                        .thenRun(() -> access(() -> closeDialog(dialog)))
                        .exceptionally(error -> {
                            access(() -> {
                                deny.setEnabled(true);
                                allow.setEnabled(true);
                                event.getSource().setEnabled(true);
                                showError(error);
                            });
                            return null;
                        });
            });
            dialog.getFooter().add(alwaysAllow);
        }
        dialog.getFooter().add(allow);
        pendingDialogs.add(dialog);
        dialog.open();
    }

    private void showUserInput(CopilotEvent.UserInputRequested request) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("GitHub Copilot question");
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        VerticalLayout content = new VerticalLayout(new Span(request.question()));
        for (String choice : request.choices()) {
            Button option = new Button(choice, event -> {
                conversations.answerUserInput(request.sessionId(), request.requestId(), choice, false);
                closeDialog(dialog);
            });
            option.setWidthFull();
            content.add(option);
        }
        if (request.freeform()) {
            TextArea answer = new TextArea("Answer");
            answer.setWidthFull();
            Button submit = new Button("Send answer", event -> {
                if (!answer.isEmpty()) {
                    conversations.answerUserInput(request.sessionId(), request.requestId(), answer.getValue(), true);
                    closeDialog(dialog);
                }
            });
            submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            content.add(answer, submit);
        }
        Button cancel = new Button("Cancel", event -> {
            conversations.answerUserInput(request.sessionId(), request.requestId(), "", false);
            closeDialog(dialog);
        });
        dialog.add(content);
        dialog.getFooter().add(cancel);
        pendingDialogs.add(dialog);
        dialog.open();
    }

    private void closeDialog(Dialog dialog) {
        pendingDialogs.remove(dialog);
        dialog.close();
    }

    private void reloadSelectedConversation() {
        String sessionId = selectedConversationId;
        UI ui = attachedUi;
        if (sessionId == null || ui == null || !ui.isAttached()) {
            return;
        }
        conversations.openConversation(sessionId)
                .thenAccept(snapshot -> ui.access(() -> {
                    if (ui.isAttached() && sessionId.equals(selectedConversationId) && activeTurnId == null) {
                        displaySnapshot(snapshot);
                    }
                }))
                .exceptionally(error -> {
                    ui.access(() -> {
                        if (ui.isAttached()) {
                            showError(error);
                        }
                    });
                    return null;
                });
    }

    private void replaceMessage(String id, MessageListItem replacement) {
        MessageListItem previous = itemsById.put(id, replacement);
        if (previous == null) {
            messageItems.add(replacement);
        } else {
            int index = messageItems.indexOf(previous);
            if (index >= 0) {
                messageItems.set(index, replacement);
            }
        }
        refreshMessages(true);
    }

    private void setRunning(boolean running) {
        send.setEnabled(!running && conversations.availability().ready());
        stop.setVisible(running);
        stop.setEnabled(running && activeTurnId != null);
        newChat.setEnabled(conversations.availability().ready());
        model.setEnabled(!running && conversations.availability().ready());
        agentStatus.getElement().getClassList().set("thinking", running);
        agentStatus.setText(running ? "GitHub Copilot is working…" : conversations.availability().message());
    }

    private void addMessage(String id, MessageListItem item) {
        if (itemsById.putIfAbsent(id, item) == null) {
            messageItems.add(item);
        }
    }

    private static MessageListItem userItem(String text) {
        MessageListItem item = new MessageListItem(sanitizeMarkdown(text), Instant.now(), "You");
        item.setUserAbbreviation("Y");
        item.setUserColorIndex(5);
        item.addClassNames("codex-user-message");
        return item;
    }

    private static MessageListItem assistantItem(String text) {
        MessageListItem item = new MessageListItem(sanitizeMarkdown(text), Instant.now(), "GitHub Copilot");
        item.setUserAbbreviation("GH");
        item.setUserColorIndex(2);
        item.addClassNames("codex-assistant-message", "copilot-assistant-message");
        return item;
    }

    private static MessageListItem activityItem(String label, String status, String details, String abbreviation) {
        String displayStatus = "inProgress".equals(status) ? "Running" : status;
        String text = "**" + sanitizeMarkdown(label) + "** · " + sanitizeMarkdown(displayStatus);
        if (details != null && !details.isBlank() && !"null".equals(details)) {
            text += "\n\n`" + sanitizeMarkdown(details).replace("`", "'") + "`";
        }
        MessageListItem item = new MessageListItem(text, Instant.now(), "Activity");
        item.setUserAbbreviation(abbreviation);
        item.setUserColorIndex("M".equals(abbreviation) ? 3 : 4);
        item.addClassNames("codex-tool-message");
        return item;
    }

    private static MessageListItem systemItem(String text, boolean error) {
        MessageListItem item = new MessageListItem(
                sanitizeMarkdown(text), Instant.now(), error ? "Error" : "GitHub Copilot");
        item.setUserAbbreviation(error ? "!" : "GH");
        item.setUserColorIndex(error ? 1 : 2);
        item.addClassNames(error ? "codex-error-message" : "codex-system-message");
        return item;
    }

    private void showError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = cause.getMessage() == null ? "GitHub Copilot request failed" : cause.getMessage();
        addMessage("error-" + UUID.randomUUID(), systemItem(message, true));
        refreshMessages(true);
    }

    private void refreshMessages(boolean scrollToLatest) {
        // MessageList can retain its existing data provider when handed the same mutable list.
        // A snapshot guarantees that text mutations and newly appended events are sent to the browser.
        messages.setItems(List.copyOf(messageItems));
        if (scrollToLatest) {
            messages.getElement().executeJs(
                    "requestAnimationFrame(() => { this.scrollTop = this.scrollHeight; })");
        }
    }

    private void access(Runnable action) {
        UI ui = attachedUi;
        if (ui != null && ui.isAttached()) {
            ui.access(action::run);
        }
    }

    private static String sanitizeMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("(?i)]\\s*\\((?:javascript|data|vbscript):", "](#blocked-");
    }
}
