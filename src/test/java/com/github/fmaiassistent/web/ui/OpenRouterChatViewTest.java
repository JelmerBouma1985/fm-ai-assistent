package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.ai.AiPromptContext;
import com.github.fmaiassistent.openrouter.OpenRouterClient;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.textfield.PasswordField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterChatViewTest {
    @AfterEach void clearUi() { UI.setCurrent(null); }
    @Test void keyIsMaskedAndClearedAndAutoRequiresDialogConfirmation() throws Exception {
        UI.setCurrent(new UI());
        var json = JsonMapper.builder().build();
        try (var service = new OpenRouterConversationService(json, new OpenRouterClient(json),
                () -> new ToolCallback[0], AiPromptContext.none(), "instructions")) {
            var view = new OpenRouterChatView(service);
            var workspace = view.getChildren().skip(1).findFirst().orElseThrow();
            assertEquals(3, workspace.getChildren().count());
            assertTrue(view.toolbarControls().getChildren().anyMatch(PasswordField.class::isInstance));
            assertTrue(view.toolbarControls().getChildren().anyMatch(ComboBox.class::isInstance));
            PasswordField key = field(view, "key");
            assertFalse(key.isRevealButtonVisible());
            key.setValue("test-key");
            OpenRouterChatViewTest.<Button>field(view, "connect").click();
            assertTrue(key.isEmpty());
            assertTrue(service.connected());
            String id = service.list().getFirst().id();
            ComboBox<String> modelChoice = field(view, "modelChoice");
            modelChoice.setValue(OpenRouterConversationService.Mode.AUTO.model());
            UI.setCurrent(new UI());
            ComponentUtil.fireEvent(modelChoice, new AbstractField.ComponentValueChangeEvent<>(modelChoice, modelChoice,
                    OpenRouterConversationService.Mode.FREE.model(), true));
            assertEquals(OpenRouterConversationService.Mode.FREE, service.get(id).mode());
            Dialog confirmation = field(view, "confirmation");
            assertTrue(confirmation.isOpened());
            confirmation.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getText().equals("Use Auto")).findFirst().orElseThrow().click();
            assertEquals(OpenRouterConversationService.Mode.AUTO, service.get(id).mode());
            OpenRouterChatViewTest.<Button>field(view, "newChat").click();
            assertEquals(OpenRouterConversationService.Mode.FREE, service.list().getLast().mode());
            ChatCommandPicker commands = field(view, "commands");
            commands.menuButton().click();
            assertTrue(commands.inputGroup().getChildren().findFirst().orElseThrow().isVisible());
            OpenRouterChatViewTest.<Button>field(view, "disconnect").click();
            assertFalse(service.connected());
            assertTrue(service.list().isEmpty());
        }
    }
    @Test void sessionDestructionClosesBackendAndClearsCredentials() {
        var json = JsonMapper.builder().build();
        var session = new OpenRouterSession(json, () -> new ToolCallback[0], AiPromptContext.none(), "instructions");
        session.conversations().connect("test-key");
        session.conversations().newConversation();
        session.close();
        assertFalse(session.conversations().connected());
        assertTrue(session.conversations().list().isEmpty());
        assertThrows(IllegalStateException.class, () -> session.conversations().connect("another-key"));
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
