package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.vaadin.flow.component.button.Button;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CopilotChatViewTest {
    @Test
    void offersInAppLoginWhenAuthenticationIsRequired() throws Exception {
        CopilotConversationService service = mock(CopilotConversationService.class);
        when(service.availability()).thenReturn(new CopilotAvailability(
                CopilotAvailability.State.AUTHENTICATION_REQUIRED, "Sign in", null, 0));
        when(service.models()).thenReturn(List.of());
        when(service.signIn()).thenReturn(new CompletableFuture<>());
        CopilotChatView view = new CopilotChatView(service);
        Button login = field(view, "login");

        assertTrue(login.isVisible());
        login.click();
        verify(service).signIn();
    }

    @Test
    void hidesLoginWhenCopilotIsReady() throws Exception {
        CopilotConversationService service = mock(CopilotConversationService.class);
        when(service.availability()).thenReturn(new CopilotAvailability(
                CopilotAvailability.State.READY, "Ready", "1.0", 1));
        when(service.models()).thenReturn(List.of());

        assertFalse(CopilotChatViewTest.<Button>field(new CopilotChatView(service), "login").isVisible());
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
