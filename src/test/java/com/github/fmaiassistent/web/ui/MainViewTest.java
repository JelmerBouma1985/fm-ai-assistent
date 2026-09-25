package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.antigravity.AntigravityAvailability;
import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.codex.CodexAvailability;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.copilot.CopilotAvailability;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.github.fmaiassistent.service.*;
import com.github.fmaiassistent.snapshot.RefreshCoordinator;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.data.provider.SortDirection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainViewTest {

    @Test
    void sortingSurvivesSwitchingBetweenDataTabs() throws Exception {
        MainView view = view();
        Tabs tabs = field(view, "tabs");
        Tab playersTab = field(view, "playersTab");
        Tab staffTab = field(view, "staffTab");
        Tab clubsTab = field(view, "clubsTab");
        Tab competitionsTab = field(view, "competitionsTab");

        assertSortSurvivesTabSwitch(tabs, playersTab, staffTab, field(view, "playersGrid"), "AGE");
        assertSortSurvivesTabSwitch(tabs, staffTab, clubsTab, field(view, "staffGrid"), "AGE");
        assertSortSurvivesTabSwitch(tabs, clubsTab, competitionsTab, field(view, "clubsGrid"), "REPUTATION");
        assertSortSurvivesTabSwitch(tabs, competitionsTab, playersTab,
                field(view, "competitionsGrid"), "REPUTATION");
    }

    private static <T> void assertSortSurvivesTabSwitch(
            Tabs tabs, Tab selected, Tab other, Grid<T> grid, String columnKey) {
        tabs.setSelectedTab(selected);
        Grid.Column<T> column = grid.getColumnByKey(columnKey);
        grid.sort(List.of(new GridSortOrder<>(column, SortDirection.DESCENDING)));

        tabs.setSelectedTab(other);
        tabs.setSelectedTab(selected);

        GridSortOrder<T> sortOrder = grid.getSortOrder().getFirst();
        assertSame(column, sortOrder.getSorted());
        assertEquals(SortDirection.DESCENDING, sortOrder.getDirection());
    }

    private static MainView view() {
        CodexConversationService codex = mock(CodexConversationService.class);
        when(codex.availability()).thenReturn(new CodexAvailability(
                CodexAvailability.State.UNAVAILABLE, "not found"));
        AntigravityConversationService antigravity = mock(AntigravityConversationService.class);
        when(antigravity.availability()).thenReturn(new AntigravityAvailability(
                AntigravityAvailability.State.UNAVAILABLE, "not found"));
        CopilotConversationService copilot = mock(CopilotConversationService.class);
        when(copilot.availability()).thenReturn(new CopilotAvailability(
                CopilotAvailability.State.UNAVAILABLE, "not found", null, 0));
        when(copilot.models()).thenReturn(List.of());
        TacticContextService tactics = mock(TacticContextService.class);
        when(tactics.current()).thenReturn(new TacticContext(
                0, "No tactic loaded", null, null, List.of(), List.of()));
        ManagedClubContextService managedClub = mock(ManagedClubContextService.class);
        when(managedClub.current()).thenReturn(ManagedClubContext.notLoaded(0));
        OpenRouterSession openRouter = mock(OpenRouterSession.class);
        when(openRouter.conversations()).thenReturn(mock(OpenRouterConversationService.class));

        return new MainView(mock(RefreshCoordinator.class),
                mock(PlayerDatabaseService.class), mock(StaffDatabaseService.class),
                mock(ClubDatabaseService.class), mock(CompetitionDatabaseService.class),
                mock(AppSettingsService.class), mock(SnapshotStatusService.class),
                codex, antigravity, copilot, openRouter, tactics, managedClub);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
