package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityAvailability;
import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.codex.CodexAvailability;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextArea;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ChatCommandsTest {
    @Test
    void matchesSlashPrefixesAndKeepsTrailingRequirements() {
        assertEquals(8, ChatCommands.matches("/").size());
        assertEquals("/recruit", ChatCommands.matches("/rec").getFirst().name());
        assertEquals("/recruit", ChatCommands.matches("/recruit left back under 25").getFirst().name());
        assertTrue(ChatCommands.matches("/unknown").isEmpty());
        assertTrue(ChatCommands.matches("hello /recruit").isEmpty());
        assertTrue(ChatCommands.expandKnown("/recruit left back under 25").orElseThrow()
                .endsWith("Requirements: left back under 25"));
        String recruit = ChatCommands.expandKnown("/recruit").orElseThrow();
        assertTrue(recruit.contains("fm27_transfer_shortlist"));
        assertTrue(recruit.contains("current squad and player market data"));
        assertTrue(recruit.contains("Only if that tool reports"));
        String squad = ChatCommands.expandKnown("/squad").orElseThrow();
        assertTrue(squad.contains("fm27_analyze_squad"));
        assertTrue(squad.contains("Only if that tool reports"));
        String club = ChatCommands.expandKnown("/club").orElseThrow();
        assertTrue(club.contains("fm27_get_club_context"));
        assertTrue(club.contains("Only if that tool reports"));
        String lineup = ChatCommands.expandKnown("/lineup").orElseThrow();
        assertTrue(lineup.contains("fm27_optimize_lineup"));
        assertTrue(lineup.contains("Only if that tool reports"));
        String opposition = ChatCommands.expandKnown("/opposition").orElseThrow();
        assertTrue(opposition.contains("fm27_analyze_next_opposition"));
        assertTrue(opposition.contains("strengths, weaknesses, key players and unavailable players"));
        assertTrue(opposition.contains("Only if that tool reports"));
        assertTrue(ChatCommands.expandKnown("/unknown").isEmpty());
        assertTrue(ChatCommands.expandKnown("ordinary message").isEmpty());
    }

    @Test
    void menuAndSuggestionsFillWithoutSendingAndNormalTextStillSends() {
        TextArea input = new TextArea();
        AtomicInteger sends = new AtomicInteger();
        ChatCommandPicker picker = new ChatCommandPicker(input, sends::incrementAndGet);

        picker.menuButton().click();
        Div suggestions = (Div) picker.inputGroup().getChildren().findFirst().orElseThrow();
        assertTrue(suggestions.isVisible());
        assertEquals(8, suggestions.getChildren().count());
        ((Button) suggestions.getChildren().findFirst().orElseThrow()).click();
        assertTrue(input.getValue().startsWith("Use the fm27_analyze_squad tool"));
        assertEquals(0, sends.get());
        assertFalse(suggestions.isVisible());

        input.setValue("/recruit left back under 25");
        assertTrue(picker.expandBeforeSend());
        assertTrue(input.getValue().endsWith("Requirements: left back under 25"));
        assertEquals(0, sends.get());

        input.setValue("/unknown request");
        picker.onEnter(sends::incrementAndGet);
        input.setValue("ordinary message");
        picker.onEnter(sends::incrementAndGet);
        assertEquals(2, sends.get());
    }

    @Test
    void oppositionCommandSendsImmediatelyWhenSelected() {
        TextArea input = new TextArea();
        AtomicInteger sends = new AtomicInteger();
        ChatCommandPicker picker = new ChatCommandPicker(input, sends::incrementAndGet);

        picker.menuButton().click();
        Div suggestions = (Div) picker.inputGroup().getChildren().findFirst().orElseThrow();
        ((Button) suggestions.getChildren().skip(2).findFirst().orElseThrow()).click();

        assertTrue(input.getValue().contains("fm27_analyze_next_opposition"));
        assertEquals(1, sends.get());
        assertFalse(suggestions.isVisible());
    }

    @Test
    void keyboardSelectionUsesHighlightedCommand() {
        TextArea input = new TextArea();
        ChatCommandPicker picker = new ChatCommandPicker(input, () -> { });
        picker.menuButton().click();
        picker.move(1);
        picker.onEnter(() -> { });
        assertTrue(input.getValue().startsWith("Use the fm27_optimize_lineup tool"));
    }

    @Test
    void toolsIsAnExactLocalCommandAndNeverAPrompt() {
        TextArea input = new TextArea();
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        ChatCommandPicker picker = new ChatCommandPicker(input, sends::incrementAndGet);
        picker.setToolsAction(opens::incrementAndGet);
        for (String text : new String[]{"/tools", " /TOOLS ", "\t/tools\n"}) {
            input.setValue(text);
            assertTrue(picker.expandBeforeSend());
            assertTrue(input.isEmpty());
            assertTrue(ChatCommands.expandKnown(text).isEmpty());
        }
        assertEquals(3, opens.get());
        for (String text : new String[]{"how do I use /tools?", "/tools settings", "/unknown", "/tools?"}) {
            input.setValue(text);
            assertFalse(picker.expandBeforeSend());
            picker.onEnter(sends::incrementAndGet);
            assertEquals(text, input.getValue());
        }
        assertEquals(3, opens.get());
        assertEquals(4, sends.get());
        assertTrue(ChatCommands.matches("/tools settings").isEmpty());
        assertEquals("/tools", ChatCommands.matches("/to").getFirst().name());
    }

    @Test
    void toolsMouseAndKeyboardSelectionOpenImmediatelyWithoutSending() throws Exception {
        TextArea input = new TextArea();
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        ChatCommandPicker picker = new ChatCommandPicker(input, sends::incrementAndGet);
        picker.setToolsAction(opens::incrementAndGet);
        picker.menuButton().click();
        Div suggestions = (Div) picker.inputGroup().getChildren().findFirst().orElseThrow();
        ((Button) suggestions.getChildren().reduce((a, b) -> b).orElseThrow()).click();
        assertTrue(input.isEmpty());
        assertFalse(suggestions.isVisible());
        picker.menuButton().click();
        picker.move(-1);
        picker.onEnter(sends::incrementAndGet);
        assertTrue(input.isEmpty());
        assertEquals(2, opens.get());
        assertEquals(0, sends.get());
        picker.menuButton().click();
        var close = ChatCommandPicker.class.getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(picker);
        assertFalse(suggestions.isVisible());
        assertEquals("false", picker.menuButton().getElement().getAttribute("aria-expanded"));
    }

    @Test
    void everyAgentViewHasTheSharedCommandPicker() throws Exception {
        CodexConversationService codex = mock(CodexConversationService.class);
        when(codex.availability()).thenReturn(new CodexAvailability(CodexAvailability.State.UNAVAILABLE, "offline"));
        AntigravityConversationService antigravity = mock(AntigravityConversationService.class);
        when(antigravity.availability()).thenReturn(
                new AntigravityAvailability(AntigravityAvailability.State.UNAVAILABLE, "offline"));
        CopilotConversationService copilot = mock(CopilotConversationService.class);
        when(copilot.availability()).thenReturn(
                new CopilotAvailability(CopilotAvailability.State.UNAVAILABLE, "offline", null, 0));
        OpenRouterConversationService openRouter = mock(OpenRouterConversationService.class);
        AtomicInteger toolsOpened = new AtomicInteger();

        for (Object view : new Object[] {
                new CodexChatView(codex), new AntigravityChatView(antigravity), new CopilotChatView(copilot),
                new OpenRouterChatView(openRouter)}) {
            Field field = view.getClass().getDeclaredField("commands");
            field.setAccessible(true);
            ChatCommandPicker picker = (ChatCommandPicker) field.get(view);
            picker.menuButton().click();
            Div suggestions = (Div) picker.inputGroup().getChildren().findFirst().orElseThrow();
            assertEquals(8, suggestions.getChildren().count());
            ((Button) suggestions.getChildren().findFirst().orElseThrow()).click();
            assertFalse(suggestions.isVisible());
            var action = view.getClass().getDeclaredMethod("toolsAction", Runnable.class);
            action.setAccessible(true);
            action.invoke(view, (Runnable) toolsOpened::incrementAndGet);
            Field inputField = view.getClass().getDeclaredField("input");
            inputField.setAccessible(true);
            TextArea input = (TextArea) inputField.get(view);
            input.setValue(" /TOOLS ");
            clearInvocations(codex, antigravity, copilot, openRouter);
            picker.onEnter(() -> { throw new AssertionError("Tools must not send a message"); });
            assertTrue(input.isEmpty());
            verifyNoInteractions(codex, antigravity, copilot, openRouter);
        }
        assertEquals(4, toolsOpened.get());
    }
}
