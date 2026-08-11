package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityAvailability;
import com.github.fmaiassistent.antigravity.AntigravityConversation;
import com.github.fmaiassistent.antigravity.AntigravityConversationItem;
import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.antigravity.AntigravityConversationSnapshot;
import com.github.fmaiassistent.antigravity.AntigravityEvent;
import com.github.fmaiassistent.antigravity.AntigravitySubscription;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextArea;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

final class AntigravityChatView extends Div {
    private final AntigravityConversationService conversations;
    private final Div conversationList = new Div();
    private final MessageList messages = new MessageList();
    private final TextArea input = new TextArea();
    private final Button send = new Button("Send", VaadinIcon.PAPERPLANE.create());
    private final Button stop = new Button("Stop", VaadinIcon.STOP.create());
    private final Button newChat = new Button("New chat", VaadinIcon.PLUS.create());
    private final Span agentStatus = new Span();
    private final Span mcpStatus = new Span("Application MCP · inherited from Antigravity configuration");
    private final List<MessageListItem> messageItems = new ArrayList<>();
    private final Map<String, MessageListItem> itemsById = new LinkedHashMap<>();
    private final Map<String, StringBuilder> assistantBuffers = new LinkedHashMap<>();
    private final Map<String, Button> conversationButtons = new LinkedHashMap<>();
    private final Set<String> completedTurns = ConcurrentHashMap.newKeySet();

    private AntigravitySubscription availabilitySubscription = () -> { };
    private AntigravitySubscription conversationSubscription = () -> { };
    private String selectedConversationId;
    private String activeTurnId;
    private boolean turnPending;
    private boolean conversationSubscribed;

    AntigravityChatView(AntigravityConversationService conversations) {
        this.conversations = conversations;
        addClassNames("codex-chat", "antigravity-chat");
        setSizeFull();

        messages.setMarkdown(true);
        messages.setAnnounceMessages(true);
        messages.addClassName("codex-messages");
        messages.setSizeFull();
        configureInput();
        configureActions();
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
        availabilitySubscription.close();
        availabilitySubscription = conversations.subscribeAvailability(value -> access(() -> {
            updateAvailability(value);
            if (value.ready()) {
                refreshConversations();
            }
        }));
        refreshConversations();
        if (selectedConversationId != null) {
            openConversation(selectedConversationId);
        }
    }

    @Override
    protected void onDetach(DetachEvent event) {
        availabilitySubscription.close();
        conversationSubscription.close();
        conversationSubscribed = false;
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
        Span heading = new Span("Antigravity");
        heading.addClassName("codex-heading");
        Div statusCopy = new Div(agentStatus, mcpStatus);
        statusCopy.addClassName("codex-status-copy");
        HorizontalLayout header = new HorizontalLayout(heading, statusCopy);
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
        input.setPlaceholder("Ask Antigravity about the FM26 data or application…");
        input.setMinRows(2);
        input.setMaxRows(9);
        input.setWidthFull();
        input.addClassName("codex-input");
        input.getElement().setAttribute("aria-label", "Message Antigravity");
        input.getElement().addEventListener("antigravity-send", event -> sendMessage());
        input.getElement().executeJs("""
                if (!this.__antigravityEnterHandler) {
                  this.__antigravityEnterHandler = (event) => {
                    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
                      event.preventDefault();
                      this.dispatchEvent(new CustomEvent('antigravity-send'));
                    }
                  };
                  this.addEventListener('keydown', this.__antigravityEnterHandler);
                }
                """);
    }

    private void configureActions() {
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(event -> sendMessage());
        stop.addThemeVariants(ButtonVariant.LUMO_ERROR);
        stop.addClickListener(event -> stopTurn());
        newChat.addClickListener(event -> createConversation());
    }

    private void updateAvailability(AntigravityAvailability value) {
        agentStatus.setText(value.message());
        agentStatus.getElement().setAttribute("data-state", value.state().name().toLowerCase());
        newChat.setEnabled(value.ready());
        send.setEnabled(value.ready() && activeTurnId == null && !turnPending);
    }

    private void refreshConversations() {
        conversations.listConversations()
                .thenAccept(values -> access(() -> renderConversationList(values)))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                });
    }

    private void renderConversationList(List<AntigravityConversation> values) {
        conversationList.removeAll();
        conversationButtons.clear();
        if (values.isEmpty()) {
            Span empty = new Span("No Antigravity conversations yet");
            empty.addClassName("codex-empty-conversations");
            conversationList.add(empty);
            return;
        }
        for (AntigravityConversation conversation : values) {
            Button button = new Button(conversation.title());
            button.addClassName("codex-conversation-button");
            button.setTooltipText(conversation.preview());
            button.setWidthFull();
            button.addClickListener(event -> openConversation(conversation.uiId()));
            button.getElement().getClassList().set(
                    "selected", conversation.uiId().equals(selectedConversationId));
            conversationButtons.put(conversation.uiId(), button);
            conversationList.add(button);
        }
    }

    private void createConversation() {
        conversations.newConversation()
                .thenAccept(snapshot -> access(() -> {
                    displaySnapshot(snapshot);
                    refreshConversations();
                    input.focus();
                }))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                });
    }

    private void openConversation(String uiId) {
        if (uiId.equals(selectedConversationId) && conversationSubscribed) {
            return;
        }
        conversations.openConversation(uiId)
                .thenAccept(snapshot -> access(() -> displaySnapshot(snapshot)))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                });
    }

    private void displaySnapshot(AntigravityConversationSnapshot snapshot) {
        conversationSubscription.close();
        conversationSubscribed = false;
        selectedConversationId = snapshot.conversation().uiId();
        activeTurnId = snapshot.activeTurnId();
        turnPending = false;
        messageItems.clear();
        itemsById.clear();
        assistantBuffers.clear();
        for (AntigravityConversationItem item : snapshot.items()) {
            addHistoryItem(item);
        }
        messages.setItems(messageItems);
        conversationButtons.forEach((id, button) ->
                button.getElement().getClassList().set("selected", id.equals(selectedConversationId)));
        conversationSubscription = conversations.subscribe(
                selectedConversationId, event -> access(() -> handleEvent(event)));
        conversationSubscribed = true;
        if (snapshot.permissionMode() != null) {
            mcpStatus.setText("Local MCP config · permissions: " + snapshot.permissionMode());
        }
        setRunning(activeTurnId != null);
        input.focus();
    }

    private void addHistoryItem(AntigravityConversationItem item) {
        MessageListItem rendered = switch (item.kind()) {
            case USER -> userItem(item.text());
            case ASSISTANT -> assistantItem(item.text());
            case TOOL -> activityItem(item.text(), item.status(), item.details(), "⚙");
            case SUBAGENT -> activityItem(item.text(), item.status(), item.details(), "A");
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
        conversations.newConversation()
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
        messages.setItems(messageItems);
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
            access(() -> {
                stop.setEnabled(true);
                showError(error);
            });
            return null;
        });
    }

    private void handleEvent(AntigravityEvent event) {
        switch (event) {
            case AntigravityEvent.TurnStarted started -> {
                turnPending = false;
                activeTurnId = started.turnId();
                setRunning(true);
            }
            case AntigravityEvent.Initialized initialized -> {
                String permission = initialized.permissionMode() == null
                        ? "default permissions"
                        : "permissions: " + initialized.permissionMode();
                mcpStatus.setText("Local MCP config · " + permission);
            }
            case AntigravityEvent.AssistantTextDelta delta -> {
                MessageListItem item = itemsById.computeIfAbsent(delta.itemId(), id -> {
                    MessageListItem created = assistantItem("");
                    messageItems.add(created);
                    messages.setItems(messageItems);
                    return created;
                });
                StringBuilder text = assistantBuffers.computeIfAbsent(
                        delta.itemId(), ignored -> new StringBuilder());
                text.append(delta.delta());
                item.setText(sanitizeMarkdown(text.toString()));
            }
            case AntigravityEvent.ToolStarted started -> {
                addMessage(started.itemId(), activityItem(
                        started.label(), "inProgress", started.details(), started.mcp() ? "M" : "⚙"));
                messages.setItems(messageItems);
                if (started.mcp()) {
                    mcpStatus.setText("Application MCP · tool active");
                }
            }
            case AntigravityEvent.ToolCompleted completed -> {
                MessageListItem item = activityItem(
                        completed.label(), completed.status(), completed.details(), completed.mcp() ? "M" : "⚙");
                replaceMessage(completed.itemId(), item);
                if (completed.mcp()) {
                    mcpStatus.setText("Application MCP · " + completed.status());
                }
            }
            case AntigravityEvent.SubagentUpdated subagent -> replaceMessage(
                    subagent.itemId(),
                    activityItem(subagent.label(), subagent.status(), subagent.details(), "A"));
            case AntigravityEvent.TurnCompleted completed -> {
                if (turnPending) {
                    completedTurns.add(completed.turnId());
                }
                if (completed.fallbackResponse() != null) {
                    addMessage("assistant-" + completed.turnId(), assistantItem(completed.fallbackResponse()));
                }
                if (completed.error() != null && !completed.error().isBlank()) {
                    addMessage("error-" + completed.turnId(), systemItem(completed.error(), true));
                } else if ("interrupted".equals(completed.status())) {
                    addMessage("stopped-" + completed.turnId(), systemItem("Response stopped", false));
                }
                assistantBuffers.remove("assistant-" + completed.turnId());
                activeTurnId = null;
                turnPending = false;
                setRunning(false);
                messages.setItems(messageItems);
                refreshConversations();
            }
            case AntigravityEvent.Failure failure -> {
                if (turnPending) {
                    completedTurns.add(failure.turnId());
                }
                activeTurnId = null;
                turnPending = false;
                setRunning(false);
                addMessage("error-" + UUID.randomUUID(), systemItem(failure.message(), true));
                messages.setItems(messageItems);
            }
        }
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
        messages.setItems(messageItems);
    }

    private void setRunning(boolean running) {
        send.setEnabled(!running && conversations.availability().ready());
        stop.setVisible(running);
        stop.setEnabled(running && activeTurnId != null);
        newChat.setEnabled(conversations.availability().ready());
        agentStatus.getElement().getClassList().set("thinking", running);
        agentStatus.setText(running ? "Antigravity is working…" : conversations.availability().message());
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
        MessageListItem item = new MessageListItem(sanitizeMarkdown(text), Instant.now(), "Antigravity");
        item.setUserAbbreviation("A");
        item.setUserColorIndex(6);
        item.addClassNames("codex-assistant-message", "antigravity-assistant-message");
        return item;
    }

    private static MessageListItem activityItem(String label, String status, String details, String abbreviation) {
        String displayStatus = "inProgress".equals(status) ? "Running" : status;
        String text = "**" + sanitizeMarkdown(label) + "** · " + sanitizeMarkdown(displayStatus);
        if (details != null && !details.isBlank()) {
            text += "\n\n`" + sanitizeMarkdown(details).replace("`", "'") + "`";
        }
        MessageListItem item = new MessageListItem(text, Instant.now(),
                "A".equals(abbreviation) ? "Agent activity" : "Activity");
        item.setUserAbbreviation(abbreviation);
        item.setUserColorIndex("M".equals(abbreviation) ? 3 : 4);
        item.addClassNames("codex-tool-message");
        return item;
    }

    private static MessageListItem systemItem(String text, boolean error) {
        MessageListItem item = new MessageListItem(
                sanitizeMarkdown(text), Instant.now(), error ? "Error" : "Antigravity");
        item.setUserAbbreviation(error ? "!" : "A");
        item.setUserColorIndex(error ? 1 : 6);
        item.addClassNames(error ? "codex-error-message" : "codex-system-message");
        return item;
    }

    private void showError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        String message = cause.getMessage() == null ? "Antigravity request failed" : cause.getMessage();
        addMessage("error-" + UUID.randomUUID(), systemItem(message, true));
        messages.setItems(messageItems);
    }

    private void access(Runnable action) {
        UI ui = getUI().orElse(null);
        if (ui != null && ui.isAttached()) {
            ui.access(action::run);
        }
    }

    private static String sanitizeMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return markdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replaceAll("(?i)]\\s*\\((?:javascript|data|vbscript):", "](#blocked-");
    }
}
