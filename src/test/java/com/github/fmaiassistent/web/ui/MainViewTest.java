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

    @Test void competitionIncomeIsShownWithoutCoefficientsLoansOrLedgerPanel()throws Exception {
        MainView view=view();ClubDatabaseService service=field(view,"clubs");
        var profile=com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.values(
                com.github.fmaiassistent.exporter.ClubFinancialCompetitionCaptureTest.expectedPayloads().get(1036L),0x07F41A8D,
                com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
        when(service.findClubCompetitionIncome(1036L)).thenReturn(java.util.Optional.of(new ClubDatabaseService.CompetitionIncome(profile,java.util.Map.of(1301394L,"UEFA Champions League"))));
        var club=com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(java.util.Map.of("uniqueId",1036L,"name","sc Heerenveen"));
        var method=MainView.class.getDeclaredMethod("opponentClubView",com.github.fmaiassistent.domain.entity.ClubEntity.class,String.class);method.setAccessible(true);
        var result=(com.vaadin.flow.component.Component)method.invoke(view,club,"sc Heerenveen");
        assertTrue(descendants(result).anyMatch(c->c instanceof ClubFinancialCompetitionsPanel));
    }

    @Test void clubLedgerRemainsVisibleWithoutCoefficientsOrLoans()throws Exception {
        MainView view=view();ClubDatabaseService service=field(view,"clubs");
        var rows=com.github.fmaiassistent.exporter.ClubFinancialLedgerCaptureTest.financeRows();
        var profile=com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.values(
                com.github.fmaiassistent.exporter.ClubFinancialLedgerCaptureTest.payload(rows.get(1036L)),
                com.github.fmaiassistent.exporter.ClubFinancialLedgerCaptureTest.CLOCK,
                com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
        when(service.findClubFinancialLedger(1036L)).thenReturn(java.util.Optional.of(profile));
        var club=com.github.fmaiassistent.domain.entity.ClubEntity.fromExportRow(java.util.Map.of("uniqueId",1036L,"name","sc Heerenveen"));
        var method=MainView.class.getDeclaredMethod("opponentClubView",com.github.fmaiassistent.domain.entity.ClubEntity.class,String.class);method.setAccessible(true);
        var result=(com.vaadin.flow.component.Component)method.invoke(view,club,"sc Heerenveen");
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
                mock(AppSettingsService.class), mock(SnapshotStatusService.class),
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
