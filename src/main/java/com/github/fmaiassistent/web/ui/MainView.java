package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.staff.StaffAttributeDefinitions;
import com.github.fmaiassistent.staff.StaffRoleRatingCalculator;
import com.github.fmaiassistent.service.*;
import com.github.fmaiassistent.codex.CodexConversationService;
import com.github.fmaiassistent.antigravity.AntigravityConversationService;
import com.github.fmaiassistent.copilot.CopilotConversationService;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import com.github.fmaiassistent.snapshot.RefreshCoordinator;
import com.github.fmaiassistent.domain.enums.MoneyCurrency;
import com.github.fmaiassistent.repository.*;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import com.github.fmaiassistent.player.PositionTextFormatter;
import com.github.fmaiassistent.player.PlayerColumnNames;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ModalityMode;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Route("")
@PageTitle("FM AI Assistent")
@CssImport("./styles/main-view.css")
@CssImport("./styles/player-grid.css")
public class MainView extends VerticalLayout {
    private static final Set<String> NUMERIC_SORT_COLUMNS = Set.of(
            "ID", "CLUB_ID", "PLAYING_CLUB_ID", "CURRENT_REPUTATION", "HOME_REPUTATION", "WORLD_REPUTATION",
            "CA", "PA", "ASKING_PRICE", "ASKING_PRICE_RAW", "SALARY_PA", "SALARY_WEEKLY_RAW", "AGE", "HEIGHT_CM",
            "REPUTATION", "TRANSFER_BUDGET", "PAYROLL_BUDGET", "TRAINING_FACILITIES", "YOUTH_FACILITIES",
            "YOUTH_COACHING", "YOUTH_RECRUITMENT", "CORPORATE_FACILITIES",
            "GOALKEEPER", "DEFENDER_LEFT", "DEFENDER_CENTRAL", "DEFENDER_RIGHT", "WING_BACK_LEFT",
            "DEFENSIVE_MIDFIELDER", "WING_BACK_RIGHT", "MIDFIELDER_LEFT", "MIDFIELDER_CENTRAL",
            "MIDFIELDER_RIGHT", "ATTACKING_MIDFIELDER_LEFT", "ATTACKING_MIDFIELDER_CENTRAL",
            "ATTACKING_MIDFIELDER_RIGHT", "STRIKER",
            "CROSSING", "DRIBBLING", "FINISHING", "HEADING", "LONG_SHOTS", "MARKING", "OFF_THE_BALL",
            "PASSING", "PENALTIES", "TACKLING", "VISION", "HANDLING", "AERIAL_ABILITY", "COMMAND_OF_AREA",
            "COMMUNICATION", "KICKING", "THROWING", "ANTICIPATION", "DECISIONS", "ONE_ON_ONES",
            "POSITIONING", "REFLEXES", "FIRST_TOUCH", "TECHNIQUE", "LEFT_FOOT", "RIGHT_FOOT", "FLAIR",
            "CORNERS", "TEAMWORK", "WORK_RATE", "LONG_THROWS", "ECCENTRICITY", "RUSHING_OUT",
            "TENDENCY_TO_PUNCH", "ACCELERATION", "FREE_KICKS", "STRENGTH", "STAMINA", "PACE",
            "JUMPING_REACH", "LEADERSHIP", "DIRTINESS", "BALANCE", "BRAVERY", "CONSISTENCY",
            "AGGRESSION", "AGILITY", "IMPORTANT_MATCHES", "INJURY_PRONENESS", "VERSATILITY",
            "NATURAL_FITNESS", "DETERMINATION", "COMPOSURE", "CONCENTRATION");
    private static final Set<String> MONEY_COLUMNS = Set.of(
            "ASKING_PRICE", "ASKING_PRICE_RAW", "SALARY_PA", "SALARY_WEEKLY_RAW",
            "BALANCE", "TRANSFER_BUDGET", "PAYROLL_BUDGET");

    private final RefreshCoordinator refreshes;
    private final PlayerDatabaseService players;
    private final StaffDatabaseService staff;
    private final ClubDatabaseService clubs;
    private final CompetitionDatabaseService competitions;
    private final AppSettingsService settings;
    private final SnapshotStatusService snapshots;

    private final Dialog loadingDialog = new Dialog();
    private final ProgressBar spinner = new ProgressBar();
    private final Button loadButton = new Button("Load data", VaadinIcon.DATABASE.create());
    private final Button freshnessButton = new Button(VaadinIcon.CLOCK.create());
    private final Button settingsButton = new Button(VaadinIcon.COG.create());
    private final Button filterButton = new Button("Filter", VaadinIcon.FILTER.create());
    private final Span status = new Span();
    private final Tabs tabs = new Tabs();
    private final Div content = new Div();
    private final Grid<PlayerEntity> playersGrid = new Grid<>();
    private final Grid<StaffEntity> staffGrid = new Grid<>();
    private final Grid<ClubEntity> clubsGrid = new Grid<>();
    private final Grid<CompetitionEntity> competitionsGrid = new Grid<>();
    private final AiAssistantView aiAssistant;

    private final Tab playersTab = new Tab("Players");
    private final Tab staffTab = new Tab("Staff");
    private final Tab clubsTab = new Tab("Clubs");
    private final Tab competitionsTab = new Tab("Competitions");
    private final Tab aiAssistantTab = new Tab("AI assistent");
    private PlayerFilterCriteria playerFilter = PlayerFilterCriteria.empty();
    private StaffFilterCriteria staffFilter = StaffFilterCriteria.empty();
    private ClubFilterCriteria clubFilter = ClubFilterCriteria.empty();
    private CompetitionFilterCriteria competitionFilter = CompetitionFilterCriteria.empty();
    private MoneyCurrency currency;

    public MainView(
            RefreshCoordinator refreshes,
            PlayerDatabaseService players,
            StaffDatabaseService staff,
            ClubDatabaseService clubs,
            CompetitionDatabaseService competitions,
            AppSettingsService settings,
            SnapshotStatusService snapshots,
            CodexConversationService codexConversations,
            AntigravityConversationService antigravityConversations,
            CopilotConversationService copilotConversations,
            TacticContextService tacticContexts,
            ManagedClubContextService managedClubContexts) {
        this.refreshes = refreshes;
        this.players = players;
        this.staff = staff;
        this.clubs = clubs;
        this.competitions = competitions;
        this.settings = settings;
        this.snapshots = snapshots;
        this.aiAssistant = new AiAssistantView(
                codexConversations, antigravityConversations, copilotConversations,
                tacticContexts, managedClubContexts);
        this.currency = settings.currency();

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("main-view");
        content.addClassName("workspace-content");

        add(header(), content);
        expand(content);
        configureTabs();
        configureGrid(playersGrid);
        configureGrid(staffGrid);
        configureGrid(clubsGrid);
        configureGrid(competitionsGrid);
        configureLoadingDialog();
        playersGrid.addItemClickListener(event -> openPlayerDetailsDialog(event.getItem()));
        staffGrid.addItemClickListener(event -> openStaffDetailsDialog(event.getItem()));
        updateStatus(null);
        showPlayers();
    }

    private Component header() {
        loadButton.addClickListener(event -> loadAllData());
        freshnessButton.addClickListener(event -> checkSnapshotFreshness());
        settingsButton.addClickListener(event -> openSettingsDialog());
        filterButton.addClickListener(event -> openFilterDialog());
        loadButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        loadButton.addClassName("load-button");
        freshnessButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        freshnessButton.setTooltipText("Check whether the loaded FM26 data is current");
        freshnessButton.getElement().setAttribute("aria-label", "Check data freshness");
        filterButton.addClassName("filter-button");
        settingsButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        settingsButton.addClassName("icon-button");
        settingsButton.setTooltipText("Settings");
        settingsButton.getElement().setAttribute("aria-label", "Settings");
        status.addClassName("app-status");

        Div brandIcon = new Div(VaadinIcon.CHART.create());
        brandIcon.addClassName("brand-icon");
        Span product = new Span("FM AI Assistent");
        product.addClassName("brand-title");
        Span descriptor = new Span("Recruitment intelligence");
        descriptor.addClassName("brand-descriptor");
        Div brandCopy = new Div(product, descriptor);
        brandCopy.addClassName("brand-copy");
        HorizontalLayout brand = new HorizontalLayout(brandIcon, brandCopy);
        brand.setAlignItems(Alignment.CENTER);
        brand.setSpacing(false);
        brand.addClassName("brand");

        HorizontalLayout actions = new HorizontalLayout(status, freshnessButton, loadButton, settingsButton);
        actions.setAlignItems(Alignment.CENTER);
        actions.setSpacing(false);
        actions.addClassName("app-actions");

        HorizontalLayout appBar = new HorizontalLayout(brand, actions);
        appBar.setWidthFull();
        appBar.setAlignItems(Alignment.CENTER);
        appBar.expand(brand);
        appBar.setPadding(false);
        appBar.setSpacing(false);
        appBar.addClassName("app-bar");

        HorizontalLayout navigation = new HorizontalLayout(tabs, filterButton);
        navigation.setWidthFull();
        navigation.setAlignItems(Alignment.CENTER);
        navigation.expand(tabs);
        navigation.setPadding(false);
        navigation.setSpacing(false);
        navigation.addClassName("workspace-nav");

        Div header = new Div(appBar, navigation);
        header.addClassName("app-header");
        return header;
    }

    private void configureTabs() {
        tabs.add(playersTab, staffTab, clubsTab, competitionsTab, aiAssistantTab);
        tabs.setWidthFull();
        tabs.addClassName("workspace-tabs");
        playersTab.addComponentAsFirst(VaadinIcon.USERS.create());
        staffTab.addComponentAsFirst(VaadinIcon.USER_STAR.create());
        clubsTab.addComponentAsFirst(VaadinIcon.OFFICE.create());
        competitionsTab.addComponentAsFirst(VaadinIcon.TROPHY.create());
        aiAssistantTab.addComponentAsFirst(VaadinIcon.CHAT.create());
        tabs.addSelectedChangeListener(event -> {
            filterButton.setVisible(event.getSelectedTab() == playersTab
                    || event.getSelectedTab() == staffTab
                    || event.getSelectedTab() == clubsTab
                    || event.getSelectedTab() == competitionsTab);
            if (event.getSelectedTab() == playersTab) {
                showPlayers();
            } else if (event.getSelectedTab() == staffTab) {
                showStaff();
            } else if (event.getSelectedTab() == clubsTab) {
                showClubs();
            } else if (event.getSelectedTab() == competitionsTab) {
                showCompetitions();
            } else {
                showAiAssistant();
            }
        });
    }

    private void configureGrid(Grid<?> grid) {
        grid.setSizeFull();
        grid.setColumnReorderingAllowed(true);
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER);
        grid.addClassName("data-grid");
    }

    private void loadAllData() {
        UI ui = UI.getCurrent();
        loadButton.setEnabled(false);
        loadButton.setText("Loading...");
        loadButton.setIcon(VaadinIcon.REFRESH.create());
        loadButton.addClassName("is-loading");
        loadingDialog.open();

        refreshes.refresh(
                        null,
                        DatabaseLoadAllService.LoadAllResult.defaultBuild(),
                        null)
                .thenAccept(result -> ui.access(() -> {
                    updateStatus(result);
                    aiAssistant.refreshManagedClubContext();
                    refreshSelectedTab();

                    Notification.show(
                            "Loaded RAM data",
                            3000,
                            Notification.Position.TOP_CENTER
                    );
                }))
                .exceptionally(ex -> {
                    ui.access(() -> {
                        aiAssistant.refreshManagedClubContext();
                        Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                                ? ex.getCause()
                                : ex;

                        Notification.show(
                                "Load failed: " + cause.getMessage(),
                                8000,
                                Notification.Position.TOP_CENTER
                        );
                    });

                    return null;
                })
                .whenComplete((result, ex) -> ui.access(() -> {
                    loadingDialog.close();
                    loadButton.setEnabled(true);
                    loadButton.setText("Load data");
                    loadButton.setIcon(VaadinIcon.DATABASE.create());
                    loadButton.removeClassName("is-loading");
                }));
    }

    private void checkSnapshotFreshness() {
        freshnessButton.setEnabled(false);
        UI ui = UI.getCurrent();
        snapshots.statusAsync(true)
                .thenAccept(snapshot -> ui.access(() -> {
                    Boolean stale = (Boolean) snapshot.get("stale");
                    String liveDate = Objects.toString(snapshot.get("live_game_date"), "unknown");
                    String loadedDate = Objects.toString(snapshot.get("game_date"), "unknown");
                    if (Boolean.TRUE.equals(stale)) {
                        status.setText("Data stale | Loaded " + loadedDate + " | FM " + liveDate);
                        Notification.show(
                                "FM26 has changed since the last load. Select Load data before asking for decisions.",
                                7000,
                                Notification.Position.TOP_CENTER);
                    } else if (Boolean.FALSE.equals(stale)) {
                        status.setText("Data current | Game date " + loadedDate);
                        Notification.show("Loaded FM26 snapshot is current", 2500, Notification.Position.TOP_CENTER);
                    } else {
                        status.setText("Data freshness unverified | Loaded " + loadedDate);
                        Notification.show(
                                "Could not verify the live FM26 game date. The loaded snapshot was left unchanged.",
                                5000,
                                Notification.Position.TOP_CENTER);
                    }
                }))
                .exceptionally(exception -> {
                    ui.access(() -> Notification.show(
                            "Freshness check failed: " + exception.getMessage(),
                            5000,
                            Notification.Position.TOP_CENTER));
                    return null;
                })
                .whenComplete((ignored, exception) -> ui.access(() -> freshnessButton.setEnabled(true)));
    }

    private void refreshSelectedTab() {
        if (tabs.getSelectedTab() == playersTab) {
            showPlayers();
        } else if (tabs.getSelectedTab() == staffTab) {
            showStaff();
        } else if (tabs.getSelectedTab() == clubsTab) {
            showClubs();
        } else if (tabs.getSelectedTab() == competitionsTab) {
            showCompetitions();
        } else {
            showAiAssistant();
        }
    }

    private void showAiAssistant() {
        content.removeAll();
        content.removeClassName("data-workspace");
        content.setSizeFull();
        content.add(aiAssistant);
    }

    private void showPlayers() {
        List<PlayerColumn> columns = List.of(
                new PlayerColumn("NAME", "Name", PlayerEntity::getName),
                new PlayerColumn("AGE", "Age", PlayerEntity::getAge),
                new PlayerColumn("HEIGHT_CM", "Height (cm)", PlayerEntity::getHeightCm),
                new PlayerColumn("NATIONALITY", "Nationality", PlayerEntity::getNationality),
                new PlayerColumn("CLUB", "Club", PlayerEntity::getClub),
                new PlayerColumn("PLAYING_CLUB", "Playing Club", PlayerEntity::getPlayingClub),
                new PlayerColumn("POSITION", "Position", PositionTextFormatter::format),
                new PlayerColumn("CA", "Current Ability", PlayerEntity::getCa),
                new PlayerColumn("PA", "Potential Ability", PlayerEntity::getPa),
                new PlayerColumn("SALARY_WEEKLY_RAW", "Salary Weekly", PlayerEntity::getSalaryWeeklyRaw),
                new PlayerColumn("ASKING_PRICE", "Asking Price", PlayerEntity::getAskingPrice),
                new PlayerColumn("CONTRACT_END_DATE", "Contract End Date", PlayerEntity::getContractEndDate),
                new PlayerColumn("TRANSFER_LISTED", "Transfer Listed", PlayerEntity::getTransferListed),
                new PlayerColumn("LISTED_FOR_LOAN", "Listed For Loan", PlayerEntity::getListedForLoan),
                new PlayerColumn("TRANSFER_AGREED", "Transfer Agreed", PlayerEntity::getTransferAgreed),
                new PlayerColumn("FUTURE_TRANSFER_CLUB", "Future Transfer Club", PlayerEntity::getFutureTransferClub),
                new PlayerColumn("FUTURE_TRANSFER_DATE", "Future Transfer Date", PlayerEntity::getFutureTransferDate),
                new PlayerColumn("FUTURE_TRANSFER_CONTRACT_END_DATE", "Future Contract End", PlayerEntity::getFutureTransferContractEndDate),
                new PlayerColumn("INJURED", "Injured", PlayerEntity::getInjured),
                new PlayerColumn("INJURY", "Injury", PlayerEntity::getInjury),
                new PlayerColumn("INJURY_LIGHT_TRAINING_DAYS_REMAINING", "Light Training Days", PlayerEntity::getInjuryLightTrainingDaysRemaining),
                new PlayerColumn("INJURY_FULL_TRAINING_DAYS_REMAINING", "Full Training Days", PlayerEntity::getInjuryFullTrainingDaysRemaining),
                new PlayerColumn("INJURY_EXPECTED_RETURN", "Expected Return", PlayerEntity::getInjuryExpectedReturn),
                new PlayerColumn("CURRENT_REPUTATION", "Current Reputation", PlayerEntity::getCurrentReputation),
                new PlayerColumn("HOME_REPUTATION", "Home Reputation", PlayerEntity::getHomeReputation),
                new PlayerColumn("WORLD_REPUTATION", "World Reputation", PlayerEntity::getWorldReputation));
        PlayerFilterCriteria activeFilter = playerFilter;
        setPlayerGrid(columns, activeFilter);
        setFilterActive(!playerFilter.isEmpty());
        if (!playerFilter.isEmpty()) {
            status.setText("Filtered players " + players.countPlayers(activeFilter)
                    + " | Total players " + players.countPlayers());
        }
    }

    private void showStaff() {
        List<StaffColumn> columns = new ArrayList<>();
        columns.add(new StaffColumn("NAME", "Name", StaffEntity::getName));
        columns.add(new StaffColumn("JOB", "Job", StaffEntity::getJob));
        columns.add(new StaffColumn("BEST_COACHING_ROLE", "Best Coaching Role", MainView::bestStaffRoleLabel));
        columns.add(new StaffColumn("BEST_COACHING_STARS", "Best Stars", MainView::bestStaffRoleStars));
        columns.add(new StaffColumn("CLUB", "Club", StaffEntity::getClub));
        columns.add(new StaffColumn("DIVISION", "Division", StaffEntity::getDivision));
        columns.add(new StaffColumn("NATIONALITY", "Nationality", StaffEntity::getNationality));
        columns.add(new StaffColumn("AGE", "Age", StaffEntity::getAge));
        columns.add(new StaffColumn("CA", "Current Ability", StaffEntity::getCa));
        columns.add(new StaffColumn("PA", "Potential Ability", StaffEntity::getPa));
        columns.add(new StaffColumn("SALARY_WEEKLY_RAW", "Salary Weekly", StaffEntity::getSalaryWeeklyRaw));
        columns.add(new StaffColumn("CONTRACT_END_DATE", "Contract End Date", StaffEntity::getContractEndDate));
        columns.add(new StaffColumn("WORLD_REPUTATION", "World Reputation", StaffEntity::getWorldReputation));
        StaffFilterCriteria activeFilter = staffFilter;
        setStaffGrid(columns, activeFilter);
        setFilterActive(!staffFilter.isEmpty());
        if (!staffFilter.isEmpty()) {
            status.setText("Filtered staff " + staff.countStaff(activeFilter)
                    + " | Total staff " + staff.countStaff());
        }
    }

    private void showClubs() {
        List<GridColumn> columns = List.of(
                new GridColumn("NAME", "Name"),
                new GridColumn("COMPETITION", "Competition"),
                new GridColumn("NATION", "Nation"),
                new GridColumn("REPUTATION", "Reputation"),
                new GridColumn("TRAINING_FACILITIES", "Training Facilities"),
                new GridColumn("YOUTH_FACILITIES", "Youth Facilities"),
                new GridColumn("YOUTH_COACHING", "Youth Coaching"),
                new GridColumn("YOUTH_RECRUITMENT", "Youth Recruitment"),
                new GridColumn("CORPORATE_FACILITIES", "Corporate Facilities"),
                new GridColumn("BALANCE", "Balance"),
                new GridColumn("TRANSFER_BUDGET", "Transfer Budget"),
                new GridColumn("PAYROLL_BUDGET", "Payroll Budget"));
        ClubFilterCriteria activeFilter = clubFilter;
        setClubGrid(columns, activeFilter);
        setFilterActive(!clubFilter.isEmpty());
        if (!clubFilter.isEmpty()) {
            status.setText("Filtered clubs " + clubs.countClubs(activeFilter)
                    + " | Total clubs " + clubs.countClubs());
        }
    }

    private void showCompetitions() {
        List<GridColumn> columns = List.of(
                new GridColumn("NAME", "Name"),
                new GridColumn("NATION", "Nation"),
                new GridColumn("REPUTATION", "Reputation"),
                new GridColumn("GENDER", "Gender"));
        CompetitionFilterCriteria activeFilter = competitionFilter;
        setCompetitionGrid(columns, activeFilter);
        setFilterActive(!competitionFilter.isEmpty());
        if (!competitionFilter.isEmpty()) {
            status.setText("Filtered competitions " + competitions.countCompetitions(activeFilter)
                    + " | Total competitions " + competitions.countCompetitions());
        }
    }

    private void setClubGrid(List<GridColumn> columns, ClubFilterCriteria filter) {
        clubsGrid.removeAllColumns();
        for (GridColumn column : columns) {
            clubsGrid.addColumn(club -> displayColumn(column.key(), clubColumnValue(club, column.key())))
                    .setKey(column.key())
                    .setHeader(column.header())
                    .setAutoWidth(true)
                    .setResizable(true)
                    .setSortProperty(column.key())
                    .setSortable(true);
        }
        clubsGrid.setDataProvider(DataProvider.fromCallbacks(
                query -> clubs.findClubPage(filter, pageable(query.getPage(), query.getPageSize(),
                        query.getSortOrders(), MainView::catalogSortProperty)).stream(),
                query -> boundedCount(clubs.countClubs(filter))));
        content.removeAll();
        content.setSizeFull();
        content.add(clubsGrid);
        content.addClassName("data-workspace");
    }

    private void setStaffGrid(List<StaffColumn> columns, StaffFilterCriteria filter) {
        staffGrid.removeAllColumns();
        for (StaffColumn column : columns) {
            Grid.Column<StaffEntity> gridColumn = staffGrid.addColumn(
                            value -> displayColumn(column.key(), column.value(value)))
                    .setKey(column.key()).setHeader(column.header()).setAutoWidth(true).setResizable(true);
            String sortProperty = staffSortProperty(column.key());
            if (sortProperty != null) {
                gridColumn.setSortProperty(column.key()).setSortable(true);
            }
        }
        staffGrid.setDataProvider(DataProvider.fromCallbacks(
                query -> staff.findStaffPage(filter, pageable(query.getPage(), query.getPageSize(),
                        query.getSortOrders(), MainView::staffSortProperty)).stream(),
                query -> boundedCount(staff.countStaff(filter))));
        content.removeAll();
        content.setSizeFull();
        content.add(staffGrid);
        content.addClassName("data-workspace");
    }

    private void setCompetitionGrid(List<GridColumn> columns, CompetitionFilterCriteria filter) {
        competitionsGrid.removeAllColumns();
        for (GridColumn column : columns) {
            competitionsGrid.addColumn(competition -> displayColumn(column.key(), competitionColumnValue(competition, column.key())))
                    .setKey(column.key())
                    .setHeader(column.header())
                    .setAutoWidth(true)
                    .setResizable(true)
                    .setSortProperty(column.key())
                    .setSortable(true);
        }
        competitionsGrid.setDataProvider(DataProvider.fromCallbacks(
                query -> competitions.findCompetitionPage(filter, pageable(query.getPage(), query.getPageSize(),
                        query.getSortOrders(), MainView::catalogSortProperty)).stream(),
                query -> boundedCount(competitions.countCompetitions(filter))));
        content.removeAll();
        content.setSizeFull();
        content.add(competitionsGrid);
        content.addClassName("data-workspace");
    }

    private void setPlayerGrid(List<PlayerColumn> columns, PlayerFilterCriteria filter) {
        playersGrid.removeAllColumns();
        playersGrid.setPartNameGenerator(this::playerRowPartName);
        for (PlayerColumn column : columns) {
            Grid.Column<PlayerEntity> gridColumn = playersGrid.addColumn(
                            player -> displayColumn(column.key(), column.value(player)))
                    .setKey(column.key())
                    .setHeader(column.header())
                    .setAutoWidth(true)
                    .setResizable(true);
            String sortProperty = playerSortProperty(column.key());
            if (sortProperty != null) {
                gridColumn.setSortProperty(column.key()).setSortable(true);
            }
        }
        playersGrid.setDataProvider(DataProvider.fromCallbacks(
                query -> players.findPlayerPage(filter, pageable(query.getPage(), query.getPageSize(),
                        query.getSortOrders(), MainView::playerSortProperty)).stream(),
                query -> boundedCount(players.countPlayers(filter))));
        content.removeAll();
        content.setSizeFull();
        content.add(playersGrid);
        content.addClassName("data-workspace");
    }

    private static Pageable pageable(
            int page,
            int pageSize,
            List<QuerySortOrder> orders,
            Function<String, String> propertyMapper) {
        List<Sort.Order> mapped = orders.stream()
                .map(order -> {
                    String property = propertyMapper.apply(order.getSorted());
                    if (property == null) {
                        return null;
                    }
                    return new Sort.Order(
                            order.getDirection() == SortDirection.ASCENDING
                                    ? Sort.Direction.ASC : Sort.Direction.DESC,
                            property);
                })
                .filter(Objects::nonNull)
                .toList();
        Sort sort = mapped.isEmpty() ? Sort.by("name").ascending() : Sort.by(mapped);
        return PageRequest.of(Math.max(0, page), Math.max(1, pageSize), sort);
    }

    private static String playerSortProperty(String key) {
        return switch (key) {
            case "POSITION" -> null;
            default -> PlayerColumnNames.toEntityFieldName(key.toLowerCase(Locale.ROOT));
        };
    }

    private static String staffSortProperty(String key) {
        return switch (key) {
            case "BEST_COACHING_ROLE", "BEST_COACHING_STARS" -> null;
            default -> PlayerColumnNames.toEntityFieldName(key.toLowerCase(Locale.ROOT));
        };
    }

    private static String catalogSortProperty(String key) {
        return PlayerColumnNames.toEntityFieldName(key.toLowerCase(Locale.ROOT));
    }

    private static int boundedCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(Math.max(0, count));
    }

    private String playerRowPartName(PlayerEntity player) {
        String filterClub = playerFilter.club();
        if (filterClub == null || filterClub.isBlank()) {
            return null;
        }
        boolean contractedToFilter = sameText(player.getClub(), filterClub);
        boolean playingAtFilter = sameText(player.getPlayingClub(), filterClub);
        if (contractedToFilter && !playingAtFilter) {
            return "contract-club-loaned-out";
        }
        if (playingAtFilter && !contractedToFilter) {
            return "playing-club-loaned-in";
        }
        return null;
    }

    private void configureLoadingDialog() {
        spinner.setIndeterminate(true);
        spinner.addClassName("loading-progress");
        loadingDialog.setModality(ModalityMode.STRICT);
        loadingDialog.setCloseOnEsc(false);
        loadingDialog.setCloseOnOutsideClick(false);
        loadingDialog.setDraggable(false);
        loadingDialog.setResizable(false);

        VerticalLayout content = new VerticalLayout(
                new Div(VaadinIcon.DATABASE.create()),
                spinner,
                new Span("Reading Football Manager memory"),
                new Span("Players, staff, clubs and competitions will refresh automatically.")
        );
        content.setAlignItems(FlexComponent.Alignment.CENTER);
        content.setPadding(true);
        content.addClassName("loading-content");

        loadingDialog.add(content);
        loadingDialog.addClassName("loading-dialog");
    }

    private void openPlayerDetailsDialog(PlayerEntity player) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(display(player.getName()));
        dialog.setWidth("1100px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("player-detail-dialog");

        VerticalLayout info = new VerticalLayout(detailLayout(List.of(
                new DetailField("Name", player.getName()),
                new DetailField("Age", player.getAge()),
                new DetailField("Height", heightDisplay(player)),
                new DetailField("Nationality", player.getNationality()),
                new DetailField("Club", player.getClub()),
                new DetailField("Playing Club", player.getPlayingClub()),
                new DetailField("Position", PositionTextFormatter.format(player)),
                new DetailField("Salary Weekly", salaryWeeklyDisplay(player.getSalaryWeeklyRaw())),
                new DetailField("Asking Price", moneyDisplay(player.getAskingPrice())),
                new DetailField("Joined Club Date", player.getJoinedClubDate()),
                new DetailField("Contract End Date", player.getContractEndDate()),
                new DetailField("Current Reputation", player.getCurrentReputation()),
                new DetailField("Home Reputation", player.getHomeReputation()),
                new DetailField("World Reputation", player.getWorldReputation()))));
        info.setPadding(false);
        info.addClassName("detail-info");

        Checkbox showGoalkeeping = new Checkbox("Show goalkeeping attributes");
        showGoalkeeping.setValue(isGoalkeeper(player));
        ComboBox<String> inPossessionRole = roleComboBox("In possession role", PlayerRoleAttributeCatalog.IN_POSSESSION);
        ComboBox<String> outOfPossessionRole = roleComboBox("Out of possession role", PlayerRoleAttributeCatalog.OUT_OF_POSSESSION);
        Div attributes = new Div();
        attributes.setWidthFull();
        attributes.addClassName("attribute-panel");
        renderAttributeColumns(attributes, player, showGoalkeeping.getValue(), Map.of());
        showGoalkeeping.addValueChangeListener(event -> renderAttributeColumns(
                attributes,
                player,
                event.getValue(),
                selectedRolePriorities(inPossessionRole, outOfPossessionRole)));
        inPossessionRole.addValueChangeListener(event -> {
            if (event.getValue() != null && !event.getValue().isBlank()) {
                outOfPossessionRole.clear();
            }
            renderAttributeColumns(attributes, player, showGoalkeeping.getValue(), selectedRolePriorities(inPossessionRole, outOfPossessionRole));
        });
        outOfPossessionRole.addValueChangeListener(event -> {
            if (event.getValue() != null && !event.getValue().isBlank()) {
                inPossessionRole.clear();
            }
            renderAttributeColumns(attributes, player, showGoalkeeping.getValue(), selectedRolePriorities(inPossessionRole, outOfPossessionRole));
        });
        HorizontalLayout attributeToolbar = new HorizontalLayout(showGoalkeeping, inPossessionRole, outOfPossessionRole);
        attributeToolbar.setWidthFull();
        attributeToolbar.setAlignItems(Alignment.END);
        attributeToolbar.expand(showGoalkeeping);
        attributeToolbar.addClassName("attribute-toolbar");
        VerticalLayout attributesView = new VerticalLayout(attributeToolbar, attributes);
        attributesView.setPadding(false);
        attributesView.setSpacing(true);
        attributesView.addClassName("attributes-view");

        Div positions = positionField(player);

        Tab infoTab = new Tab("Info");
        Tab attributesTab = new Tab("Attributes");
        Tab positionsTab = new Tab("Positions");
        Tabs detailTabs = new Tabs(infoTab, attributesTab, positionsTab);
        detailTabs.addClassName("dialog-tabs");
        Div detailContent = new Div(info);
        detailContent.setWidthFull();
        detailContent.addClassName("dialog-content");
        detailTabs.addSelectedChangeListener(event -> {
            detailContent.removeAll();
            if (event.getSelectedTab() == infoTab) {
                detailContent.add(info);
            } else if (event.getSelectedTab() == attributesTab) {
                detailContent.add(attributesView);
            } else {
                detailContent.add(positions);
            }
        });

        Button close = new Button("Close", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(detailTabs, detailContent);
        dialog.getFooter().add(close);
        dialog.open();
    }

    private void openStaffDetailsDialog(StaffEntity staffMember) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(display(staffMember.getName()));
        dialog.setWidth("1000px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");

        VerticalLayout info = new VerticalLayout(detailLayout(List.of(
                new DetailField("Staff Unique ID", staffMember.getUniqueId()),
                new DetailField("Job", staffMember.getJob()),
                new DetailField("Club", staffMember.getClub()),
                new DetailField("Division", staffMember.getDivision()),
                new DetailField("Nationality", staffMember.getNationality()),
                new DetailField("Gender", staffMember.getGender()),
                new DetailField("Age", staffMember.getAge()),
                new DetailField("Date of Birth", staffMember.getDateOfBirth()),
                new DetailField("Salary Weekly", salaryWeeklyDisplay(staffMember.getSalaryWeeklyRaw())),
                new DetailField("Contract End Date", staffMember.getContractEndDate()),
                new DetailField("Current Ability", staffMember.getCa()),
                new DetailField("Potential Ability", staffMember.getPa()),
                new DetailField("Current Reputation", staffMember.getCurrentReputation()),
                new DetailField("Home Reputation", staffMember.getHomeReputation()),
                new DetailField("World Reputation", staffMember.getWorldReputation()))));
        info.setPadding(false);

        List<DetailField> attributeDetails = StaffAttributeDefinitions.ALL.stream()
                .map(attribute -> {
                    Object value = staffMember.value(attribute.key());
                    String display = value instanceof Number number
                            ? number.intValue() + " - " + StaffRoleRatingCalculator.quality(number.doubleValue())
                            : "-";
                    return new DetailField(attribute.label(), display);
                })
                .toList();
        VerticalLayout attributes = new VerticalLayout(detailLayout(attributeDetails));
        attributes.setPadding(false);

        Grid<StaffRoleRatingCalculator.RoleRating> roleGrid = new Grid<>();
        roleGrid.addColumn(StaffRoleRatingCalculator.RoleRating::label).setHeader("Training assignment")
                .setWidth("180px").setFlexGrow(0);
        roleGrid.addColumn(value -> starsDisplay(value.stars())).setHeader("Stars")
                .setWidth("75px").setFlexGrow(0);
        roleGrid.addColumn(StaffRoleRatingCalculator.RoleRating::score20).setHeader("Score / 20")
                .setWidth("90px").setFlexGrow(0);
        roleGrid.addColumn(StaffRoleRatingCalculator.RoleRating::quality).setHeader("Quality")
                .setWidth("125px").setFlexGrow(0);
        roleGrid.addComponentColumn(MainView::roleAttributesComponent).setHeader("Role-specific attributes")
                .setWidth("480px").setFlexGrow(1);
        roleGrid.setItems(StaffRoleRatingCalculator.ratings(staffMember));
        roleGrid.setHeight("min(520px, 55vh)");
        roleGrid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        roleGrid.setWidthFull();

        Tab infoTab = new Tab("Info");
        Tab attributesTab = new Tab("Attributes");
        Tab rolesTab = new Tab("Coaching roles");
        Tabs detailTabs = new Tabs(infoTab, attributesTab, rolesTab);
        Div detailContent = new Div(info);
        detailContent.setWidthFull();
        detailTabs.addSelectedChangeListener(event -> {
            detailContent.removeAll();
            detailContent.add(event.getSelectedTab() == attributesTab ? attributes
                    : event.getSelectedTab() == rolesTab ? roleGrid : info);
        });
        Button close = new Button("Close", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(detailTabs, detailContent);
        dialog.getFooter().add(close);
        dialog.open();
    }

    private void openFilterDialog() {
        if (tabs.getSelectedTab() == staffTab) {
            openStaffFilterDialog();
        } else if (tabs.getSelectedTab() == clubsTab) {
            openClubFilterDialog();
        } else if (tabs.getSelectedTab() == competitionsTab) {
            openCompetitionFilterDialog();
        } else {
            openPlayerFilterDialog();
        }
    }

    private void openSettingsDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Settings");
        dialog.setWidth("420px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("settings-dialog");

        Select<MoneyCurrency> currencySelect = new Select<>();
        currencySelect.setLabel("Currency");
        currencySelect.setItems(MoneyCurrency.POUND, MoneyCurrency.DOLLAR, MoneyCurrency.EURO);
        currencySelect.setItemLabelGenerator(MoneyCurrency::label);
        currencySelect.setValue(currency);

        Span file = new Span("Stored in " + settings.settingsPath());
        file.getStyle()
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("overflow-wrap", "anywhere");

        VerticalLayout layout = new VerticalLayout(currencySelect, file);
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.addClassName("settings-content");
        dialog.add(layout);

        Button save = new Button("Save", VaadinIcon.CHECK.create(), event -> {
            currency = currencySelect.getValue() == null ? MoneyCurrency.POUND : currencySelect.getValue();
            settings.saveCurrency(currency);
            refreshSelectedTab();
            Notification.show("Settings saved", 2500, Notification.Position.TOP_CENTER);
            dialog.close();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancel = new Button("Cancel", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    private void openPlayerFilterDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Player filter");
        dialog.setWidth("1280px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("filter-dialog");
        dialog.getElement().getThemeList().add("wide-dialog");

        TextField name = new TextField("Name contains");
        name.setValue(nullSafeValue(playerFilter.name()));
        Select<String> gender = new Select<>();
        gender.setLabel("Gender");
        gender.setItems("", "male", "female");
        gender.setItemLabelGenerator(value -> value == null || value.isBlank() ? "Any" : value);
        gender.setValue(nullSafeValue(playerFilter.gender()));
        ComboBox<String> playingNation = comboBox("Playing nation", players.findPlayingNations(), playerFilter.playingNation());
        ComboBox<String> playingCompetition = comboBox("Playing competition", players.findPlayingCompetitions(), playerFilter.playingCompetition());
        ComboBox<String> club = comboBox("Club", players.findClubs(), playerFilter.club());
        ComboBox<String> nationality = comboBox("Nationality", competitions.findNations(), playerFilter.nationality());
        nationality.setValue(nullSafeValue(playerFilter.nationality()));

        IntegerField ageMin = intField("Age min", playerFilter.ageMin(), 1, 80);
        IntegerField ageMax = intField("Age max", playerFilter.ageMax(), 1, 80);
        IntegerField heightMin = intField("Height min (cm)", playerFilter.heightMin(), 100, 230);
        IntegerField heightMax = intField("Height max (cm)", playerFilter.heightMax(), 100, 230);
        IntegerField currentRepMin = intField("Current rep min", defaultInt(playerFilter.currentReputationMin(), 1), 1, 10000);
        IntegerField currentRepMax = intField("Current rep max", playerFilter.currentReputationMax(), 1, 10000);
        IntegerField homeRepMin = intField("Home rep min", defaultInt(playerFilter.homeReputationMin(), 1), 1, 10000);
        IntegerField homeRepMax = intField("Home rep max", playerFilter.homeReputationMax(), 1, 10000);
        IntegerField worldRepMin = intField("World rep min", defaultInt(playerFilter.worldReputationMin(), 1), 1, 10000);
        IntegerField worldRepMax = intField("World rep max", playerFilter.worldReputationMax(), 1, 10000);
        IntegerField caMin = intField("CA min", defaultInt(playerFilter.caMin(), 1), 1, 200);
        IntegerField caMax = intField("CA max", playerFilter.caMax(), 1, 200);
        IntegerField paMin = intField("PA min", defaultInt(playerFilter.paMin(), 1), 1, 200);
        IntegerField paMax = intField("PA max", playerFilter.paMax(), 1, 200);
        LongField askingMin = new LongField("Asking price min", playerFilter.askingPriceMin());
        LongField askingMax = new LongField("Asking price max", playerFilter.askingPriceMax());
        LongField salaryMax = new LongField("Weekly Salary max", playerFilter.salaryMax());
        DatePicker contractFrom = new DatePicker("Contract end from");
        contractFrom.setValue(playerFilter.contractEndDateFrom());
        DatePicker contractTo = new DatePicker("Contract end to");
        contractTo.setValue(playerFilter.contractEndDateTo());

        FormLayout basicFilters = new FormLayout(
                name, gender,
                playingNation, playingCompetition,
                ageMin, ageMax,
                heightMin, heightMax,
                club, salaryMax.field(),
                askingMin.field(), askingMax.field(),
                contractFrom, contractTo,
                caMin, caMax,
                paMin, paMax,
                currentRepMin, currentRepMax,
                homeRepMin, homeRepMax,
                worldRepMin, worldRepMax,
                nationality
                );
        basicFilters.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("720px", 2));
        basicFilters.addClassName("filter-form");

        Map<String, PositionLevel> selectedPositions = new LinkedHashMap<>();
        for (FieldDef field : AttributeDefinitions.POSITION_FIELDS) {
            String column = PlayerColumnNames.toColumnName(field.name()).toUpperCase();
            PositionLevel initial = PositionLevel.fromMinimum(playerFilter.positionMinimums().get(column));
            selectedPositions.put(column, initial);
        }

        VerticalLayout playerTab = new VerticalLayout(basicFilters);
        playerTab.setPadding(false);
        playerTab.setSpacing(true);
        playerTab.addClassName("filter-tab-content");

        Div positionFilterLayout = positionFilterField(selectedPositions);

        Map<String, IntegerField> attributeFields = new LinkedHashMap<>();
        Div attributeLayout = new Div();
        attributeLayout.setWidthFull();
        attributeLayout.addClassName("attribute-filter-grid");
        attributeLayout.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(6, minmax(180px, 1fr))")
                .set("gap", "16px")
                .set("align-items", "start");

        Tab filtersTab = new Tab("Filters");
        Tab positionsTab = new Tab("Positions");
        Tab attributesTab = new Tab("Attributes");
        Tabs dialogTabs = new Tabs(filtersTab, positionsTab, attributesTab);
        dialogTabs.addClassName("dialog-tabs");
        Div dialogContent = new Div(playerTab);
        dialogContent.setWidthFull();
        dialogContent.addClassName("dialog-content");
        dialogContent.addClassName("filter-dialog-content");
        dialogTabs.addSelectedChangeListener(event -> {
            dialogContent.removeAll();
            if (event.getSelectedTab() == filtersTab) {
                dialogContent.add(playerTab);
            } else if (event.getSelectedTab() == positionsTab) {
                dialogContent.add(positionFilterLayout);
            } else {
                createAttributeFields(attributeFields, attributeLayout);
                dialogContent.add(attributeLayout);
            }
        });

        Button apply = new Button("Apply filters", VaadinIcon.CHECK.create(), event -> {
            createAttributeFields(attributeFields, attributeLayout);
            if (!validPlayerFilter(
                    currentRepMin, currentRepMax,
                    homeRepMin, homeRepMax,
                    worldRepMin, worldRepMax,
                    caMin, caMax,
                    paMin, paMax,
                    heightMin, heightMax,
                    attributeFields)) {
                return;
            }
            playerFilter = new PlayerFilterCriteria(
                    name.getValue(),
                    gender.getValue(),
                    playingNation.getValue(),
                    playingCompetition.getValue(),
                    club.getValue(),
                    ageMin.getValue(), ageMax.getValue(),
                    heightMin.getValue(), heightMax.getValue(),
                    nationality.getValue(),
                    defaultInt(currentRepMin.getValue(), 1), currentRepMax.getValue(),
                    defaultInt(homeRepMin.getValue(), 1), homeRepMax.getValue(),
                    defaultInt(worldRepMin.getValue(), 1), worldRepMax.getValue(),
                    defaultInt(caMin.getValue(), 1), caMax.getValue(),
                    defaultInt(paMin.getValue(), 1), paMax.getValue(),
                    contractFrom.getValue(), contractTo.getValue(),
                    askingMin.value(), askingMax.value(),
                    salaryMax.value(),
                    selectedPositionMinimums(selectedPositions),
                    selectedAttributeMinimums(attributeFields));
            showPlayers();
            dialog.close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button clear = new Button("Clear", VaadinIcon.TRASH.create(), event -> {
            playerFilter = PlayerFilterCriteria.empty();
            showPlayers();
            updateStatus(null);
            dialog.close();
        });
        Button cancel = new Button("Cancel", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());

        dialog.add(dialogTabs, dialogContent);
        dialog.getFooter().add(clear, cancel, apply);
        dialog.open();
    }

    private void openStaffFilterDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Staff filter");
        dialog.setWidth("1200px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("filter-dialog");

        TextField name = new TextField("Name contains");
        name.setValue(nullSafeValue(staffFilter.name()));
        Select<String> gender = new Select<>();
        gender.setLabel("Gender");
        gender.setItems("", "male", "female");
        gender.setItemLabelGenerator(value -> value == null || value.isBlank() ? "Any" : value);
        gender.setValue(nullSafeValue(staffFilter.gender()));
        List<StaffEntity> staffRows = staff.findAllStaff();
        ComboBox<String> nationality = comboBox("Nationality", distinctStaffValues(staffRows, "nationality"), staffFilter.nationality());
        ComboBox<String> club = comboBox("Club", distinctStaffValues(staffRows, "club"), staffFilter.club());
        ComboBox<String> division = comboBox("Division", distinctStaffValues(staffRows, "division"), staffFilter.division());
        ComboBox<String> job = comboBox("Job", distinctStaffValues(staffRows, "job"), staffFilter.job());
        IntegerField ageMin = intField("Age min", staffFilter.ageMin(), 16, 100);
        IntegerField ageMax = intField("Age max", staffFilter.ageMax(), 16, 100);
        IntegerField caMin = intField("CA min", staffFilter.caMin(), 1, 200);
        IntegerField caMax = intField("CA max", staffFilter.caMax(), 1, 200);
        IntegerField paMin = intField("PA min", staffFilter.paMin(), 1, 200);
        IntegerField paMax = intField("PA max", staffFilter.paMax(), 1, 200);
        IntegerField currentRepMin = intField("Current reputation min", staffFilter.currentReputationMin(), 1, 10000);
        IntegerField worldRepMin = intField("World reputation min", staffFilter.worldReputationMin(), 1, 10000);
        LongField salaryMax = new LongField("Weekly salary max", staffFilter.salaryWeeklyMax());
        DatePicker contractFrom = new DatePicker("Contract end from");
        contractFrom.setValue(staffFilter.contractEndDateFrom());
        DatePicker contractTo = new DatePicker("Contract end to");
        contractTo.setValue(staffFilter.contractEndDateTo());

        FormLayout basic = new FormLayout(name, gender, nationality, club, division, job,
                ageMin, ageMax, caMin, caMax, paMin, paMax, currentRepMin, worldRepMin,
                salaryMax.field(), contractFrom, contractTo);
        basic.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("720px", 2));

        Map<String, IntegerField> attributeFields = new LinkedHashMap<>();
        Div attributeLayout = new Div();
        attributeLayout.setWidthFull();
        attributeLayout.getStyle().set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fit, minmax(220px, 1fr))").set("gap", "16px");
        for (StaffAttributeDefinitions.StaffAttribute attribute : StaffAttributeDefinitions.ALL) {
            IntegerField field = intField(attribute.label() + " min", staffFilter.attributeMinimums().get(attribute.key()), 1, 20);
            field.setWidthFull();
            attributeFields.put(attribute.key(), field);
            attributeLayout.add(field);
        }

        Map<String, Select<String>> coachingRoleFields = new LinkedHashMap<>();
        Div coachingRoleLayout = new Div();
        coachingRoleLayout.setWidthFull();
        coachingRoleLayout.getStyle().set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fit, minmax(240px, 1fr))").set("gap", "16px");
        List<String> starChoices = List.of("", "0.5", "1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0");
        for (StaffRoleRatingCalculator.RoleDefinition role : StaffRoleRatingCalculator.ROLES) {
            Select<String> field = new Select<>();
            field.setLabel(role.label());
            field.setItems(starChoices);
            field.setItemLabelGenerator(value -> value == null || value.isBlank() ? "Any rating"
                    : value + " stars or better");
            Double selected = staffFilter.coachingRoleMinimumStars().get(role.key());
            field.setValue(selected == null ? "" : String.format(Locale.ROOT, "%.1f", selected));
            coachingRoleFields.put(role.key(), field);
            coachingRoleLayout.add(field);
        }
        Span coachingRoleExplanation = new Span(
                "Set a minimum for one or more assignments. A coach must meet every selected minimum.");
        coachingRoleExplanation.addClassName("filter-help-text");
        VerticalLayout coachingRoles = new VerticalLayout(coachingRoleExplanation, coachingRoleLayout);
        coachingRoles.setPadding(false);
        coachingRoles.setSpacing(true);

        Tab filtersTab = new Tab("Filters");
        Tab attributesTab = new Tab("Attributes");
        Tab coachingRolesTab = new Tab("Coaching roles");
        Tabs dialogTabs = new Tabs(filtersTab, attributesTab, coachingRolesTab);
        dialogTabs.addClassName("dialog-tabs");
        Div dialogContent = new Div(basic);
        dialogContent.setWidthFull();
        dialogContent.addClassName("dialog-content");
        dialogContent.addClassName("filter-dialog-content");
        dialogTabs.addSelectedChangeListener(event -> {
            dialogContent.removeAll();
            dialogContent.add(event.getSelectedTab() == attributesTab ? attributeLayout
                    : event.getSelectedTab() == coachingRolesTab ? coachingRoles : basic);
        });

        Button apply = new Button("Apply filters", VaadinIcon.CHECK.create(), event -> {
            if (!validRange("Age", ageMin.getValue(), ageMax.getValue())
                    || !validRange("CA", caMin.getValue(), caMax.getValue())
                    || !validRange("PA", paMin.getValue(), paMax.getValue())
                    || contractFrom.getValue() != null && contractTo.getValue() != null
                    && contractFrom.getValue().isAfter(contractTo.getValue())) {
                if (contractFrom.getValue() != null && contractTo.getValue() != null
                        && contractFrom.getValue().isAfter(contractTo.getValue())) {
                    Notification.show("Contract end from must be on or before contract end to", 5000, Notification.Position.TOP_CENTER);
                }
                return;
            }
            Map<String, Integer> minimums = new LinkedHashMap<>();
            attributeFields.forEach((key, field) -> { if (field.getValue() != null) minimums.put(key, field.getValue()); });
            Map<String, Double> roleMinimums = new LinkedHashMap<>();
            coachingRoleFields.forEach((key, field) -> {
                if (field.getValue() != null && !field.getValue().isBlank()) {
                    roleMinimums.put(key, Double.parseDouble(field.getValue()));
                }
            });
            staffFilter = new StaffFilterCriteria(name.getValue(), gender.getValue(), nationality.getValue(),
                    club.getValue(), division.getValue(), job.getValue(), ageMin.getValue(), ageMax.getValue(),
                    caMin.getValue(), caMax.getValue(), paMin.getValue(), paMax.getValue(), currentRepMin.getValue(),
                    worldRepMin.getValue(), salaryMax.value(), contractFrom.getValue(), contractTo.getValue(), minimums,
                    "", null, roleMinimums);
            showStaff();
            dialog.close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button clear = new Button("Clear", VaadinIcon.TRASH.create(), event -> {
            staffFilter = StaffFilterCriteria.empty();
            showStaff();
            updateStatus(null);
            dialog.close();
        });
        Button cancel = new Button("Cancel", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());
        dialog.add(dialogTabs, dialogContent);
        dialog.getFooter().add(clear, cancel, apply);
        dialog.open();
    }

    private void openClubFilterDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Club filter");
        dialog.setWidth("1280px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("filter-dialog");

        List<ClubEntity> clubRows = clubs.findAllClubs();
        ComboBox<String> name = comboBox("Name", distinctColumnValues(clubRows, "NAME"), clubFilter.name());
        ComboBox<String> competition = comboBox("Competition", distinctColumnValues(clubRows, "COMPETITION"), clubFilter.competition());
        ComboBox<String> nation = comboBox("Nation", distinctColumnValues(clubRows, "NATION"), clubFilter.nation());

        IntegerField reputationMin = intField("Reputation min", clubFilter.reputationMin(), 1, 10000);
        IntegerField reputationMax = intField("Reputation max", clubFilter.reputationMax(), 1, 10000);
        LongField balanceMin = new LongField("Balance min", clubFilter.balanceMin());
        LongField balanceMax = new LongField("Balance max", clubFilter.balanceMax());
        LongField transferMin = new LongField("Transfer budget min", clubFilter.transferBudgetMin());
        LongField transferMax = new LongField("Transfer budget max", clubFilter.transferBudgetMax());
        LongField payrollMin = new LongField("Payroll budget min", clubFilter.payrollBudgetMin());
        LongField payrollMax = new LongField("Payroll budget max", clubFilter.payrollBudgetMax());

        FormLayout filters = new FormLayout(
                name, competition,
                nation,
                reputationMin, reputationMax,
                balanceMin.field(), balanceMax.field(),
                transferMin.field(), transferMax.field(),
                payrollMin.field(), payrollMax.field());
        filters.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("720px", 2));
        filters.addClassName("filter-form");

        Div dialogContent = new Div(filters);
        dialogContent.setWidthFull();
        dialogContent.addClassName("dialog-content");
        dialogContent.addClassName("filter-dialog-content");

        Button apply = new Button("Apply filters", VaadinIcon.CHECK.create(), event -> {
            if (!validIntegerField(reputationMin, 1, 10000)
                    || !validIntegerField(reputationMax, 1, 10000)
                    || !validRange("Reputation", reputationMin.getValue(), reputationMax.getValue())
                    || !validRange("Balance", balanceMin.value(), balanceMax.value())
                    || !validRange("Transfer budget", transferMin.value(), transferMax.value())
                    || !validRange("Payroll budget", payrollMin.value(), payrollMax.value())) {
                return;
            }
            clubFilter = new ClubFilterCriteria(
                    name.getValue(),
                    competition.getValue(),
                    nation.getValue(),
                    reputationMin.getValue(), reputationMax.getValue(),
                    balanceMin.value(), balanceMax.value(),
                    transferMin.value(), transferMax.value(),
                    payrollMin.value(), payrollMax.value());
            showClubs();
            dialog.close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button clear = new Button("Clear", VaadinIcon.TRASH.create(), event -> {
            clubFilter = ClubFilterCriteria.empty();
            showClubs();
            updateStatus(null);
            dialog.close();
        });
        Button cancel = new Button("Cancel", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());

        dialog.add(dialogContent);
        dialog.getFooter().add(clear, cancel, apply);
        dialog.open();
    }

    private void openCompetitionFilterDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Competition filter");
        dialog.setWidth("1280px");
        dialog.setMaxWidth("calc(100vw - 32px)");
        dialog.getElement().getThemeList().add("professional-dialog");
        dialog.getElement().getThemeList().add("filter-dialog");

        ComboBox<String> name = comboBox("Name", competitions.findNames(), competitionFilter.name());
        ComboBox<String> nation = comboBox("Nation", competitions.findNations(), competitionFilter.nation());
        ComboBox<String> gender = comboBox("Gender", competitions.findGenders(), competitionFilter.gender());
        IntegerField reputationMin = intField("Reputation min", competitionFilter.reputationMin(), 1, 10000);
        IntegerField reputationMax = intField("Reputation max", competitionFilter.reputationMax(), 1, 10000);

        FormLayout filters = new FormLayout(
                name, nation,
                reputationMin, reputationMax,
                gender);
        filters.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("720px", 2));
        filters.addClassName("filter-form");

        Div dialogContent = new Div(filters);
        dialogContent.setWidthFull();
        dialogContent.addClassName("dialog-content");
        dialogContent.addClassName("filter-dialog-content");

        Button apply = new Button("Apply filters", VaadinIcon.CHECK.create(), event -> {
            if (!validIntegerField(reputationMin, 1, 10000)
                    || !validIntegerField(reputationMax, 1, 10000)
                    || !validRange("Reputation", reputationMin.getValue(), reputationMax.getValue())) {
                return;
            }
            competitionFilter = new CompetitionFilterCriteria(
                    name.getValue(),
                    nation.getValue(),
                    reputationMin.getValue(),
                    reputationMax.getValue(),
                    gender.getValue());
            showCompetitions();
            dialog.close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button clear = new Button("Clear", VaadinIcon.TRASH.create(), event -> {
            competitionFilter = CompetitionFilterCriteria.empty();
            showCompetitions();
            updateStatus(null);
            dialog.close();
        });
        Button cancel = new Button("Cancel", VaadinIcon.CLOSE_SMALL.create(), event -> dialog.close());

        dialog.add(dialogContent);
        dialog.getFooter().add(clear, cancel, apply);
        dialog.open();
    }

    private void updateStatus(DatabaseLoadAllService.LoadAllResult result) {
        if (result == null) {
            status.setText("Players " + players.countPlayers()
                    + " | Staff " + staff.countStaff()
                    + " | Clubs " + clubs.countClubs()
                    + " | Competitions " + competitions.countCompetitions());
            return;
        }
        status.setText("PID " + result.pid()
                + " | Game date " + nullSafe(result.gameDate())
                + " | Players " + result.players()
                + " | Staff " + result.staff()
                + " | Clubs " + result.clubs()
                + " | Competitions " + result.competitions());
    }

    private void setFilterActive(boolean active) {
        filterButton.setText(active ? "Filter active" : "Filter");
        filterButton.getElement().getClassList().set("has-filter", active);
    }

    private static String display(Object value) {
        return value == null ? "" : Objects.toString(value);
    }

    private String displayColumn(String column, Object value) {
        if ("SALARY_WEEKLY_RAW".equals(column)) {
            return salaryWeeklyDisplay(value);
        }
        return MONEY_COLUMNS.contains(column) ? moneyDisplay(value) : display(value);
    }

    private String salaryWeeklyDisplay(Object value) {
        Long pounds = sortableLong(value);
        if (pounds == null) {
            return "";
        }
        MoneyCurrency selected = currency == null ? MoneyCurrency.POUND : currency;
        long roundedPounds = roundDisplayedWeeklySalary(pounds);
        long converted = convertPounds(roundedPounds, selected);
        if (selected != MoneyCurrency.POUND) {
            converted = roundDisplayedWeeklySalaryCurrency(converted);
        }
        return selected.symbol() + NumberFormat.getIntegerInstance(Locale.US).format(converted);
    }

    private String moneyDisplay(Object value) {
        Long pounds = sortableLong(value);
        if (pounds == null) {
            return "";
        }
        MoneyCurrency selected = currency == null ? MoneyCurrency.POUND : currency;
        long converted = convertPounds(pounds, selected);
        if (selected != MoneyCurrency.POUND) {
            converted = roundDisplayedCurrencyAmount(converted);
        }
        return selected.symbol() + NumberFormat.getIntegerInstance(Locale.US).format(converted);
    }

    private static long convertPounds(long pounds, MoneyCurrency selected) {
        return BigDecimal.valueOf(pounds)
                .multiply(selected.rateFromPounds())
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private static String heightDisplay(PlayerEntity player) {
        Integer cm = player.getHeightCm();
        if (cm == null || cm <= 0) {
            return "";
        }
        int totalInches = (int) Math.round(cm / 2.54);
        return cm + " cm (" + (totalInches / 12) + "'" + (totalInches % 12) + "\")";
    }

    private static FormLayout detailLayout(List<DetailField> fields) {
        FormLayout layout = new FormLayout();
        layout.addClassName("detail-grid");
        layout.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("720px", 2));
        fields.stream()
                .map(field -> detailField(field.label(), field.value()))
                .forEach(layout::add);
        return layout;
    }

    private static Div detailField(String label, Object value) {
        Span labelText = new Span(label);
        labelText.addClassName("detail-label");
        Span valueText = new Span(display(value));
        valueText.addClassName("detail-value");
        Div field = new Div(labelText, valueText);
        field.addClassName("detail-field");
        return field;
    }

    private static Div positionField(PlayerEntity player) {
        Div field = new Div();
        field.addClassName("position-pitch");
        field.getStyle()
                .set("position", "relative")
                .set("display", "grid")
                .set("grid-template-rows", "repeat(6, 1fr)")
                .set("gap", "6px")
                .set("width", "min(320px, 100%)")
                .set("aspect-ratio", "2 / 3")
                .set("margin", "0 auto")
                .set("padding", "10px")
                .set("box-sizing", "border-box")
                .set("border", "2px solid #d1fae5")
                .set("border-radius", "8px")
                .set("background", "linear-gradient(90deg, #064e3b 0%, #065f46 50%, #064e3b 100%)")
                .set("overflow", "hidden");

        addFieldLine(field, "50%", "0", "100%", "2px", "#d1fae580", "translateY(-1px)");
        addFieldLine(field, "50%", "50%", "68px", "68px", "#d1fae580", "translate(-50%, -50%)");
        addPenaltyBox(field, "top");
        addPenaltyBox(field, "bottom");

        field.add(
                positionFieldRow(positionRow(null, pos(player, "ST", "Striker"), null)),
                positionFieldRow(List.of(pos(player, "AML", "AttackingMidfielderLeft"), pos(player, "AMC", "AttackingMidfielderCentral"), pos(player, "AMR", "AttackingMidfielderRight"))),
                positionFieldRow(List.of(pos(player, "ML", "MidfielderLeft"), pos(player, "MC", "MidfielderCentral"), pos(player, "MR", "MidfielderRight"))),
                positionFieldRow(List.of(pos(player, "WBL", "WingBackLeft"), pos(player, "DMC", "DefensiveMidfielder"), pos(player, "WBR", "WingBackRight"))),
                positionFieldRow(List.of(pos(player, "DL", "DefenderLeft"), pos(player, "DC", "DefenderCentral"), pos(player, "DR", "DefenderRight"))),
                positionFieldRow(positionRow(null, pos(player, "GK", "Goalkeeper"), null))
        );
        return field;
    }

    private static Div positionFilterField(Map<String, PositionLevel> selectedPositions) {
        Div field = emptyField();
        field.add(
                positionFilterRow(blank(), positionFilterTile("ST", "Striker", "STRIKER", selectedPositions), blank()),
                positionFilterRow(
                        positionFilterTile("AML", "Attacking Midfielder Left", "ATTACKING_MIDFIELDER_LEFT", selectedPositions),
                        positionFilterTile("AMC", "Attacking Midfielder Central", "ATTACKING_MIDFIELDER_CENTRAL", selectedPositions),
                        positionFilterTile("AMR", "Attacking Midfielder Right", "ATTACKING_MIDFIELDER_RIGHT", selectedPositions)),
                positionFilterRow(
                        positionFilterTile("ML", "Midfielder Left", "MIDFIELDER_LEFT", selectedPositions),
                        positionFilterTile("MC", "Midfielder Central", "MIDFIELDER_CENTRAL", selectedPositions),
                        positionFilterTile("MR", "Midfielder Right", "MIDFIELDER_RIGHT", selectedPositions)),
                positionFilterRow(
                        positionFilterTile("WBL", "Wing Back Left", "WING_BACK_LEFT", selectedPositions),
                        positionFilterTile("DMC", "Defensive Midfielder", "DEFENSIVE_MIDFIELDER", selectedPositions),
                        positionFilterTile("WBR", "Wing Back Right", "WING_BACK_RIGHT", selectedPositions)),
                positionFilterRow(
                        positionFilterTile("DL", "Defender Left", "DEFENDER_LEFT", selectedPositions),
                        positionFilterTile("DC", "Defender Central", "DEFENDER_CENTRAL", selectedPositions),
                        positionFilterTile("DR", "Defender Right", "DEFENDER_RIGHT", selectedPositions)),
                positionFilterRow(blank(), positionFilterTile("GK", "Goalkeeper", "GOALKEEPER", selectedPositions), blank())
        );
        return field;
    }

    private static Div emptyField() {
        Div field = new Div();
        field.addClassName("position-pitch");
        field.addClassName("position-filter-pitch");
        field.getStyle()
                .set("position", "relative")
                .set("display", "grid")
                .set("grid-template-rows", "repeat(6, 1fr)")
                .set("gap", "7px")
                .set("width", "min(360px, 100%)")
                .set("aspect-ratio", "2 / 3")
                .set("margin", "0 auto")
                .set("padding", "12px")
                .set("box-sizing", "border-box")
                .set("border", "2px solid #d1fae5")
                .set("border-radius", "8px")
                .set("background", "linear-gradient(90deg, #064e3b 0%, #065f46 50%, #064e3b 100%)")
                .set("overflow", "hidden");
        addFieldLine(field, "50%", "0", "100%", "2px", "#d1fae580", "translateY(-1px)");
        addFieldLine(field, "50%", "50%", "76px", "76px", "#d1fae580", "translate(-50%, -50%)");
        addPenaltyBox(field, "top");
        addPenaltyBox(field, "bottom");
        return field;
    }

    private static List<PositionTile> positionRow(PositionTile left, PositionTile center, PositionTile right) {
        List<PositionTile> row = new ArrayList<>();
        row.add(left);
        row.add(center);
        row.add(right);
        return row;
    }

    private static PositionTile pos(PlayerEntity player, String label, String fieldName) {
        String column = PlayerColumnNames.toColumnName(fieldName).toUpperCase();
        return new PositionTile(label, displayName(fieldName), player.getColumnValue(column));
    }

    private static Div positionFieldRow(List<PositionTile> positions) {
        Div row = new Div();
        row.addClassName("position-row");
        row.getStyle()
                .set("position", "relative")
                .set("z-index", "1")
                .set("display", "grid")
                .set("grid-template-columns", "repeat(3, minmax(0, 1fr))")
                .set("gap", "6px")
                .set("align-items", "center");
        positions.forEach(position -> row.add(position == null ? new Div() : positionFieldTile(position)));
        return row;
    }

    private static Div positionFilterRow(Component... positions) {
        Div row = new Div();
        row.addClassName("position-row");
        row.getStyle()
                .set("position", "relative")
                .set("z-index", "1")
                .set("display", "grid")
                .set("grid-template-columns", "repeat(3, minmax(0, 1fr))")
                .set("gap", "7px")
                .set("align-items", "center");
        row.add(positions);
        return row;
    }

    private static Div blank() {
        return new Div();
    }

    private static Button positionFilterTile(
            String shortName,
            String fullName,
            String column,
            Map<String, PositionLevel> selectedPositions) {
        PositionLevel initial = selectedPositions.getOrDefault(column, PositionLevel.CANNOT);
        Button button = new Button(filterPositionLabel(shortName, initial));
        button.addClassName("position-filter-tile");
        button.getElement().setProperty("title", fullName);
        button.setWidthFull();
        button.getStyle()
                .set("min-width", "0")
                .set("min-height", "38px")
                .set("padding", "6px 4px")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("font-weight", "800")
                .set("line-height", "1.1")
                .set("white-space", "normal");
        applyPositionColor(button, initial);
        button.addClickListener(event -> {
            PositionLevel next = selectedPositions.getOrDefault(column, PositionLevel.CANNOT).next();
            selectedPositions.put(column, next);
            button.setText(filterPositionLabel(shortName, next));
            applyPositionColor(button, next);
        });
        return button;
    }

    private static Div positionFieldTile(PositionTile position) {
        Span label = new Span(position.shortName());
        label.getStyle().set("font-weight", "800").set("line-height", "1");
        Span value = new Span(display(position.value()) + " - " + PositionTextFormatter.positionLevelText(position.value()));
        value.getStyle()
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("line-height", "1.15")
                .set("text-align", "center");
        Div tile = new Div(label, value);
        tile.addClassName("position-tile");
        PositionLevel level = PositionLevel.fromScore(position.value());
        tile.getElement().setProperty("title", position.fullName());
        tile.getStyle()
                .set("min-width", "0")
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("gap", "3px")
                .set("padding", "5px 4px")
                .set("min-height", "42px")
                .set("border-radius", "8px")
                .set("background", level.color)
                .set("color", level.textColor)
                .set("border", "1px solid rgba(255,255,255,.55)")
                .set("box-shadow", "0 1px 3px rgba(0,0,0,.22)");
        return tile;
    }

    private static void addFieldLine(Div field, String top, String left, String width, String height, String color, String transform) {
        Div line = new Div();
        line.getStyle()
                .set("position", "absolute")
                .set("top", top)
                .set("left", left)
                .set("width", width)
                .set("height", height)
                .set("border", "2px solid " + color)
                .set("border-radius", "999px")
                .set("transform", transform)
                .set("box-sizing", "border-box")
                .set("pointer-events", "none");
        field.add(line);
    }

    private static void addPenaltyBox(Div field, String side) {
        Div box = new Div();
        box.getStyle()
                .set("position", "absolute")
                .set(side, "-2px")
                .set("left", "50%")
                .set("width", "44%")
                .set("height", "15%")
                .set("border", "2px solid #d1fae580")
                .set("border-" + side, "0")
                .set("transform", "translateX(-50%)")
                .set("box-sizing", "border-box")
                .set("pointer-events", "none");
        field.add(box);
    }

    private static void renderAttributeColumns(
            Div container,
            PlayerEntity player,
            boolean showGoalkeeping,
            Map<String, String> rolePriorities) {
        container.removeAll();
        Div columns = new Div();
        columns.addClassName("attribute-columns");
        columns.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fit, minmax(170px, 1fr))")
                .set("gap", "16px")
                .set("align-items", "start");
        PlayerAttributeCatalog.categories(showGoalkeeping).forEach(category -> columns.add(attributeCategory(player, category, rolePriorities)));
        container.add(columns);
    }

    private static Div attributeCategory(
            PlayerEntity player,
            PlayerAttributeCatalog.AttributeCategory category,
            Map<String, String> rolePriorities) {
        Span title = new Span(category.name());
        title.getStyle()
                .set("font-weight", "700")
                .set("padding-bottom", "6px")
                .set("border-bottom", "1px solid var(--lumo-contrast-20pct)");
        Div column = new Div(title);
        column.addClassName("attribute-category");
        column.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "4px");
        for (PlayerAttributeCatalog.AttributeDefinition attribute : category.attributes()) {
            Object value = attributeValue(player, attribute.columnName());
            column.add(attributeRow(attribute.displayName(), value, rolePriority(rolePriorities, attribute.columnName())));
        }
        return column;
    }

    private static Div attributeRow(String label, Object value, String rolePriority) {
        Span labelText = new Span(label);
        labelText.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Span valueText = new Span(display(value));
        valueText.getStyle()
                .set("font-weight", "600")
                .set("text-align", "right")
                .set("color", scoreColor(value));
        Div row = new Div(labelText, valueText);
        row.addClassName("attribute-row");
        row.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "1fr auto")
                .set("gap", "8px")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("line-height", "1.3")
                .set("padding", "2px 4px")
                .set("border-radius", "4px");
        if ("primary".equals(rolePriority)) {
            row.addClassName("role-primary");
            row.getStyle().set("background", "#dcfce7");
        } else if ("secondary".equals(rolePriority)) {
            row.addClassName("role-secondary");
            row.getStyle().set("background", "#dbeafe");
        }
        return row;
    }

    private static Object attributeValue(PlayerEntity player, String columnName) {
        return columnName == null ? null : player.getColumnValue(columnName);
    }

    private static String rolePriority(Map<String, String> rolePriorities, String columnName) {
        return columnName == null ? null : rolePriorities.get(columnName.toLowerCase());
    }

    private static String scoreColor(Object value) {
        Long score = sortableLong(value);
        if (score == null) {
            return "var(--lumo-secondary-text-color)";
        }
        if (score <= 5) {
            return "#dc2626";
        }
        if (score <= 10) {
            return "#ea580c";
        }
        if (score <= 15) {
            return "#ca8a04";
        }
        return "#16a34a";
    }

    private static ComboBox<String> roleComboBox(String label, String phase) {
        ComboBox<String> comboBox = new ComboBox<>(label);
        comboBox.setItems(PlayerRoleAttributeCatalog.roles(phase));
        comboBox.setClearButtonVisible(true);
        comboBox.setWidth("260px");
        return comboBox;
    }

    private static Map<String, String> selectedRolePriorities(ComboBox<String> inPossessionRole, ComboBox<String> outOfPossessionRole) {
        if (inPossessionRole.getValue() != null && !inPossessionRole.getValue().isBlank()) {
            return PlayerRoleAttributeCatalog.priorities(PlayerRoleAttributeCatalog.IN_POSSESSION, inPossessionRole.getValue());
        }
        if (outOfPossessionRole.getValue() != null && !outOfPossessionRole.getValue().isBlank()) {
            return PlayerRoleAttributeCatalog.priorities(PlayerRoleAttributeCatalog.OUT_OF_POSSESSION, outOfPossessionRole.getValue());
        }
        return Map.of();
    }

    private static boolean isGoalkeeper(PlayerEntity player) {
        Integer goalkeeper = player.getGoalkeeper();
        return goalkeeper != null && goalkeeper >= 15;
    }

    private static int comparePlayerColumn(PlayerEntity left, PlayerEntity right, PlayerColumn column) {
        if (NUMERIC_SORT_COLUMNS.contains(column.key())) {
            if ("SALARY_WEEKLY_RAW".equals(column.key())) {
                return compareLongs(
                        roundedDisplayedWeeklySalary(column.value(left)),
                        roundedDisplayedWeeklySalary(column.value(right)));
            }
            return compareLongs(sortableLong(column.value(left)), sortableLong(column.value(right)));
        }
        return display(column.value(left)).compareToIgnoreCase(display(column.value(right)));
    }

    private static int compareStaffColumn(StaffEntity left, StaffEntity right, StaffColumn column) {
        Object leftValue = column.value(left);
        Object rightValue = column.value(right);
        if (leftValue instanceof Number || rightValue instanceof Number) {
            double leftNumber = leftValue instanceof Number number ? number.doubleValue() : Double.NEGATIVE_INFINITY;
            double rightNumber = rightValue instanceof Number number ? number.doubleValue() : Double.NEGATIVE_INFINITY;
            return Double.compare(leftNumber, rightNumber);
        }
        return display(leftValue).compareToIgnoreCase(display(rightValue));
    }

    private static String bestStaffRoleLabel(StaffEntity staff) {
        return StaffRoleRatingCalculator.bestRating(staff)
                .map(StaffRoleRatingCalculator.RoleRating::label).orElse("-");
    }

    private static Double bestStaffRoleStars(StaffEntity staff) {
        return StaffRoleRatingCalculator.bestRating(staff)
                .map(StaffRoleRatingCalculator.RoleRating::stars).orElse(null);
    }

    private static String starsDisplay(double stars) {
        return String.format(Locale.ROOT, "%.1f ★", stars);
    }

    private static String roleAttributesDisplay(StaffRoleRatingCalculator.RoleRating role) {
        return role.attributes().stream()
                .map(attribute -> attribute.label() + " " + attribute.value() + " (" + attribute.quality() + ", ×" + attribute.weight() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Component roleAttributesComponent(StaffRoleRatingCalculator.RoleRating role) {
        Span attributes = new Span(roleAttributesDisplay(role));
        attributes.getStyle().set("white-space", "normal").set("line-height", "1.35");
        return attributes;
    }

    private static Long roundedDisplayedWeeklySalary(Object value) {
        Long pounds = sortableLong(value);
        return pounds == null ? null : roundDisplayedWeeklySalary(pounds);
    }

    private static long roundDisplayedWeeklySalary(long pounds) {
        if (pounds <= 0) {
            return 0;
        }
        long step;
        if (pounds < 500L) {
            step = 10L;
        } else if (pounds < 1_000L) {
            step = 50L;
        } else if (pounds < 10_000L) {
            step = 100L;
        } else if (pounds < 50_000L) {
            step = 500L;
        } else {
            step = 1_000L;
        }
        return Math.round(pounds / (double) step) * step;
    }

    private static long roundDisplayedWeeklySalaryCurrency(long amount) {
        long abs = Math.abs(amount);
        long step;
        if (abs < 500L) {
            step = 50L;
        } else if (abs < 1_000L) {
            step = 25L;
        } else if (abs < 10_000L) {
            step = 100L;
        } else if (abs < 50_000L) {
            step = 1_000L;
        } else {
            step = 5_000L;
        }
        return roundToNearest(amount, step);
    }

    private static long roundDisplayedCurrencyAmount(long amount) {
        long abs = Math.abs(amount);
        long step;
        if (abs < 25_000L) {
            step = 250L;
        } else if (abs < 100_000L) {
            step = 1_000L;
        } else if (abs < 1_000_000L) {
            step = 25_000L;
        } else if (abs < 10_000_000L) {
            step = 1_000_000L;
        } else {
            step = 1_000_000L;
        }
        return roundToNearest(amount, step);
    }

    private static long roundToNearest(long value, long step) {
        if (value == 0 || step <= 0) {
            return value;
        }
        return Math.round(value / (double) step) * step;
    }

    private static int compareClubColumn(ClubEntity left, ClubEntity right, String column) {
        if (NUMERIC_SORT_COLUMNS.contains(column)) {
            return compareLongs(sortableLong(clubColumnValue(left, column)), sortableLong(clubColumnValue(right, column)));
        }
        return display(clubColumnValue(left, column)).compareToIgnoreCase(display(clubColumnValue(right, column)));
    }

    private static int compareCompetitionColumn(CompetitionEntity left, CompetitionEntity right, String column) {
        if (NUMERIC_SORT_COLUMNS.contains(column)) {
            return compareLongs(sortableLong(competitionColumnValue(left, column)), sortableLong(competitionColumnValue(right, column)));
        }
        return display(competitionColumnValue(left, column)).compareToIgnoreCase(display(competitionColumnValue(right, column)));
    }

    private static int compareLongs(Long left, Long right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return Long.compare(left, right);
    }

    private static Long sortableLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String nullSafeValue(String value) {
        return value == null ? "" : value;
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String toColumnName(String fieldName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char ch = fieldName.charAt(i);
            if (Character.isUpperCase(ch)) {
                out.append('_').append(Character.toLowerCase(ch));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static IntegerField intField(String label, Integer value, int min, int max) {
        IntegerField field = new IntegerField(label);
        field.setMin(min);
        field.setMax(max);
        field.setStep(1);
        field.setClearButtonVisible(true);
        field.setValue(value);
        return field;
    }

    private static ComboBox<String> comboBox(String label, List<String> items, String value) {
        ComboBox<String> comboBox = new ComboBox<>(label);
        comboBox.setItems(items);
        comboBox.setClearButtonVisible(true);
        comboBox.setAllowCustomValue(false);
        if (value != null && !value.isBlank()) {
            comboBox.setValue(value);
        }
        return comboBox;
    }

    private void createAttributeFields(Map<String, IntegerField> attributeFields, Div attributeLayout) {
        if (!attributeFields.isEmpty()) {
            return;
        }
        for (PlayerAttributeCatalog.AttributeCategory category : PlayerAttributeCatalog.filterCategories()) {
            VerticalLayout column = new VerticalLayout();
            column.setPadding(false);
            column.setSpacing(false);
            column.addClassName("attribute-filter-column");
            Span title = new Span(attributeFilterCategoryTitle(category.name()));
            title.getStyle()
                    .set("font-weight", "700")
                    .set("padding-bottom", "6px")
                    .set("border-bottom", "1px solid var(--lumo-contrast-20pct)")
                    .set("margin-bottom", "6px");
            column.add(title);
            for (PlayerAttributeCatalog.AttributeDefinition definition : category.attributes()) {
                String attributeColumn = definition.columnName();
                String key = category.name() + ":" + attributeColumn;
                IntegerField attribute = intField(definition.displayName(), playerFilter.attributeMinimums().getOrDefault(attributeColumn, 1), 1, 20);
                attribute.setWidthFull();
                attributeFields.put(key, attribute);
                column.add(attribute);
            }
            attributeLayout.add(column);
        }
    }

    private static boolean validPlayerFilter(
            IntegerField currentRepMin,
            IntegerField currentRepMax,
            IntegerField homeRepMin,
            IntegerField homeRepMax,
            IntegerField worldRepMin,
            IntegerField worldRepMax,
            IntegerField caMin,
            IntegerField caMax,
            IntegerField paMin,
            IntegerField paMax,
            IntegerField heightMin,
            IntegerField heightMax,
            Map<String, IntegerField> attributeFields) {
        for (IntegerField field : List.of(currentRepMin, currentRepMax, homeRepMin, homeRepMax, worldRepMin, worldRepMax)) {
            if (!validIntegerField(field, 1, 10000)) {
                return false;
            }
        }
        for (IntegerField field : List.of(caMin, caMax, paMin, paMax)) {
            if (!validIntegerField(field, 1, 200)) {
                return false;
            }
        }
        for (IntegerField field : List.of(heightMin, heightMax)) {
            if (!validIntegerField(field, 100, 230)) {
                return false;
            }
        }
        for (IntegerField field : attributeFields.values()) {
            if (!validIntegerField(field, 1, 20)) {
                return false;
            }
        }
        return validRange("Current reputation", defaultInt(currentRepMin.getValue(), 1), currentRepMax.getValue())
                && validRange("Home reputation", defaultInt(homeRepMin.getValue(), 1), homeRepMax.getValue())
                && validRange("World reputation", defaultInt(worldRepMin.getValue(), 1), worldRepMax.getValue())
                && validRange("CA", defaultInt(caMin.getValue(), 1), caMax.getValue())
                && validRange("PA", defaultInt(paMin.getValue(), 1), paMax.getValue())
                && validRange("Height", heightMin.getValue(), heightMax.getValue());
    }

    private static boolean validIntegerField(IntegerField field, int min, int max) {
        Integer value = field.getValue();
        if (value == null) {
            return true;
        }
        if (value < min || value > max) {
            Notification.show(field.getLabel() + " must be between " + min + " and " + max, 5000, Notification.Position.TOP_CENTER);
            return false;
        }
        return true;
    }

    private static boolean validRange(String label, Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            Notification.show(label + " min must be less than or equal to max", 5000, Notification.Position.TOP_CENTER);
            return false;
        }
        return true;
    }

    private static boolean validRange(String label, Long min, Long max) {
        if (min != null && max != null && min > max) {
            Notification.show(label + " min must be less than or equal to max", 5000, Notification.Position.TOP_CENTER);
            return false;
        }
        return true;
    }

    private static List<String> distinctColumnValues(List<ClubEntity> rows, String column) {
        return rows.stream()
                .map(row -> display(clubColumnValue(row, column)))
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static List<String> distinctStaffValues(List<StaffEntity> rows, String key) {
        return rows.stream().map(row -> display(row.value(key))).filter(value -> !value.isBlank())
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static Object clubColumnValue(ClubEntity club, String column) {
        return switch (column) {
            case "NAME" -> club.getName();
            case "COMPETITION" -> club.getCompetition();
            case "NATION" -> club.getNation();
            case "REPUTATION" -> club.getReputation();
            case "TRAINING_FACILITIES" -> club.getTrainingFacilities();
            case "YOUTH_FACILITIES" -> club.getYouthFacilities();
            case "YOUTH_COACHING" -> club.getYouthCoaching();
            case "YOUTH_RECRUITMENT" -> club.getYouthRecruitment();
            case "CORPORATE_FACILITIES" -> club.getCorporateFacilities();
            case "BALANCE" -> club.getBalance();
            case "TRANSFER_BUDGET" -> club.getTransferBudget();
            case "PAYROLL_BUDGET" -> club.getPayrollBudget();
            default -> null;
        };
    }

    private static Object competitionColumnValue(CompetitionEntity competition, String column) {
        return switch (column) {
            case "NAME" -> competition.getName();
            case "NATION" -> competition.getNation();
            case "REPUTATION" -> competition.getReputation();
            case "GENDER" -> competition.getGender();
            default -> null;
        };
    }

    private static Integer defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Map<String, Integer> selectedPositionMinimums(Map<String, PositionLevel> selectedPositions) {
        Map<String, Integer> out = new LinkedHashMap<>();
        selectedPositions.forEach((column, level) -> {
            if (level.minimum > 1) {
                out.put(column, level.minimum);
            }
        });
        return out;
    }

    private static Map<String, Integer> selectedAttributeMinimums(Map<String, IntegerField> attributeFields) {
        Map<String, Integer> out = new LinkedHashMap<>();
        attributeFields.forEach((key, field) -> {
            Integer value = defaultInt(field.getValue(), 1);
            if (value != null && value > 1) {
                String column = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
                out.merge(column, value, Math::max);
            }
        });
        return out;
    }

    private static String positionLabel(String fieldName, PositionLevel level) {
        return displayName(fieldName) + " - " + level.label;
    }

    private static String filterPositionLabel(String shortName, PositionLevel level) {
        return shortName + "\n" + level.label;
    }

    private static String displayName(String fieldName) {
        return toColumnName(fieldName).replace('_', ' ');
    }

    private static String attributeFilterCategoryTitle(String categoryName) {
        if (PlayerAttributeCatalog.GOALKEEPING.equals(categoryName)) {
            return "Goalkeeper";
        }
        if ("Hidden Attributes".equals(categoryName)) {
            return "Hidden";
        }
        return categoryName;
    }

    private static void applyPositionColor(Button button, PositionLevel level) {
        button.getStyle()
                .set("background", level.color)
                .set("color", level.textColor)
                .set("border", "1px solid var(--lumo-contrast-20pct)");
    }

    private record PlayerColumn(String key, String header, Function<PlayerEntity, Object> valueProvider) {
        private Object value(PlayerEntity player) {
            return valueProvider.apply(player);
        }
    }

    private record StaffColumn(String key, String header, Function<StaffEntity, Object> valueProvider) {
        private Object value(StaffEntity staff) { return valueProvider.apply(staff); }
    }

    private record GridColumn(String key, String header) {
    }

    private record DetailField(String label, Object value) {
    }

    private record PositionTile(String shortName, String fullName, Object value) {
    }

    private enum PositionLevel {
        CANNOT("Cannot play", 1, "#e5e7eb", "#111827"),
        CAN("Can play", 5, "#dc2626", "#ffffff"),
        COMPETENT("Is competent at", 9, "#f97316", "#111827"),
        ACCOMPLISHED("Is accomplished at", 15, "#fde047", "#111827"),
        NATURAL("Is natural at", 18, "#16a34a", "#ffffff");

        private final String label;
        private final int minimum;
        private final String color;
        private final String textColor;

        PositionLevel(String label, int minimum, String color, String textColor) {
            this.label = label;
            this.minimum = minimum;
            this.color = color;
            this.textColor = textColor;
        }

        private PositionLevel next() {
            PositionLevel[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private static PositionLevel fromMinimum(Integer minimum) {
            if (minimum == null || minimum <= 1) {
                return CANNOT;
            }
            for (PositionLevel level : values()) {
                if (level.minimum == minimum) {
                    return level;
                }
            }
            return CANNOT;
        }

        private static PositionLevel fromScore(Object scoreValue) {
            Long score = sortableLong(scoreValue);
            if (score == null || score <= 4) {
                return CANNOT;
            }
            if (score <= 8) {
                return CAN;
            }
            if (score <= 14) {
                return COMPETENT;
            }
            if (score <= 17) {
                return ACCOMPLISHED;
            }
            return NATURAL;
        }
    }

    private static final class LongField {
        private final com.vaadin.flow.component.textfield.NumberField field;

        private LongField(String label, Long value) {
            field = new com.vaadin.flow.component.textfield.NumberField(label);
            field.setMin(0);
            field.setStep(1000);
            field.setClearButtonVisible(true);
            field.setValue(value == null ? null : value.doubleValue());
        }

        private com.vaadin.flow.component.textfield.NumberField field() {
            return field;
        }

        private Long value() {
            return field.getValue() == null ? null : field.getValue().longValue();
        }
    }
}
