package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityAvailability;
import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.codex.CodexAvailability;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.vaadin.flow.component.select.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAssistantViewTest {

    @Test
    void unavailableAgentsAreLabelledDisabledAndSkipped() throws Exception {
        AiAssistantView view = view(
                new CodexAvailability(CodexAvailability.State.UNAVAILABLE, "not found"),
                new AntigravityAvailability(AntigravityAvailability.State.AUTHENTICATION_REQUIRED, "sign in"),
                new CopilotAvailability(CopilotAvailability.State.UNAVAILABLE, "not found", null, 0));

        Select<Object> selector = selector(view);
        List<Object> items = selector.getListDataView().getItems().toList();
        Object codex = item(items, "CODEX");
        Object antigravity = item(items, "ANTIGRAVITY");
        Object copilot = item(items, "COPILOT");

        assertEquals("Codex (not installed)", selector.getItemLabelGenerator().apply(codex));
        assertEquals("GitHub Copilot (not installed)", selector.getItemLabelGenerator().apply(copilot));
        assertFalse(selector.getItemEnabledProvider().test(codex));
        assertFalse(selector.getItemEnabledProvider().test(copilot));
        assertTrue(selector.getItemEnabledProvider().test(antigravity));
        assertEquals("ANTIGRAVITY", selector.getValue().toString());
    }

    @Test
    void noInstalledAgentsShowsAnEmptySelection() throws Exception {
        AiAssistantView view = view(
                new CodexAvailability(CodexAvailability.State.UNAVAILABLE, "not found"),
                new AntigravityAvailability(AntigravityAvailability.State.UNAVAILABLE, "not found"),
                new CopilotAvailability(CopilotAvailability.State.UNAVAILABLE, "not found", null, 0));

        Select<Object> selector = selector(view);

        assertNull(selector.getValue());
        assertTrue(selector.isEmptySelectionAllowed());
        assertEquals("No AI agents installed", selector.getEmptySelectionCaption());
    }

    private static AiAssistantView view(
            CodexAvailability codexAvailability,
            AntigravityAvailability antigravityAvailability,
            CopilotAvailability copilotAvailability) {
        CodexConversationService codex = mock(CodexConversationService.class);
        when(codex.availability()).thenReturn(codexAvailability);
        AntigravityConversationService antigravity = mock(AntigravityConversationService.class);
        when(antigravity.availability()).thenReturn(antigravityAvailability);
        CopilotConversationService copilot = mock(CopilotConversationService.class);
        when(copilot.availability()).thenReturn(copilotAvailability);
        when(copilot.models()).thenReturn(List.of());

        TacticContextService tactics = mock(TacticContextService.class);
        when(tactics.current()).thenReturn(new TacticContext(
                0, "No tactic loaded", null, null, List.of(), List.of()));
        ManagedClubContextService managedClub = mock(ManagedClubContextService.class);
        when(managedClub.current()).thenReturn(ManagedClubContext.notLoaded(0));

        return new AiAssistantView(codex, antigravity, copilot, tactics, managedClub);
    }

    @SuppressWarnings("unchecked")
    private static Select<Object> selector(AiAssistantView view) throws Exception {
        Field field = AiAssistantView.class.getDeclaredField("provider");
        field.setAccessible(true);
        return (Select<Object>) field.get(view);
    }

    private static Object item(List<Object> items, String name) {
        return items.stream().filter(value -> value.toString().equals(name)).findFirst().orElseThrow();
    }
}
