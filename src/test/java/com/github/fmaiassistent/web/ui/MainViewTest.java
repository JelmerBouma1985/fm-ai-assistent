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
    void fixturesManagerSelectionIsIndependentOfAiAndSurvivesTabChanges() throws Exception {
        MainView view = view();
        ManagedClubContextService contexts = field(view, "managedClubContexts");
        var ties = manager(101, "Ties Bouma", "SC Cambuur");
        var jemme = manager(102, "Jemme Bomma", "Helmond Sport");
        var jelmer = manager(103, "Jelmer Bouma", "Feyenoord");
        when(contexts.managers()).thenReturn(List.of(ties, jemme, jelmer));
        when(contexts.current()).thenReturn(jelmer);
        com.github.fmaiassistent.repository.FixtureRepository fixtures = field(view, "fixtures");
        var cambuurMatch = new com.github.fmaiassistent.domain.entity.FixtureEntity("2035-01-01", "15:00", "League",
                "SC Cambuur", "Opponent A", "Opponent A", "Stadium", true);
        var helmondMatch = new com.github.fmaiassistent.domain.entity.FixtureEntity("2035-01-01", "15:00", "League",
                "Opponent B", "Helmond Sport", "Opponent B", "Stadium", false);
        when(fixtures.findAllByOrderByKickoffDateAscKickoffTimeAsc()).thenReturn(List.of(cambuurMatch, helmondMatch));
        Tabs tabs = field(view, "tabs");
        tabs.setSelectedTab(field(view, "fixturesTab"));
        com.vaadin.flow.component.select.Select<ManagedClubContext> select = field(view, "fixturesManagerSelect");
        Grid<com.github.fmaiassistent.domain.entity.FixtureEntity> grid = field(view, "fixturesGrid");
        assertEquals(3, select.getListDataView().getItems().count());
        assertEquals(ties, select.getValue());
        assertEquals(List.of(cambuurMatch), grid.getListDataView().getItems().toList());

        select.setValue(jemme);
        assertEquals(List.of(helmondMatch), grid.getListDataView().getItems().toList());
        assertSame(jelmer, contexts.current());
        org.mockito.Mockito.verify(contexts, org.mockito.Mockito.never()).selectManager(org.mockito.ArgumentMatchers.anyLong());

        when(contexts.current()).thenReturn(ties);
        tabs.setSelectedTab(field(view, "playersTab"));
        tabs.setSelectedTab(field(view, "fixturesTab"));
        assertEquals(jemme, select.getValue());
        assertEquals(List.of(helmondMatch), grid.getListDataView().getItems().toList());

        when(contexts.managers()).thenReturn(List.of(ties, jelmer));
        tabs.setSelectedTab(field(view, "playersTab"));
        tabs.setSelectedTab(field(view, "fixturesTab"));
        assertEquals(ties, select.getValue());
        assertEquals(List.of(cambuurMatch), grid.getListDataView().getItems().toList());
    }

    private static ManagedClubContext manager(long id, String name, String club) {
        return new ManagedClubContext(id, ManagedClubContext.State.AVAILABLE, name, id, club,
                "", "", "", null, null, null, null, id, "Loaded");
    }

    @Test
    @SuppressWarnings("unchecked")
    void settingsOffersFourMemoryPresetsAndSavesTheChoiceForRestart() throws Exception {
        var ui = new com.vaadin.flow.component.UI();
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            MainView view = view();
            var memory = MainViewTest.<com.github.fmaiassistent.desktop.DesktopMemorySettings>field(view, "memorySettings");
            when(memory.available()).thenReturn(true);
            when(memory.preset()).thenReturn(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.MEDIUM);
            var dialog = view.settingsDialog();
            var select = (com.vaadin.flow.component.select.Select<com.github.fmaiassistent.domain.enums.DesktopMemoryPreset>)
                    descendants(dialog).filter(com.vaadin.flow.component.select.Select.class::isInstance)
                            .map(com.vaadin.flow.component.select.Select.class::cast)
                            .filter(component -> "Database size".equals(component.getLabel())).findFirst().orElseThrow();
            assertEquals(4, select.getListDataView().getItems().count());
            assertTrue(select.getHelperText().contains("Restart"));
            assertEquals(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.MEDIUM, select.getValue());
            select.setValue(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.LARGE);
            dialog.open();
            dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                    .filter(com.vaadin.flow.component.button.Button.class::isInstance)
                    .map(com.vaadin.flow.component.button.Button.class::cast)
                    .filter(button -> "Save".equals(button.getText())).findFirst().orElseThrow().click();
            org.mockito.Mockito.verify(memory).save(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.LARGE);
            assertFalse(dialog.isOpened());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedMemorySaveKeepsSettingsOpenWithAnError() throws Exception {
        com.vaadin.flow.component.UI.setCurrent(new com.vaadin.flow.component.UI());
        try {
            MainView view = view();
            var memory = MainViewTest.<com.github.fmaiassistent.desktop.DesktopMemorySettings>field(view, "memorySettings");
            when(memory.available()).thenReturn(true);
            when(memory.preset()).thenReturn(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.MEDIUM);
            org.mockito.Mockito.doThrow(new java.io.IOException("read only")).when(memory)
                    .save(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.LARGE);
            var dialog = view.settingsDialog();
            var select = (com.vaadin.flow.component.select.Select<com.github.fmaiassistent.domain.enums.DesktopMemoryPreset>)
                    descendants(dialog).filter(com.vaadin.flow.component.select.Select.class::isInstance)
                            .map(com.vaadin.flow.component.select.Select.class::cast)
                            .filter(component -> "Database size".equals(component.getLabel())).findFirst().orElseThrow();
            select.setValue(com.github.fmaiassistent.domain.enums.DesktopMemoryPreset.LARGE);
            dialog.open();
            dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
                    .filter(com.vaadin.flow.component.button.Button.class::isInstance)
                    .map(com.vaadin.flow.component.button.Button.class::cast)
                    .filter(button -> "Save".equals(button.getText())).findFirst().orElseThrow().click();
            assertTrue(dialog.isOpened());
            assertTrue(textOf(dialog).contains("Could not save settings"));
            org.mockito.Mockito.verify(MainViewTest.<AppSettingsService>field(view, "settings"), org.mockito.Mockito.never())
                    .saveCurrency(org.mockito.ArgumentMatchers.any());
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
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
    void managedClubWarningAppearsBesideCurrentFreshnessAndClearsAfterRecovery() throws Exception {
        MainView view = view();
        var apply = MainView.class.getDeclaredMethod("applyFreshness", java.util.Map.class, boolean.class);
        apply.setAccessible(true);
        apply.invoke(view, java.util.Map.of("state", "loaded", "stale", false,
                "managed_club_state", "unavailable", "managed_club_message", "Manager unreadable"), false);
        com.vaadin.flow.component.html.Span warning = field(view, "managedClubWarning");
        com.vaadin.flow.component.button.Button freshness = field(view, "freshnessBadge");
        assertTrue(warning.isVisible());
        assertEquals("Failed to load managed club", warning.getText());
        assertEquals("Manager unreadable", warning.getElement().getAttribute("title"));
        assertEquals(freshness.getParent(), warning.getParent());
        assertEquals("Data current", freshness.getText());

        apply.invoke(view, java.util.Map.of("state", "loaded", "stale", false,
                "managed_club_state", "available"), false);
        assertFalse(warning.isVisible());
    }

    @Test
    void coefficientWarningDoesNotChangeFreshnessAndClearsAfterRecovery() throws Exception {
        MainView view = view();
        var apply = MainView.class.getDeclaredMethod("applyFreshness", java.util.Map.class, boolean.class);
        apply.setAccessible(true);
        apply.invoke(view, java.util.Map.of("state", "loaded", "stale", false,
                "uefa_coefficients_failure", "UEFA coefficient read failed"), false);
        com.vaadin.flow.component.html.Span warning = field(view, "coefficientWarning");
        com.vaadin.flow.component.button.Button freshness = field(view, "freshnessBadge");
        assertTrue(warning.isVisible());
        assertEquals("Failed to load club coefficients", warning.getText());
        assertEquals("UEFA coefficient read failed", warning.getElement().getAttribute("title"));
        assertEquals(freshness.getParent(), warning.getParent());
        assertEquals("Data current", freshness.getText());
        apply.invoke(view, java.util.Map.of("state", "loaded", "stale", false,
                "uefa_coefficients_failure", ""), false);
        assertFalse(warning.isVisible());
    }

    @Test
    void fixtureWarningIsIndependentOfFreshnessAndClearsAfterRecovery() throws Exception {
        MainView view = view();
        var apply = MainView.class.getDeclaredMethod("applyFreshness", java.util.Map.class, boolean.class);
        apply.setAccessible(true);
        apply.invoke(view, java.util.Map.of("state", "loaded", "stale", false,
                "managed_club_state", "available", "fixtures_failure", "Fixture read failed"), false);
        com.vaadin.flow.component.html.Span warning = field(view, "fixtureWarning");
        com.vaadin.flow.component.html.Span managerWarning = field(view, "managedClubWarning");
        com.vaadin.flow.component.button.Button freshness = field(view, "freshnessBadge");
        assertTrue(warning.isVisible());
        assertFalse(managerWarning.isVisible());
        assertEquals("Failed to load fixtures", warning.getText());
        assertEquals("Fixture read failed", warning.getElement().getAttribute("title"));
        assertEquals(freshness.getParent(), warning.getParent());
        assertEquals("Data current", freshness.getText());
        apply.invoke(view, java.util.Map.of("state", "loaded", "stale", false,
                "fixtures_failure", ""), false);
        assertFalse(warning.isVisible());
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
                mock(AppSettingsService.class), mock(com.github.fmaiassistent.desktop.DesktopMemorySettings.class),
                mock(SnapshotStatusService.class),
                codex, antigravity, copilot, openRouter, tactics, managedClub,
                mock(com.github.fmaiassistent.shortlist.ShortlistFileService.class),
                mock(com.github.fmaiassistent.recruitment.RecruitmentCaseService.class),
                contracts, mock(com.github.fmaiassistent.update.UpdateCoordinator.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
