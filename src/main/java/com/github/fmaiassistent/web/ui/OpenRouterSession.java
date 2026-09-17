package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.ai.AiPromptContext;
import com.github.fmaiassistent.openrouter.OpenRouterClient;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

/** Vaadin owns destruction on session expiry and application shutdown. */
@SpringComponent
@VaadinSessionScope
public class OpenRouterSession {
    private final OpenRouterConversationService conversations;
    private final Registration shutdownListener;

    public OpenRouterSession(ObjectMapper json, ToolCallbackProvider tools, AiPromptContext context,
            @Value("${spring.ai.mcp.server.instructions}") String instructions) {
        conversations = new OpenRouterConversationService(json, new OpenRouterClient(json), tools, context, instructions);
        VaadinSession session = VaadinSession.getCurrent();
        shutdownListener = session == null ? () -> { }
                : session.getService().addServiceDestroyListener(event -> close());
    }

    public OpenRouterConversationService conversations() { return conversations; }

    @PreDestroy
    public void close() {
        conversations.close();
        shutdownListener.remove();
    }
}
