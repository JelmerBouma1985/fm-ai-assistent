package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;

final class AiAssistantView extends Div {
    private final CodexChatView codexChat;
    private final AntigravityChatView antigravityChat;
    private final CopilotChatView copilotChat;
    private final Div chatHost = new Div();
    private final Select<Provider> provider = new Select<>();

    AiAssistantView(
            CodexConversationService codexConversations,
            AntigravityConversationService antigravityConversations,
            CopilotConversationService copilotConversations) {
        codexChat = new CodexChatView(codexConversations);
        antigravityChat = new AntigravityChatView(antigravityConversations);
        copilotChat = new CopilotChatView(copilotConversations);

        addClassName("ai-assistant-view");
        setSizeFull();
        chatHost.addClassName("ai-assistant-chat-host");
        chatHost.setSizeFull();

        provider.setLabel("AI agent");
        provider.setItems(Provider.values());
        provider.setItemLabelGenerator(Provider::label);
        provider.setValue(Provider.CODEX);
        provider.addValueChangeListener(event -> showProvider(event.getValue()));

        Span description = new Span("Choose which local AI agent handles this chat.");
        description.addClassName("ai-provider-description");
        HorizontalLayout toolbar = new HorizontalLayout(description, provider);
        toolbar.setAlignItems(HorizontalLayout.Alignment.END);
        toolbar.expand(description);
        toolbar.setWidthFull();
        toolbar.addClassName("ai-provider-toolbar");

        add(toolbar, chatHost);
        showProvider(provider.getValue());
    }

    private void showProvider(Provider selected) {
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
