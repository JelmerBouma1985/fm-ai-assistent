package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityAvailability;
import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.codex.CodexAvailability;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextArea;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatCommandsTest {
    @Test
    void matchesSlashPrefixesAndKeepsTrailingRequirements() {
        assertEquals(6, ChatCommands.matches("/").size());
        assertEquals("/recruit", ChatCommands.matches("/rec").getFirst().name());
        assertEquals("/recruit", ChatCommands.matches("/recruit left back under 25").getFirst().name());
        assertTrue(ChatCommands.matches("/unknown").isEmpty());
        assertTrue(ChatCommands.matches("hello /recruit").isEmpty());
        assertTrue(ChatCommands.expandKnown("/recruit left back under 25").orElseThrow()
                .endsWith("Requirements: left back under 25"));
        String recruit = ChatCommands.expandKnown("/recruit").orElseThrow();
        assertTrue(recruit.contains("fm26_transfer_shortlist"));
        assertTrue(recruit.contains("current squad and player market data"));
        assertTrue(recruit.contains("Only if that tool reports"));
        String squad = ChatCommands.expandKnown("/squad").orElseThrow();
        assertTrue(squad.contains("fm26_analyze_squad"));
        assertTrue(squad.contains("Only if that tool reports"));
        String club = ChatCommands.expandKnown("/club").orElseThrow();
        assertTrue(club.contains("fm26_get_club_context"));
        assertTrue(club.contains("Only if that tool reports"));
        String lineup = ChatCommands.expandKnown("/lineup").orElseThrow();
        assertTrue(lineup.contains("fm26_optimize_lineup"));
        assertTrue(lineup.contains("Only if that tool reports"));
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
        assertEquals(6, suggestions.getChildren().count());
        ((Button) suggestions.getChildren().findFirst().orElseThrow()).click();
        assertTrue(input.getValue().startsWith("Use the fm26_analyze_squad tool"));
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
    void keyboardSelectionUsesHighlightedCommand() {
        TextArea input = new TextArea();
        ChatCommandPicker picker = new ChatCommandPicker(input, () -> { });
        picker.menuButton().click();
        picker.move(1);
        picker.onEnter(() -> { });
        assertTrue(input.getValue().startsWith("Use the fm26_optimize_lineup tool"));
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

        for (Object view : new Object[] {
                new CodexChatView(codex), new AntigravityChatView(antigravity), new CopilotChatView(copilot)}) {
            Field field = view.getClass().getDeclaredField("commands");
            field.setAccessible(true);
            ChatCommandPicker picker = (ChatCommandPicker) field.get(view);
            picker.menuButton().click();
            Div suggestions = (Div) picker.inputGroup().getChildren().findFirst().orElseThrow();
            assertEquals(6, suggestions.getChildren().count());
            ((Button) suggestions.getChildren().findFirst().orElseThrow()).click();
            assertFalse(suggestions.isVisible());
        }
    }
}
