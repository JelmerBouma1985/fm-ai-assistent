package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.codex.CodexAvailability;
import com.github.fmaiassistent.codex.CodexConversation;
import com.github.fmaiassistent.codex.CodexConversationItem;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.codex.CodexConversationSnapshot;
import com.github.fmaiassistent.codex.CodexEvent;
import com.github.fmaiassistent.codex.CodexLogin;
import com.github.fmaiassistent.codex.CodexSubscription;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
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
import java.util.UUID;
import java.util.concurrent.CompletionException;

final class CodexChatView extends Div {
    private final CodexConversationService conversations;
    private final Div conversationList = new Div();
    private final MessageList messages = new MessageList();
    private final TextArea input = new TextArea();
    private final Button send = new Button("Send", VaadinIcon.PAPERPLANE.create());
    private final Button stop = new Button("Stop", VaadinIcon.STOP.create());
    private final Button newChat = new Button("New chat", VaadinIcon.PLUS.create());
    private final Button restart = new Button("Restart", VaadinIcon.REFRESH.create());
    private final Button login = new Button("Sign in with ChatGPT", VaadinIcon.SIGN_IN.create());
    private final Span codexStatus = new Span();
    private final Span mcpStatus = new Span("Application MCP · connects with first chat");
    private final List<MessageListItem> messageItems = new ArrayList<>();
    private final Map<String, MessageListItem> itemsById = new LinkedHashMap<>();
    private final Map<String, StringBuilder> assistantBuffers = new LinkedHashMap<>();
    private final Map<String, Button> conversationButtons = new LinkedHashMap<>();

    private CodexSubscription availabilitySubscription = () -> { };
    private CodexSubscription conversationSubscription = () -> { };
    private String selectedThreadId;
    private String activeTurnId;
    private boolean turnPending;
    private boolean conversationSubscribed;
    private CodexLogin currentLogin;

    CodexChatView(CodexConversationService conversations) {
        this.conversations = conversations;
        addClassName("codex-chat");
        setSizeFull();

        messages.setMarkdown(true);
        messages.setAnnounceMessages(true);
        messages.addClassName("codex-messages");
        messages.setSizeFull();

        configureInput();
        configureActions();
        conversationList.addClassName("codex-conversation-list");
        codexStatus.addClassName("codex-status");
        mcpStatus.addClassName("codex-mcp-status");

        Div sidebar = sidebar();
        Div workspace = workspace();
        add(sidebar, workspace);
        updateAvailability(conversations.availability());
        setRunning(false);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        availabilitySubscription.close();
        availabilitySubscription = conversations.subscribeAvailability(value -> access(() -> {
            updateAvailability(value);
            if (value.ready() && conversationButtons.isEmpty()) {
                refreshConversations();
            }
        }));
        if (conversations.availability().ready()) {
            refreshConversations();
        }
        if (selectedThreadId != null) {
            openConversation(selectedThreadId);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        availabilitySubscription.close();
        conversationSubscription.close();
        conversationSubscribed = false;
        availabilitySubscription = () -> { };
        conversationSubscription = () -> { };
        super.onDetach(detachEvent);
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
        Span heading = new Span("AI assistent");
        heading.addClassName("codex-heading");
        Div statusCopy = new Div(codexStatus, mcpStatus);
        statusCopy.addClassName("codex-status-copy");
        HorizontalLayout header = new HorizontalLayout(heading, statusCopy, login, restart);
        header.setAlignItems(HorizontalLayout.Alignment.CENTER);
        header.expand(statusCopy);
        header.setWidthFull();
        header.addClassName("codex-chat-header");

        HorizontalLayout inputActions = new HorizontalLayout(stop, send);
        inputActions.setAlignItems(HorizontalLayout.Alignment.END);
        inputActions.addClassName("codex-input-actions");
        Div composer = new Div(input, inputActions);
        composer.addClassName("codex-composer");

        Div workspace = new Div(header, messages, composer);
        workspace.addClassName("codex-workspace");
        return workspace;
    }

    private void configureInput() {
        input.setPlaceholder("Ask Codex about the FM26 data or application…");
        input.setMinRows(2);
        input.setMaxRows(9);
        input.setWidthFull();
        input.setValueChangeMode(ValueChangeMode.EAGER);
        input.addClassName("codex-input");
        input.getElement().setAttribute("aria-label", "Message Codex");
        Shortcuts.addShortcutListener(input, this::sendMessage, Key.ENTER).listenOn(input);
    }

    private void configureActions() {
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(event -> sendMessage());
        stop.addThemeVariants(ButtonVariant.LUMO_ERROR);
        stop.addClickListener(event -> stopTurn());
        newChat.addClickListener(event -> createConversation());
        restart.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        restart.addClickListener(event -> restartCodex());
        login.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        login.addClickListener(event -> startLogin());
    }

    private void updateAvailability(CodexAvailability value) {
        codexStatus.setText(value.message());
        codexStatus.getElement().setAttribute("data-state", value.state().name().toLowerCase());
        newChat.setEnabled(value.ready());
        send.setEnabled(value.ready() && activeTurnId == null && !turnPending);
        restart.setVisible(value.state() == CodexAvailability.State.ERROR
                || value.state() == CodexAvailability.State.UNAVAILABLE);
        boolean signingIn = value.state() == CodexAvailability.State.AUTHENTICATING;
        login.setVisible(value.state() == CodexAvailability.State.AUTHENTICATION_REQUIRED || signingIn);
        login.setText(signingIn ? "Continue sign in" : "Sign in with ChatGPT");
        login.setEnabled(!signingIn || currentLogin != null);
        if (value.ready()) {
            currentLogin = null;
        }
    }

    private void startLogin() {
        if (currentLogin != null) {
            showLoginDialog(currentLogin);
            return;
        }
        login.setEnabled(false);
        conversations.startChatGptLogin()
                .thenAccept(loginInfo -> access(() -> {
                    currentLogin = loginInfo;
                    login.setEnabled(true);
                    showLoginDialog(loginInfo);
                }))
                .exceptionally(error -> {
                    access(() -> {
                        login.setEnabled(true);
                        showError(error);
                    });
                    return null;
                });
    }

    private void showLoginDialog(CodexLogin loginInfo) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Sign in to Codex with ChatGPT");
        dialog.setWidth("560px");
        dialog.setMaxWidth("calc(100vw - 32px)");

        Span explanation = new Span(
                "Open the secure ChatGPT sign-in page. Codex stores the authentication locally; this application never sees your password or an API key.");
        Anchor openLogin = new Anchor(loginInfo.authUrl(), "Open ChatGPT sign-in");
        openLogin.setTarget("_blank");
        openLogin.getElement().setAttribute("rel", "noopener noreferrer");
        openLogin.addClassName("codex-login-link");
        VerticalLayout body = new VerticalLayout(explanation, openLogin);
        body.setPadding(false);
        dialog.add(body);

        Button check = new Button("I have signed in", event -> conversations.refreshAccount()
                .thenRun(() -> access(() -> {
                    if (conversations.availability().ready()) {
                        dialog.close();
                    }
                }))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                }));
        check.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", event -> conversations.cancelLogin(loginInfo.loginId())
                .whenComplete((ignored, error) -> access(() -> {
                    currentLogin = null;
                    dialog.close();
                    if (error != null) {
                        showError(error);
                    }
                })));
        dialog.getFooter().add(cancel, check);
        dialog.open();
    }

    private void refreshConversations() {
        conversations.listConversations()
                .thenAccept(values -> access(() -> renderConversationList(values)))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                });
    }

    private void renderConversationList(List<CodexConversation> values) {
        conversationList.removeAll();
        conversationButtons.clear();
        if (values.isEmpty()) {
            Span empty = new Span("No conversations yet");
            empty.addClassName("codex-empty-conversations");
            conversationList.add(empty);
            return;
        }
        for (CodexConversation conversation : values) {
            Button button = new Button(conversation.title());
            button.addClassName("codex-conversation-button");
            button.setTooltipText(conversation.preview());
            button.setWidthFull();
            button.addClickListener(event -> openConversation(conversation.threadId()));
            button.getElement().getClassList().set("selected", conversation.threadId().equals(selectedThreadId));
            conversationButtons.put(conversation.threadId(), button);
            conversationList.add(button);
        }
    }

    private void createConversation() {
        newChat.setEnabled(false);
        conversations.newConversation()
                .thenAccept(snapshot -> access(() -> {
                    displaySnapshot(snapshot);
                    refreshConversations();
                    input.focus();
                }))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                })
                .whenComplete((ignored, error) -> access(() ->
                        newChat.setEnabled(conversations.availability().ready())));
    }

    private void openConversation(String threadId) {
        if (threadId.equals(selectedThreadId) && conversationSubscribed) {
            return;
        }
        conversations.openConversation(threadId)
                .thenAccept(snapshot -> access(() -> displaySnapshot(snapshot)))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                });
    }

    private void displaySnapshot(CodexConversationSnapshot snapshot) {
        conversationSubscription.close();
        conversationSubscribed = false;
        selectedThreadId = snapshot.conversation().threadId();
        activeTurnId = snapshot.activeTurnId();
        messageItems.clear();
        itemsById.clear();
        assistantBuffers.clear();
        for (CodexConversationItem item : snapshot.items()) {
            addHistoryItem(item);
        }
        messages.setItems(messageItems);
        conversationButtons.forEach((id, button) ->
                button.getElement().getClassList().set("selected", id.equals(selectedThreadId)));
        conversationSubscription = conversations.subscribe(selectedThreadId, event -> access(() -> handleEvent(event)));
        conversationSubscribed = true;
        setRunning(activeTurnId != null);
        input.focus();
    }

    private void addHistoryItem(CodexConversationItem item) {
        MessageListItem rendered = switch (item.kind()) {
            case USER -> userItem(item.text());
            case ASSISTANT -> assistantItem(item.text());
            case TOOL -> toolItem(item.text(), item.status(), item.details());
            case SYSTEM -> systemItem(item.text(), false);
        };
        addMessage(item.id(), rendered);
    }

    private void sendMessage() {
        String text = input.getValue();
        if (activeTurnId != null || turnPending || text == null || text.isBlank()) {
            return;
        }
        input.clear();
        if (selectedThreadId == null) {
            createConversationAndSend(text);
            return;
        }
        submitMessage(text);
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
                        activeTurnId = null;
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
        conversations.sendMessage(selectedThreadId, text)
                .thenAccept(turnId -> access(() -> {
                    if (turnPending || turnId.equals(activeTurnId)) {
                        turnPending = false;
                        activeTurnId = turnId;
                        setRunning(true);
                    }
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
        if (selectedThreadId == null || activeTurnId == null) {
            return;
        }
        stop.setEnabled(false);
        conversations.interrupt(selectedThreadId).exceptionally(error -> {
            access(() -> {
                stop.setEnabled(true);
                showError(error);
            });
            return null;
        });
    }

    private void restartCodex() {
        restart.setEnabled(false);
        conversations.restart()
                .thenRun(() -> access(this::refreshConversations))
                .exceptionally(error -> {
                    access(() -> showError(error));
                    return null;
                })
                .whenComplete((ignored, error) -> access(() -> restart.setEnabled(true)));
    }

    private void handleEvent(CodexEvent event) {
        switch (event) {
            case CodexEvent.TurnStarted started -> {
                turnPending = false;
                activeTurnId = started.turnId();
                setRunning(true);
            }
            case CodexEvent.AssistantStarted started -> {
                if (!itemsById.containsKey(started.itemId())) {
                    addMessage(started.itemId(), assistantItem(""));
                    assistantBuffers.put(started.itemId(), new StringBuilder());
                    messages.setItems(messageItems);
                }
            }
            case CodexEvent.AssistantTextDelta delta -> {
                MessageListItem item = itemsById.computeIfAbsent(delta.itemId(), id -> {
                    MessageListItem created = assistantItem("");
                    messageItems.add(created);
                    messages.setItems(messageItems);
                    return created;
                });
                StringBuilder text = assistantBuffers.computeIfAbsent(delta.itemId(), ignored -> new StringBuilder());
                text.append(delta.delta());
                item.setText(sanitizeMarkdown(text.toString()));
            }
            case CodexEvent.AssistantCompleted completed -> {
                MessageListItem item = itemsById.get(completed.itemId());
                if (item == null) {
                    addMessage(completed.itemId(), assistantItem(completed.text()));
                    messages.setItems(messageItems);
                } else {
                    item.setText(sanitizeMarkdown(completed.text()));
                }
                assistantBuffers.remove(completed.itemId());
            }
            case CodexEvent.ToolStarted started -> {
                addMessage(started.itemId(), toolItem(started.label(), "inProgress", started.details()));
                messages.setItems(messageItems);
            }
            case CodexEvent.ToolCompleted completed -> {
                MessageListItem item = itemsById.get(completed.itemId());
                String text = toolText(completed.label(), completed.status(), completed.details());
                if (item == null) {
                    addMessage(completed.itemId(), toolItem(completed.label(), completed.status(), completed.details()));
                    messages.setItems(messageItems);
                } else {
                    item.setText(text);
                }
            }
            case CodexEvent.TurnCompleted completed -> {
                turnPending = false;
                activeTurnId = null;
                setRunning(false);
                if ("interrupted".equals(completed.status())) {
                    addMessage("stopped-" + completed.turnId(), systemItem("Response stopped", false));
                    messages.setItems(messageItems);
                } else if (completed.error() != null) {
                    addMessage("failed-" + completed.turnId(), systemItem(completed.error(), true));
                    messages.setItems(messageItems);
                }
                refreshConversations();
            }
            case CodexEvent.Failure failure -> {
                turnPending = false;
                activeTurnId = null;
                setRunning(false);
                addMessage("error-" + UUID.randomUUID(), systemItem(failure.message(), true));
                messages.setItems(messageItems);
            }
            case CodexEvent.ApprovalRequested approval -> showApproval(approval);
            case CodexEvent.McpStatusChanged status -> {
                String text = status.server() + " · " + status.status();
                if (status.error() != null && !status.error().isBlank()) {
                    text += " · " + status.error();
                }
                mcpStatus.setText(text);
            }
        }
    }

    private void showApproval(CodexEvent.ApprovalRequested approval) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(switch (approval.kind()) {
            case COMMAND -> "Allow command?";
            case FILE_CHANGE -> "Allow file changes?";
            case PERMISSIONS -> "Grant permissions?";
        });
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        dialog.setWidth("680px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("codex-approval-dialog");

        Span summary = new Span(approval.summary());
        summary.addClassName("codex-approval-summary");
        Span details = new Span(approval.details());
        details.addClassName("codex-approval-details");
        VerticalLayout body = new VerticalLayout(summary, details);
        body.setPadding(false);
        body.addClassName("codex-approval-body");
        dialog.add(body);

        Button deny = approvalButton("Deny", CodexConversationService.ApprovalDecision.DENY, approval, dialog);
        Button denyAndStop = approvalButton("Deny & stop", CodexConversationService.ApprovalDecision.DENY_AND_STOP, approval, dialog);
        Button allowSession = approvalButton("Allow for session", CodexConversationService.ApprovalDecision.ALLOW_SESSION, approval, dialog);
        Button allow = approvalButton("Allow once", CodexConversationService.ApprovalDecision.ALLOW_ONCE, approval, dialog);
        allow.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(deny, denyAndStop, allowSession, allow);
        dialog.open();
    }

    private Button approvalButton(
            String label,
            CodexConversationService.ApprovalDecision decision,
            CodexEvent.ApprovalRequested approval,
            Dialog dialog) {
        return new Button(label, event -> {
            try {
                conversations.decideApproval(approval.requestKey(), decision);
                dialog.close();
            } catch (RuntimeException ex) {
                showError(ex);
            }
        });
    }

    private void setRunning(boolean running) {
        send.setEnabled(!running && conversations.availability().ready());
        stop.setVisible(running);
        stop.setEnabled(running && activeTurnId != null);
        newChat.setEnabled(!running && conversations.availability().ready());
        codexStatus.getElement().getClassList().set("thinking", running);
        if (running) {
            codexStatus.setText("Codex is working…");
        } else {
            codexStatus.setText(conversations.availability().message());
        }
    }

    private void addMessage(String id, MessageListItem item) {
        if (itemsById.containsKey(id)) {
            return;
        }
        itemsById.put(id, item);
        messageItems.add(item);
    }

    private static MessageListItem userItem(String text) {
        MessageListItem item = new MessageListItem(sanitizeMarkdown(text), Instant.now(), "You");
        item.setUserAbbreviation("Y");
        item.setUserColorIndex(5);
        item.addClassNames("codex-user-message");
        return item;
    }

    private static MessageListItem assistantItem(String text) {
        MessageListItem item = new MessageListItem(sanitizeMarkdown(text), Instant.now(), "Codex");
        item.setUserAbbreviation("C");
        item.setUserColorIndex(2);
        item.addClassNames("codex-assistant-message");
        return item;
    }

    private static MessageListItem toolItem(String label, String status, String details) {
        MessageListItem item = new MessageListItem(toolText(label, status, details), Instant.now(), "Activity");
        item.setUserAbbreviation("⚙");
        item.setUserColorIndex(3);
        item.addClassNames("codex-tool-message");
        return item;
    }

    private static String toolText(String label, String status, String details) {
        String indicator = "inProgress".equals(status) ? "Running" : status;
        String text = "**" + label + "** · " + indicator;
        return details == null || details.isBlank() ? text : text + "\n\n`" + details.replace("`", "'") + "`";
    }

    private static MessageListItem systemItem(String text, boolean error) {
        MessageListItem item = new MessageListItem(text, Instant.now(), error ? "Error" : "Codex");
        item.setUserAbbreviation(error ? "!" : "C");
        item.setUserColorIndex(error ? 1 : 2);
        item.addClassNames(error ? "codex-error-message" : "codex-system-message");
        return item;
    }

    private void showError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        String message = cause.getMessage() == null ? "Codex request failed" : cause.getMessage();
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
