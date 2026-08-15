package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.antigravity.AntigravityAvailability;
import com.github.fmaiassistent.antigravity.AntigravitySubscription;
import com.github.fmaiassistent.codex.CodexAvailability;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.codex.CodexSubscription;
import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.github.fmaiassistent.copilot.CopilotSubscription;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;

final class AiAssistantView extends Div {
    private final CodexChatView codexChat;
    private final AntigravityChatView antigravityChat;
    private final CopilotChatView copilotChat;
    private final Div chatHost = new Div();
    private final Select<Provider> provider = new Select<>();
    private final Button contextButton = new Button(VaadinIcon.BOOK.create());
    private final Dialog contextDialog = new Dialog();
    private final ManagedClubContextPanel managedClubContext;
    private final TacticContextPanel tacticContext;
    private final ManagedClubContextService managedClubContexts;
    private final TacticContextService tacticContexts;
    private final CodexConversationService codexConversations;
    private final AntigravityConversationService antigravityConversations;
    private final CopilotConversationService copilotConversations;
    private CodexSubscription codexAvailabilitySubscription = () -> { };
    private AntigravitySubscription antigravityAvailabilitySubscription = () -> { };
    private CopilotSubscription copilotAvailabilitySubscription = () -> { };

    AiAssistantView(
            CodexConversationService codexConversations,
            AntigravityConversationService antigravityConversations,
            CopilotConversationService copilotConversations,
            TacticContextService tacticContexts,
            ManagedClubContextService managedClubContexts) {
        this.tacticContexts = tacticContexts;
        this.managedClubContexts = managedClubContexts;
        this.codexConversations = codexConversations;
        this.antigravityConversations = antigravityConversations;
        this.copilotConversations = copilotConversations;
        codexChat = new CodexChatView(codexConversations);
        antigravityChat = new AntigravityChatView(antigravityConversations);
        copilotChat = new CopilotChatView(copilotConversations);
        managedClubContext = new ManagedClubContextPanel(managedClubContexts, this::refreshContextButton);
        tacticContext = new TacticContextPanel(tacticContexts, this::refreshContextButton);

        addClassName("ai-assistant-view");
        setSizeFull();
        chatHost.addClassName("ai-assistant-chat-host");
        chatHost.setSizeFull();

        provider.setItems(Provider.values());
        provider.setItemLabelGenerator(this::providerLabel);
        provider.setItemEnabledProvider(this::providerSelectable);
        provider.addValueChangeListener(event -> showProvider(event.getValue()));
        provider.getElement().setAttribute("aria-label", "AI agent");
        refreshProviderOptions();

        Span providerLabel = new Span("Agent");
        providerLabel.addClassName("ai-provider-label");

        contextButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        contextButton.addClassName("ai-context-button");
        contextButton.addClickListener(ignored -> {
            managedClubContext.refresh();
            refreshContextButton();
            contextDialog.open();
        });

        Div toolbarSpacer = new Div();
        HorizontalLayout toolbar = new HorizontalLayout(providerLabel, provider, toolbarSpacer, contextButton);
        toolbar.setAlignItems(HorizontalLayout.Alignment.CENTER);
        toolbar.expand(toolbarSpacer);
        toolbar.setWidthFull();
        toolbar.addClassName("ai-provider-toolbar");

        configureContextDialog();
        refreshContextButton();
        add(toolbar, chatHost);
        showProvider(provider.getValue());
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        closeAvailabilitySubscriptions();
        codexAvailabilitySubscription = codexConversations.subscribeAvailability(
                ignored -> refreshProvidersFromBackground());
        antigravityAvailabilitySubscription = antigravityConversations.subscribeAvailability(
                ignored -> refreshProvidersFromBackground());
        copilotAvailabilitySubscription = copilotConversations.subscribeAvailability(
                ignored -> refreshProvidersFromBackground());
        refreshProviderOptions();
    }

    @Override
    protected void onDetach(DetachEvent event) {
        closeAvailabilitySubscriptions();
        super.onDetach(event);
    }

    void refreshManagedClubContext() {
        managedClubContext.refresh();
        refreshContextButton();
    }

    private void configureContextDialog() {
        contextDialog.setHeaderTitle("AI context");
        contextDialog.setWidth("min(720px, calc(100vw - 32px))");
        contextDialog.setMaxHeight("calc(100vh - 48px)");
        contextDialog.getElement().setAttribute("theme", "professional-dialog ai-context-dialog");

        Span introduction = new Span(
                "Choose which Football Manager context is included with messages to the selected agent.");
        introduction.addClassName("ai-context-dialog-introduction");
        Div content = new Div(introduction, managedClubContext, tacticContext);
        content.addClassName("ai-context-dialog-content");
        contextDialog.add(content);

        Button close = new Button("Done", ignored -> contextDialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        contextDialog.getFooter().add(close);
    }

    private void refreshContextButton() {
        boolean clubAvailable = managedClubContexts.current().available();
        boolean tacticAvailable = tacticContexts.current().active();
        int available = (clubAvailable ? 1 : 0) + (tacticAvailable ? 1 : 0);
        int enabled = (clubAvailable && managedClubContexts.aiContextEnabled() ? 1 : 0)
                + (tacticAvailable && tacticContexts.aiContextEnabled() ? 1 : 0);

        if (available == 0) {
            contextButton.setText("Set up context");
            contextButton.getElement().setAttribute("title", "Add managed-club or tactic context");
        } else if (enabled == available) {
            contextButton.setText("Context · " + enabled + " on");
            contextButton.getElement().setAttribute("title", "All available AI context is enabled");
        } else {
            contextButton.setText("Context · " + enabled + "/" + available + " on");
            contextButton.getElement().setAttribute("title", "Some available AI context is disabled");
        }
    }

    private void refreshProvidersFromBackground() {
        UI ui = getUI().orElse(null);
        if (ui != null && ui.isAttached()) {
            ui.access(this::refreshProviderOptions);
        }
    }

    private void refreshProviderOptions() {
        provider.setItemLabelGenerator(this::providerLabel);
        provider.setItemEnabledProvider(this::providerSelectable);
        Provider selected = provider.getValue();
        if (selected != null && providerSelectable(selected)) {
            provider.setEmptySelectionAllowed(false);
            return;
        }
        for (Provider candidate : Provider.values()) {
            if (providerSelectable(candidate)) {
                provider.setValue(candidate);
                provider.setEmptySelectionAllowed(false);
                return;
            }
        }
        provider.setEmptySelectionAllowed(true);
        provider.setEmptySelectionCaption("No AI agents installed");
        provider.clear();
    }

    private String providerLabel(Provider value) {
        if (value == null) {
            return "No AI agents installed";
        }
        if (providerNotInstalled(value)) {
            return value.label() + " (not installed)";
        }
        if (providerDisabled(value)) {
            return value.label() + " (disabled)";
        }
        return value.label();
    }

    private boolean providerSelectable(Provider value) {
        return value != null && !providerNotInstalled(value) && !providerDisabled(value);
    }

    private boolean providerNotInstalled(Provider value) {
        return switch (value) {
            case CODEX -> codexConversations.availability().state() == CodexAvailability.State.UNAVAILABLE;
            case ANTIGRAVITY -> antigravityConversations.availability().state()
                    == AntigravityAvailability.State.UNAVAILABLE;
            case COPILOT -> copilotConversations.availability().state() == CopilotAvailability.State.UNAVAILABLE;
        };
    }

    private boolean providerDisabled(Provider value) {
        return switch (value) {
            case CODEX -> codexConversations.availability().state() == CodexAvailability.State.DISABLED;
            case ANTIGRAVITY -> antigravityConversations.availability().state()
                    == AntigravityAvailability.State.DISABLED;
            case COPILOT -> copilotConversations.availability().state() == CopilotAvailability.State.DISABLED;
        };
    }

    private void closeAvailabilitySubscriptions() {
        codexAvailabilitySubscription.close();
        antigravityAvailabilitySubscription.close();
        copilotAvailabilitySubscription.close();
        codexAvailabilitySubscription = () -> { };
        antigravityAvailabilitySubscription = () -> { };
        copilotAvailabilitySubscription = () -> { };
    }

    private void showProvider(Provider selected) {
        if (selected == null) {
            Span heading = new Span("No AI agents installed");
            heading.addClassName("ai-provider-empty-heading");
            Span help = new Span(
                    "Install Codex, Antigravity or GitHub Copilot, then restart FM AI Assistent.");
            help.addClassName("ai-provider-empty-help");
            Div empty = new Div(heading, help);
            empty.addClassName("ai-provider-empty");
            chatHost.removeAll();
            chatHost.add(empty);
            return;
        }
        Component chat = switch (selected) {
            case CODEX -> codexChat;
            case ANTIGRAVITY -> antigravityChat;
            case COPILOT -> copilotChat;
        };
        if (chat.getParent().orElse(null) != chatHost) {
            chatHost.removeAll();
            chatHost.add(chat);
        }
    }

    private enum Provider {
        CODEX("Codex"),
        ANTIGRAVITY("Antigravity"),
        COPILOT("GitHub Copilot");

        private final String label;

        Provider(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
