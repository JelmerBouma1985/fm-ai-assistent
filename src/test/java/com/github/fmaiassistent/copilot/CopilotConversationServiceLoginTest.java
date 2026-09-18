package com.github.fmaiassistent.copilot;

import com.github.fmaiassistent.ai.AiPromptContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CopilotConversationServiceLoginTest {
    @Test
    void sdkAuthenticationFailureEnablesInAppLogin() {
        assertEquals(CopilotAvailability.State.AUTHENTICATION_REQUIRED,
                CopilotConversationService.startupFailureState("authentication required"));
        assertEquals(CopilotAvailability.State.ERROR,
                CopilotConversationService.startupFailureState("incompatible protocol"));
    }

    @Test
    void failedBrowserLoginReturnsToRetryableAuthenticationState() throws Exception {
        CopilotProperties properties = new CopilotProperties(
                true, "copilot", ".", null, null, null, null, null);
        CopilotExecutableResolver resolver = mock(CopilotExecutableResolver.class);
        CopilotLoginRunner runner = mock(CopilotLoginRunner.class);
        when(resolver.resolve()).thenReturn("C:/Copilot/copilot.cmd");
        doThrow(new IOException("Login cancelled")).when(runner)
                .login(eq("C:/Copilot/copilot.cmd"), any(Path.class), any(Map.class));
        CopilotConversationService service = new CopilotConversationService(
                properties, new CopilotWorkspaceResolver(properties), resolver, runner, AiPromptContext.none());
        Field availability = CopilotConversationService.class.getDeclaredField("availability");
        availability.setAccessible(true);
        availability.set(service, new CopilotAvailability(
                CopilotAvailability.State.AUTHENTICATION_REQUIRED, "Sign in", null, 0));
        try {
            assertThrows(ExecutionException.class, () -> service.signIn().get(2, TimeUnit.SECONDS));
            assertEquals(CopilotAvailability.State.AUTHENTICATION_REQUIRED, service.availability().state());
            verify(runner).login(eq("C:/Copilot/copilot.cmd"), any(Path.class), any(Map.class));
        } finally {
            service.shutdown();
        }
    }
}
