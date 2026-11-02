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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainViewTest {

    @Test
    void bulkScoutingUsesTheActiveFilterAndShowsProgressUntilCompletion() throws Exception {
        var ui = new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            MainView view = view();
            ScoutReportService scouting = field(view, "scouting");
            when(scouting.isSupported()).thenReturn(true);
            var filter = new com.github.fmaiassistent.repository.PlayerFilterCriteria(
                    "Abel", "", "", "", "", null, null, null, null, "", null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, java.util.Map.of(), java.util.Map.of());
            Field filterField = MainView.class.getDeclaredField("playerFilter");
            filterField.setAccessible(true);
            filterField.set(view, filter);
            var future = new java.util.concurrent.CompletableFuture<ScoutReportService.Result>();
            when(scouting.scoutFiltered(filter)).thenReturn(future);
            when(scouting.progress()).thenReturn(new ScoutReportService.Progress(2, 5, "Scouting 2 of 5"));
            var update = MainView.class.getDeclaredMethod("updateScoutingProgress");
            update.setAccessible(true);
            update.invoke(view);
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            bulk.click();
            org.mockito.Mockito.verify(scouting).scoutFiltered(filter);
            assertFalse(bulk.isEnabled());
            assertEquals("Scouting...", bulk.getText());
            com.vaadin.flow.component.progressbar.ProgressBar progress = field(view, "scoutingProgress");
            assertEquals(0.4, progress.getValue());
            com.vaadin.flow.component.html.Span phase = field(view, "scoutingPhase");
            assertEquals("Scouting 2 of 5", phase.getText());
            future.complete(new ScoutReportService.Result(3, 1, 1));
            update.invoke(view);
            assertTrue(bulk.isEnabled());
            assertEquals("Scout all players in view", bulk.getText());
            com.vaadin.flow.component.dialog.Dialog dialog = field(view, "scoutingDialog");
            assertFalse(dialog.isOpened());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void popupScoutsOnlyItsPlayerAndUnsupportedOverviewIsDisabled() throws Exception {
        var ui = new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            MainView view = view();
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            assertFalse(bulk.isEnabled());
            ScoutReportService scouting = field(view, "scouting");
            when(scouting.isSupported()).thenReturn(true);
            when(scouting.scoutPlayer(67288450L)).thenReturn(new java.util.concurrent.CompletableFuture<>());
            var player = playerRow(java.util.Map.of("unique_id", 67288450L, "name", "Abel Bretones"));
            var open = MainView.class.getDeclaredMethod("openPlayerDetailsDialog", player.getClass());
            open.setAccessible(true);
            open.invoke(view, player);
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var dialog = ui.getChildren().filter(com.vaadin.flow.component.dialog.Dialog.class::isInstance)
                    .map(com.vaadin.flow.component.dialog.Dialog.class::cast)
                    .filter(d -> "Abel Bretones".equals(d.getHeaderTitle())).findFirst().orElseThrow();
            var scout = dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                    .filter(com.vaadin.flow.component.button.Button.class::isInstance)
                    .map(com.vaadin.flow.component.button.Button.class::cast)
                    .filter(b -> "Scout this player".equals(b.getText())).findFirst().orElseThrow();
            scout.click();
            org.mockito.Mockito.verify(scouting).scoutPlayer(67288450L);
            org.mockito.Mockito.verify(scouting, org.mockito.Mockito.never())
                    .scoutFiltered(org.mockito.ArgumentMatchers.any());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void settingsShowAndPersistTheDatabaseScoutChoice() throws Exception {
        var ui = new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            MainView view = view();
            ScoutSettingsService settings = field(view, "scoutSettings");
            when(settings.mode()).thenReturn(com.github.fmaiassistent.domain.enums.ScoutingMode.BEST_CLUB_SCOUT);
            var open = MainView.class.getDeclaredMethod("openSettingsDialog");
            open.setAccessible(true);
            open.invoke(view);
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var dialog = ui.getChildren().filter(com.vaadin.flow.component.dialog.Dialog.class::isInstance)
                    .map(com.vaadin.flow.component.dialog.Dialog.class::cast)
                    .filter(d -> "Settings".equals(d.getHeaderTitle())).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            var choice = (com.vaadin.flow.component.select.Select<com.github.fmaiassistent.domain.enums.ScoutingMode>)
                    descendants(dialog).filter(com.vaadin.flow.component.select.Select.class::isInstance)
                            .map(com.vaadin.flow.component.select.Select.class::cast)
                            .filter(s -> "Scout reports".equals(s.getLabel())).findFirst().orElseThrow();
            assertEquals(com.github.fmaiassistent.domain.enums.ScoutingMode.BEST_CLUB_SCOUT, choice.getValue());
            choice.setValue(com.github.fmaiassistent.domain.enums.ScoutingMode.PERFECT_SCOUT);
            var warning = descendants(dialog).filter(com.vaadin.flow.component.checkbox.Checkbox.class::isInstance)
                    .map(com.vaadin.flow.component.checkbox.Checkbox.class::cast)
                    .filter(c -> "Warn before scouting more than 100 players".equals(c.getLabel())).findFirst().orElseThrow();
            assertFalse(warning.getValue());
            warning.setValue(true);
            dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                    .filter(com.vaadin.flow.component.button.Button.class::isInstance)
                    .map(com.vaadin.flow.component.button.Button.class::cast)
                    .filter(b -> "Save".equals(b.getText())).findFirst().orElseThrow().click();
            org.mockito.Mockito.verify(settings).saveMode(com.github.fmaiassistent.domain.enums.ScoutingMode.PERFECT_SCOUT);
            org.mockito.Mockito.verify(settings).saveWarnForLargeBatch(true);
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void scoutingFailureRestoresControlsAndClosesTheProgressDialog() throws Exception {
        var ui = new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            MainView view = view();
            ScoutReportService scouting = field(view, "scouting");
            when(scouting.isSupported()).thenReturn(true);
            var future = new java.util.concurrent.CompletableFuture<ScoutReportService.Result>();
            when(scouting.scoutFiltered(org.mockito.ArgumentMatchers.any())).thenReturn(future);
            var update = MainView.class.getDeclaredMethod("updateScoutingProgress");
            update.setAccessible(true);
            update.invoke(view);
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            com.vaadin.flow.component.button.Button load = field(view, "loadButton");
            bulk.click();
            assertFalse(load.isEnabled());
            future.completeExceptionally(new IllegalStateException("FM is no longer running"));
            update.invoke(view);
            assertTrue(bulk.isEnabled());
            assertTrue(load.isEnabled());
            assertEquals("Scout all players in view", bulk.getText());
            com.vaadin.flow.component.dialog.Dialog dialog = field(view, "scoutingDialog");
            assertFalse(dialog.isOpened());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void exactlyOneHundredPlayersStartWithoutAWarning() throws Exception {
        var ui = scoutingUi();
        try {
            MainView view = view();
            prepareBulkScouting(view, 100, true);
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            bulk.click();
            ScoutReportService scouting = field(view, "scouting");
            org.mockito.Mockito.verify(scouting).scoutFiltered(org.mockito.ArgumentMatchers.any());
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            assertFalse(ui.getChildren().filter(com.vaadin.flow.component.dialog.Dialog.class::isInstance)
                    .map(com.vaadin.flow.component.dialog.Dialog.class::cast)
                    .anyMatch(d -> d.getHeaderTitle().startsWith("Scout 100")));
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void oneHundredAndOnePlayersRequireContinueAndDismissingDoesNotSaveSuppression() throws Exception {
        var ui = scoutingUi();
        try {
            MainView view = view();
            prepareBulkScouting(view, 101, true);
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            bulk.click();
            var confirmation = scoutingDialog(ui, "Scout 101 players?");
            ScoutReportService scouting = field(view, "scouting");
            org.mockito.Mockito.verify(scouting, org.mockito.Mockito.never()).scoutFiltered(org.mockito.ArgumentMatchers.any());
            descendants(confirmation).filter(com.vaadin.flow.component.checkbox.Checkbox.class::isInstance)
                    .map(com.vaadin.flow.component.checkbox.Checkbox.class::cast).findFirst().orElseThrow().setValue(true);
            footerButton(confirmation, "Cancel").click();
            assertFalse(confirmation.isOpened());
            org.mockito.Mockito.verify(scouting, org.mockito.Mockito.never()).scoutFiltered(org.mockito.ArgumentMatchers.any());
            ScoutSettingsService settings = field(view, "scoutSettings");
            org.mockito.Mockito.verify(settings, org.mockito.Mockito.never()).saveWarnForLargeBatch(org.mockito.ArgumentMatchers.anyBoolean());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void continueUsesTheCapturedFilterAndSavesDontShowAgain() throws Exception {
        var ui = scoutingUi();
        try {
            MainView view = view();
            prepareBulkScouting(view, 101, true);
            var captured = new com.github.fmaiassistent.repository.PlayerFilterCriteria(
                    "Abel", "", "", "", "", null, null, null, null, "", null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, java.util.Map.of(), java.util.Map.of());
            var filter = MainView.class.getDeclaredField("playerFilter");
            filter.setAccessible(true);
            filter.set(view, captured);
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            bulk.click();
            var confirmation = scoutingDialog(ui, "Scout 101 players?");
            filter.set(view, com.github.fmaiassistent.repository.PlayerFilterCriteria.empty());
            descendants(confirmation).filter(com.vaadin.flow.component.checkbox.Checkbox.class::isInstance)
                    .map(com.vaadin.flow.component.checkbox.Checkbox.class::cast).findFirst().orElseThrow().setValue(true);
            footerButton(confirmation, "Continue").click();
            ScoutReportService scouting = field(view, "scouting");
            org.mockito.Mockito.verify(scouting).scoutFiltered(captured);
            ScoutSettingsService settings = field(view, "scoutSettings");
            org.mockito.Mockito.verify(settings).saveWarnForLargeBatch(false);
            assertFalse(confirmation.isOpened());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void disabledBatchWarningStartsLargeBatchesImmediately() throws Exception {
        var ui = scoutingUi();
        try {
            MainView view = view();
            prepareBulkScouting(view, 101, false);
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            bulk.click();
            ScoutReportService scouting = field(view, "scouting");
            org.mockito.Mockito.verify(scouting).scoutFiltered(org.mockito.ArgumentMatchers.any());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    void cancelKeepsTheModalOpenUntilTheCurrentReportFinishesAndReportsPartialCounts() throws Exception {
        var ui = scoutingUi();
        try {
            MainView view = view();
            var future = prepareBulkScouting(view, 100, true);
            ScoutReportService scouting = field(view, "scouting");
            when(scouting.cancel(future)).thenReturn(true);
            when(scouting.progress()).thenReturn(new ScoutReportService.Progress(2, 100, "Scouting 2 of 100"));
            com.vaadin.flow.component.button.Button bulk = field(view, "scoutAllButton");
            bulk.click();
            com.vaadin.flow.component.button.Button cancel = field(view, "cancelScoutingButton");
            cancel.click();
            org.mockito.Mockito.verify(scouting).cancel(future);
            assertEquals("Cancelling...", cancel.getText());
            assertFalse(cancel.isEnabled());
            com.vaadin.flow.component.dialog.Dialog modal = field(view, "scoutingDialog");
            assertTrue(modal.isOpened());
            assertFalse(future.isDone());
            future.complete(new ScoutReportService.Result(2, 1, 0, true));
            var update = MainView.class.getDeclaredMethod("updateScoutingProgress");
            update.setAccessible(true);
            try (var notifications = org.mockito.Mockito.mockStatic(com.vaadin.flow.component.notification.Notification.class)) {
                update.invoke(view);
                notifications.verify(() -> com.vaadin.flow.component.notification.Notification.show(
                        "Scouting cancelled: 2 created, 1 updated, 0 skipped", 6000,
                        com.vaadin.flow.component.notification.Notification.Position.TOP_CENTER));
            }
            assertFalse(modal.isOpened());
            assertTrue(bulk.isEnabled());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    private static com.vaadin.flow.component.UI scoutingUi() {
        var ui = new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        return ui;
    }

    private static java.util.concurrent.CompletableFuture<ScoutReportService.Result> prepareBulkScouting(
            MainView view, long count, boolean warning) throws Exception {
        PlayerDatabaseService players = field(view, "players");
        when(players.countPlayers(org.mockito.ArgumentMatchers.any())).thenReturn(count);
        ScoutSettingsService settings = field(view, "scoutSettings");
        when(settings.warnForLargeBatch()).thenReturn(warning);
        ScoutReportService scouting = field(view, "scouting");
        when(scouting.isSupported()).thenReturn(true);
        var future = new java.util.concurrent.CompletableFuture<ScoutReportService.Result>();
        when(scouting.scoutFiltered(org.mockito.ArgumentMatchers.any())).thenReturn(future);
        var update = MainView.class.getDeclaredMethod("updateScoutingProgress");
        update.setAccessible(true);
        update.invoke(view);
        return future;
    }

    private static com.vaadin.flow.component.dialog.Dialog scoutingDialog(com.vaadin.flow.component.UI ui, String title) {
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return ui.getChildren().filter(com.vaadin.flow.component.dialog.Dialog.class::isInstance)
                .map(com.vaadin.flow.component.dialog.Dialog.class::cast)
                .filter(d -> title.equals(d.getHeaderTitle())).findFirst().orElseThrow();
    }

    private static com.vaadin.flow.component.button.Button footerButton(com.vaadin.flow.component.dialog.Dialog dialog, String label) {
        return dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                .filter(com.vaadin.flow.component.button.Button.class::isInstance)
                .map(com.vaadin.flow.component.button.Button.class::cast)
                .filter(b -> label.equals(b.getText())).findFirst().orElseThrow();
    }

    @Test
    void playerInfoDistinguishesLoadedTraitsNoTraitsAndMissingSnapshotData() {
        MainView view = view();
        for (var sample : java.util.Map.of(
                "Moves Into Channels", "Moves Into Channels", "", "None").entrySet()) {
            var player = playerRow(java.util.Map.of("name", "Trait player", "player_traits", sample.getKey()));
            assertEquals(sample.getValue(), view.playerInfoFields(player).stream()
                    .filter(field -> "Player Traits".equals(field.label())).findFirst().orElseThrow().value());
        }
        assertEquals("Not loaded", view.playerInfoFields(playerRow(java.util.Map.of("name", "Legacy"))).stream()
                .filter(field -> "Player Traits".equals(field.label())).findFirst().orElseThrow().value());
    }

    @Test
    void roleMenusHaveBundledOptionsAndAttributePriorities() throws Exception {
        var factory = MainView.class.getDeclaredMethod("roleComboBox", String.class, String.class);
        factory.setAccessible(true);
        for (String phase : List.of(PlayerRoleAttributeCatalog.IN_POSSESSION, PlayerRoleAttributeCatalog.OUT_OF_POSSESSION)) {
            var combo = (com.vaadin.flow.component.combobox.ComboBox<?>) factory.invoke(null, phase, phase);
            var roles = combo.getListDataView().getItems().toList();
            assertFalse(roles.isEmpty(), phase);
            for (Object role : roles) {
                var priorities = PlayerRoleAttributeCatalog.priorities(phase, (String) role);
                assertFalse(priorities.isEmpty(), phase + ": " + role);
                assertTrue(priorities.values().stream().allMatch(p -> List.of("primary", "secondary").contains(p)));
            }
        }
        assertEquals("primary", PlayerRoleAttributeCatalog.priorities(
                PlayerRoleAttributeCatalog.IN_POSSESSION, "Goalkeeper").get("aerial_ability"));
    }

    @Test
    void clubTabsSeparateOverviewFinancesAndMissingCoefficients() throws Exception {
        MainView view = view();
        var club = com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(
                java.util.Map.of("uniqueId", 1036L, "name", "Feyenoord", "balance", 123456L));
        var method = MainView.class.getDeclaredMethod("opponentClubView", club.getClass(), String.class);
        method.setAccessible(true);
        var result = (com.vaadin.flow.component.Component) method.invoke(view, club, "Feyenoord");
        var tabs = descendants(result).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("Overview", "Finances", "Coefficients"),
                tabs.getChildren().map(Tab.class::cast).map(Tab::getLabel).toList());
        assertTrue(textOf(result).contains("Training Facilities"));
        assertFalse(textOf(result).contains("Balance"));
        selectClubTab(result, "Finances");
        assertTrue(textOf(result).contains("Balance"));
        assertFalse(textOf(result).contains("Training Facilities"));
        selectClubTab(result, "Coefficients");
        assertTrue(textOf(result).contains("No coefficients are loaded for this club."));
        selectClubTab(result, "Overview");
        assertTrue(textOf(result).contains("Training Facilities"));
    }

    private static String textOf(com.vaadin.flow.component.Component component) {
        return component.getElement().getTextRecursively();
    }

    private static void selectClubTab(com.vaadin.flow.component.Component root, String label) {
        var tabs = descendants(root).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
        tabs.setSelectedTab(tabs.getChildren().map(Tab.class::cast)
                .filter(t -> label.equals(t.getLabel())).findFirst().orElseThrow());
    }

    @Test
    void navigationKeepsHeadingFiltersAndWorkspaceInSync() throws Exception {
        MainView view = view();
        Tabs tabs = field(view, "tabs");
        com.vaadin.flow.component.html.H1 title = field(view, "workspaceTitle");
        com.vaadin.flow.component.button.Button filter = field(view, "filterButton");
        assertEquals(Tabs.Orientation.VERTICAL, tabs.getOrientation());
        assertEquals("Players", title.getText());
        assertTrue(filter.isVisible());

        for (String name : List.of("staffTab", "clubsTab", "coefficientsTab", "competitionsTab",
                "fixturesTab", "recruitmentTab", "aiAssistantTab", "playersTab")) {
            Tab tab = field(view, name);
            tabs.setSelectedTab(tab);
            assertEquals(tab.getLabel(), title.getText());
            assertEquals(List.of("playersTab", "staffTab", "clubsTab", "competitionsTab").contains(name),
                    filter.isVisible());
            com.vaadin.flow.component.html.Div content = field(view, "content");
            assertTrue(content.getChildren().findAny().isPresent());
        }
    }

    @Test void competitionIncomeIsShownWithoutCoefficientsLoansOrLedgerPanel()throws Exception {
        MainView view=view();ClubDatabaseService service=field(view,"clubs");
        var profile=new com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.Profile(
                java.time.LocalDate.of(2026,8,3),java.util.stream.IntStream.range(0,4)
                .mapToObj(i->new com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.Period(i,List.of(),List.of())).toList(),"");
        when(service.findClubCompetitionIncome(1036L)).thenReturn(java.util.Optional.of(new ClubDatabaseService.CompetitionIncome(profile,java.util.Map.of(1301394L,"UEFA Champions League"))));
        var club=com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(java.util.Map.of("uniqueId",1036L,"name","sc Heerenveen"));
        var method=MainView.class.getDeclaredMethod("opponentClubView",com.github.fmaiassistent.domain.entity.ClubEntity.class,String.class);method.setAccessible(true);
        var result=(com.vaadin.flow.component.Component)method.invoke(view,club,"sc Heerenveen");
        selectClubTab(result, "Finances");
        assertTrue(descendants(result).anyMatch(c->c instanceof ClubFinancialCompetitionsPanel));
    }

    @Test void clubLedgerRemainsVisibleWithoutCoefficientsOrLoans()throws Exception {
        MainView view=view();ClubDatabaseService service=field(view,"clubs");
        var profile=new com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.Profile(
                java.time.LocalDate.of(2026,8,3),java.util.Arrays.stream(com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.PeriodName.values())
                .map(p->new com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.Period(p,java.util.Collections.nCopies(57,0))).toList(),"");
        when(service.findClubFinancialLedger(1036L)).thenReturn(java.util.Optional.of(profile));
        var club=com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(java.util.Map.of("uniqueId",1036L,"name","sc Heerenveen"));
        var method=MainView.class.getDeclaredMethod("opponentClubView",com.github.fmaiassistent.domain.entity.ClubEntity.class,String.class);method.setAccessible(true);
        var result=(com.vaadin.flow.component.Component)method.invoke(view,club,"sc Heerenveen");
        selectClubTab(result, "Finances");
        assertTrue(descendants(result).anyMatch(c->c instanceof ClubFinancialLedgerPanel));
    }

    @Test
    void clubLoanDetailsRemainVisibleForAClubWithoutCoefficients()throws Exception {
        MainView view=view();ClubDatabaseService service=field(view,"clubs");
        var profile=com.github.fmaiassistent.memory.ClubFinancialLoanLayout.values(
                "a6cfa205d6b80500a4e90000000000008a3207089032ee0701000000",0x07F41A8D,
                com.github.fmaiassistent.memory.ClubFinancialLoanLayout.MODULE_SHA256).orElseThrow();
        when(service.findClubCoefficients(1036L)).thenReturn(java.util.Optional.empty());
        when(service.findClubFinancialLoans(1036L)).thenReturn(java.util.Optional.of(profile));
        var club=com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(java.util.Map.of("uniqueId",1036L,"name","sc Heerenveen"));
        var method=MainView.class.getDeclaredMethod("opponentClubView",com.github.fmaiassistent.domain.entity.ClubEntity.class,String.class);method.setAccessible(true);
        var result=(com.vaadin.flow.component.Component)method.invoke(view,club,"sc Heerenveen");
        selectClubTab(result, "Finances");
        assertTrue(descendants(result).anyMatch(c->c instanceof ClubFinancialLoansPanel));
    }

    @Test
    void clubGridUsesNativeIdentityAndExactCurrentWagesRatherThanBlankCells() throws Exception {
        var club=com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(java.util.Map.of(
                "uniqueId",1036L,"name","sc Heerenveen","currentWagesIncludingBonuses",1763603L));
        var column=MainView.class.getDeclaredMethod("clubColumnValue",com.github.fmaiassistent.domain.entity.ClubEntity.class,String.class);
        column.setAccessible(true);
        assertEquals(1036L,column.invoke(null,club,"UNIQUE_ID"));
        assertEquals(1763603L,column.invoke(null,club,"CURRENT_WAGES_INCLUDING_BONUSES"));
    }

    @Test
    void contractTabsQueryTheSelectedNativeOwnerInBothDialogs() throws Exception {
        var ui=new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            MainView view=view();
            com.github.fmaiassistent.repository.ContractRepository repository=field(view,"contracts");
            var player=playerRow(java.util.Map.of("unique_id",43390774L));
            var staff=com.github.fmaiassistent.domain.entity.StaffEntity.fromExportRow(java.util.Map.of("unique_id",156L));
            for(var entity:List.of(player,staff)) {
                var id=entity.getClass().getDeclaredField("id");id.setAccessible(true);id.set(entity,7L);
            }
            for(var entity:List.of(player,staff)) {
                String method=entity==player?"openPlayerDetailsDialog":"openStaffDetailsDialog";
                var open=MainView.class.getDeclaredMethod(method,entity.getClass());open.setAccessible(true);open.invoke(view,entity);
                ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
                var dialog=ui.getChildren().filter(c->c instanceof com.vaadin.flow.component.dialog.Dialog)
                        .map(c->(com.vaadin.flow.component.dialog.Dialog)c).filter(com.vaadin.flow.component.dialog.Dialog::isOpened).findFirst().orElseThrow();
                var tabs=descendants(dialog).filter(c->c instanceof Tabs).map(c->(Tabs)c).findFirst().orElseThrow();
                var contractsTab=tabs.getChildren().filter(c->c instanceof Tab&&"Contracts".equals(((Tab)c).getLabel())).map(c->(Tab)c).findFirst().orElseThrow();
                tabs.setSelectedTab(contractsTab);
                assertTrue(descendants(dialog).anyMatch(c->c instanceof ContractDetailsPanel));
                dialog.close();
            }
            org.mockito.Mockito.verify(repository).findPlayerContracts(7L,43390774L);
            org.mockito.Mockito.verify(repository).findStaffContracts(7L,156L);
        } finally { com.vaadin.flow.component.UI.setCurrent(null); }
    }

    private static java.util.stream.Stream<com.vaadin.flow.component.Component> descendants(com.vaadin.flow.component.Component root) {
        return root.getChildren().flatMap(c->java.util.stream.Stream.concat(java.util.stream.Stream.of(c),descendants(c)));
    }

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
        com.vaadin.flow.component.grid.Grid<?> clubGrid=field(view,"clubsGrid");
        assertTrue(clubGrid.getColumns().stream().anyMatch(c->"UNIQUE_ID".equals(c.getKey())));
        assertSortSurvivesTabSwitch(tabs, clubsTab, competitionsTab, field(view, "clubsGrid"), "REPUTATION");
        assertSortSurvivesTabSwitch(tabs, competitionsTab, playersTab,
                field(view, "competitionsGrid"), "REPUTATION");
    }

    @Test
    void freshnessBadgeMapping() {
        assertEquals(MainView.FreshnessBadge.EMPTY,
                MainView.badgeFor(java.util.Map.of("state", "not_loaded")));
        assertEquals(MainView.FreshnessBadge.EMPTY, MainView.badgeFor(java.util.Map.of()));
        assertEquals(MainView.FreshnessBadge.STALE,
                MainView.badgeFor(java.util.Map.of("state", "loaded", "stale", Boolean.TRUE)));
        assertEquals(MainView.FreshnessBadge.CURRENT,
                MainView.badgeFor(java.util.Map.of("state", "loaded", "stale", Boolean.FALSE)));
        assertEquals(MainView.FreshnessBadge.UNKNOWN,
                MainView.badgeFor(java.util.Map.of("state", "loaded")));
    }

    @Test
    void injuredPlayerInfoIncludesInjuryDetails() {
        MainView view = view();
        com.github.fmaiassistent.domain.entity.PlayerEntity injured = playerRow(
                java.util.Map.of(
                        "name", "Hurt Håland",
                        "injured", Boolean.TRUE,
                        "injury", "Torn calf",
                        "injury_start_date", "2026-09-01",
                        "injury_expected_return", "12 days",
                        "injury_full_training_days_remaining", 9,
                        "injury_light_training_days_remaining", 4));

        assertEquals("Injured: Torn calf — full training in 9 days (expected return 12 days)",
                MainView.injurySummary(injured));
        java.util.List<String> labels = view.playerInfoFields(injured).stream()
                .map(field -> field.label()).toList();
        assertTrue(labels.containsAll(java.util.List.of(
                "Injured", "Injury", "Injury Start Date", "Expected Return",
                "Full Training In (days)", "Light Training In (days)")));
    }

    @Test
    void fitPlayerInfoOmitsInjuryDetails() {
        MainView view = view();
        com.github.fmaiassistent.domain.entity.PlayerEntity fit = playerRow(
                java.util.Map.of("name", "Fit Felipe", "injured", Boolean.FALSE));

        java.util.List<String> labels = view.playerInfoFields(fit).stream()
                .map(field -> field.label()).toList();
        assertFalse(labels.contains("Injury"));
        assertFalse(labels.contains("Expected Return"));
    }

    @Test
    void playerDetailsShowFitnessAndKeepLegacyFitnessUnknown() {
        MainView view = view();
        var loaded = playerRow(java.util.Map.of("condition", 9587, "sharpness", 10000, "fatigue", -123,
                "match_load", "Light", "training_load", "Medium", "injury_susceptibility", "High",
                "overall_risk", "Increased"));
        var fields = view.playerInfoFields(loaded).stream().collect(java.util.stream.Collectors.toMap(
                field -> field.label(), field -> field.value() == null ? "" : field.value()));
        assertEquals("95.87%", fields.get("Condition"));
        assertEquals("100.00%", fields.get("Sharpness"));
        assertEquals(-123, fields.get("Fatigue (raw)"));
        assertEquals("Light", fields.get("Match Load"));
        assertEquals("Medium", fields.get("Training Load"));
        assertEquals("High", fields.get("Injury Susceptibility"));
        assertEquals("Increased", fields.get("Overall Risk"));
        var legacy = view.playerInfoFields(playerRow(java.util.Map.of("name", "Legacy")));
        assertEquals("Unknown", legacy.stream().filter(field -> field.label().equals("Condition"))
                .findFirst().orElseThrow().value());
    }

    @Test
    void dialogFilterBuilderMapsFieldsToCriteria() {
        MainView view = view();
        com.vaadin.flow.component.textfield.TextField name = new com.vaadin.flow.component.textfield.TextField();
        name.setValue("Haaland");
        com.vaadin.flow.component.select.Select<String> gender = new com.vaadin.flow.component.select.Select<>();
        gender.setItems("", "male", "female");
        gender.setValue("male");
        com.vaadin.flow.component.combobox.ComboBox<String> playingNation = new com.vaadin.flow.component.combobox.ComboBox<>();
        com.vaadin.flow.component.combobox.ComboBox<String> playingCompetition = new com.vaadin.flow.component.combobox.ComboBox<>();
        com.vaadin.flow.component.combobox.ComboBox<String> club = new com.vaadin.flow.component.combobox.ComboBox<>();
        club.setItems("Dortmund");
        club.setValue("Dortmund");
        com.vaadin.flow.component.combobox.ComboBox<String> nationality = new com.vaadin.flow.component.combobox.ComboBox<>();
        com.vaadin.flow.component.textfield.IntegerField ageMin = new com.vaadin.flow.component.textfield.IntegerField();
        ageMin.setValue(20);
        com.vaadin.flow.component.textfield.IntegerField ageMax = new com.vaadin.flow.component.textfield.IntegerField();
        ageMax.setValue(30);
        com.vaadin.flow.component.textfield.IntegerField heightMin = new com.vaadin.flow.component.textfield.IntegerField();
        com.vaadin.flow.component.textfield.IntegerField heightMax = new com.vaadin.flow.component.textfield.IntegerField();
        com.vaadin.flow.component.textfield.IntegerField caMax = new com.vaadin.flow.component.textfield.IntegerField();
        caMax.setValue(180);
        MainView.LongField askingMin = new MainView.LongField("Asking price min", 5_000_000L);
        com.vaadin.flow.component.datepicker.DatePicker contractFrom =
                new com.vaadin.flow.component.datepicker.DatePicker();
        com.vaadin.flow.component.datepicker.DatePicker contractTo =
                new com.vaadin.flow.component.datepicker.DatePicker();

        com.github.fmaiassistent.repository.PlayerFilterCriteria criteria = view.buildPlayerFilterFromDialog(
                name, gender, playingNation, playingCompetition, club, nationality,
                ageMin, ageMax, heightMin, heightMax,
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(), caMax,
                new com.vaadin.flow.component.textfield.IntegerField(),
                new com.vaadin.flow.component.textfield.IntegerField(),
                askingMin,
                new MainView.LongField("Asking price max", null),
                new MainView.LongField("Weekly Salary max", null),
                contractFrom, contractTo,
                java.util.Map.of(), new java.util.LinkedHashMap<>(), new com.vaadin.flow.component.html.Div());

        assertEquals("Haaland", criteria.name());
        assertEquals("male", criteria.gender());
        assertEquals("Dortmund", criteria.club());
        assertEquals(20, criteria.ageMin());
        assertEquals(30, criteria.ageMax());
        assertEquals(1, criteria.caMin());
        assertEquals(180, criteria.caMax());
        assertEquals(5_000_000L, criteria.askingPriceMin());
    }

    private static com.github.fmaiassistent.domain.entity.PlayerEntity playerRow(java.util.Map<String, Object> values) {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        com.github.fmaiassistent.exporter.PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.putAll(values);
        return com.github.fmaiassistent.domain.entity.PlayerEntity.fromExportRow(row);
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
        return view(mock(com.github.fmaiassistent.repository.ContractRepository.class));
    }

    static MainView view(com.github.fmaiassistent.repository.ContractRepository contracts) {
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
                mock(com.github.fmaiassistent.repository.FixtureRepository.class),
                mock(AppSettingsService.class), mock(ScoutSettingsService.class), mock(ScoutReportService.class),
                mock(SnapshotStatusService.class),
                codex, antigravity, copilot, openRouter, tactics, managedClub,
                mock(com.github.fmaiassistent.shortlist.ShortlistFileService.class),
                mock(com.github.fmaiassistent.recruitment.RecruitmentCaseService.class),
                contracts);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
