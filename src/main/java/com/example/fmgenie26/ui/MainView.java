package com.example.fmgenie26.ui;

import com.example.fmgenie26.club.ClubExporter;
import com.example.fmgenie26.competition.CompetitionExporter;
import com.example.fmgenie26.db.ClubDatabaseService;
import com.example.fmgenie26.db.CompetitionDatabaseService;
import com.example.fmgenie26.db.DatabaseLoadAllService;
import com.example.fmgenie26.db.PlayerFilterCriteria;
import com.example.fmgenie26.db.PlayerColumnNames;
import com.example.fmgenie26.db.PlayerDatabaseService;
import com.example.fmgenie26.player.AttributeDefinitions;
import com.example.fmgenie26.player.FieldDef;
import com.example.fmgenie26.player.PlayerExporter;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Route("")
@PageTitle("FM Genie 26")
public class MainView extends VerticalLayout {
    private static final Set<String> NUMERIC_SORT_COLUMNS = Set.of(
            "ID", "CLUB_ID", "PLAYING_CLUB_ID", "CURRENT_REPUTATION", "HOME_REPUTATION", "WORLD_REPUTATION",
            "CA", "PA", "ASKING_PRICE", "ASKING_PRICE_RAW", "SALARY_PA", "SALARY_WEEKLY_RAW", "AGE",
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

    private final DatabaseLoadAllService loadAll;
    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final CompetitionDatabaseService competitions;

    private final Button loadButton = new Button("Load from RAM");
    private final Button filterButton = new Button("Filter");
    private final Span status = new Span();
    private final Tabs tabs = new Tabs();
    private final Div content = new Div();
    private final Grid<Map<String, Object>> playersGrid = new Grid<>();
    private final Grid<Map<String, Object>> clubsGrid = new Grid<>();
    private final Grid<Map<String, Object>> competitionsGrid = new Grid<>();

    private final Tab playersTab = new Tab("Players");
    private final Tab clubsTab = new Tab("Clubs");
    private final Tab competitionsTab = new Tab("Competitions");
    private PlayerFilterCriteria playerFilter = PlayerFilterCriteria.empty();

    public MainView(
            DatabaseLoadAllService loadAll,
            PlayerDatabaseService players,
            ClubDatabaseService clubs,
            CompetitionDatabaseService competitions) {
        this.loadAll = loadAll;
        this.players = players;
        this.clubs = clubs;
        this.competitions = competitions;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(header(), tabs, content);
        configureTabs();
        configureGrid(playersGrid);
        configureGrid(clubsGrid);
        configureGrid(competitionsGrid);
        showPlayers();
        updateStatus(null);
    }

    private HorizontalLayout header() {
        loadButton.addClickListener(event -> loadAllData());
        filterButton.addClickListener(event -> openPlayerFilterDialog());
        status.getStyle().set("font-size", "var(--lumo-font-size-s)");
        status.getStyle().set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout header = new HorizontalLayout(loadButton, filterButton, status);
        header.setWidthFull();
        header.setAlignItems(Alignment.CENTER);
        header.setPadding(true);
        header.setSpacing(true);
        header.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        return header;
    }

    private void configureTabs() {
        tabs.add(playersTab, clubsTab, competitionsTab);
        tabs.setWidthFull();
        tabs.addSelectedChangeListener(event -> {
            filterButton.setVisible(event.getSelectedTab() == playersTab);
            if (event.getSelectedTab() == playersTab) {
                showPlayers();
            } else if (event.getSelectedTab() == clubsTab) {
                showClubs();
            } else {
                showCompetitions();
            }
        });
    }

    private void configureGrid(Grid<Map<String, Object>> grid) {
        grid.setSizeFull();
        grid.setColumnReorderingAllowed(true);
        grid.getStyle().set("border", "0");
    }

    private void loadAllData() {
        loadButton.setEnabled(false);
        loadButton.setText("Loading...");
        try {
            DatabaseLoadAllService.LoadAllResult result = loadAll.loadAll(null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null);
            updateStatus(result);
            refreshSelectedTab();
            Notification.show("Loaded RAM data", 3000, Notification.Position.TOP_CENTER);
        } catch (IOException | RuntimeException ex) {
            Notification.show("Load failed: " + ex.getMessage(), 8000, Notification.Position.TOP_CENTER);
        } finally {
            loadButton.setEnabled(true);
            loadButton.setText("Load from RAM");
        }
    }

    private void refreshSelectedTab() {
        if (tabs.getSelectedTab() == playersTab) {
            showPlayers();
        } else if (tabs.getSelectedTab() == clubsTab) {
            showClubs();
        } else {
            showCompetitions();
        }
    }

    private void showPlayers() {
        List<String> columns = new ArrayList<>(List.of("ID", "CLUB_ID", "PLAYING_CLUB_ID", "PLAYING_NATION", "PLAYING_COMPETITION"));
        PlayerExporter.FIELD_NAMES.stream()
                .map(PlayerColumnNames::toColumnName)
                .map(String::toUpperCase)
                .forEach(columns::add);
        List<Map<String, Object>> rows = playerFilter.isEmpty() ? players.findAllPlayers() : players.findPlayers(playerFilter);
        setGrid(playersGrid, columns, rows);
        if (!playerFilter.isEmpty()) {
            status.setText("Filtered players " + rows.size() + " | Total players " + players.countPlayers());
        }
    }

    private void showClubs() {
        List<String> columns = new ArrayList<>(List.of("ID", "COMPETITION_ID"));
        ClubExporter.FIELD_NAMES.stream()
                .map(MainView::toColumnName)
                .map(String::toUpperCase)
                .forEach(columns::add);
        setGrid(clubsGrid, columns, clubs.findAllClubs());
    }

    private void showCompetitions() {
        List<String> columns = new ArrayList<>(List.of("ID"));
        CompetitionExporter.FIELD_NAMES.stream()
                .map(MainView::toColumnName)
                .map(String::toUpperCase)
                .forEach(columns::add);
        setGrid(competitionsGrid, columns, competitions.findAllCompetitions());
    }

    private void setGrid(Grid<Map<String, Object>> grid, List<String> columns, List<Map<String, Object>> rows) {
        grid.removeAllColumns();
        for (String column : columns) {
            grid.addColumn(row -> display(row.get(column)))
                    .setKey(column)
                    .setHeader(column)
                    .setAutoWidth(true)
                    .setResizable(true)
                    .setComparator((left, right) -> compareColumn(left, right, column))
                    .setSortable(true);
        }
        grid.setItems(rows);
        content.removeAll();
        content.setSizeFull();
        content.add(grid);
        content.getStyle().set("height", "calc(100vh - 120px)");
    }

    private void openPlayerFilterDialog() {
        
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Player filter");
        dialog.setWidth("980px");
        dialog.setMaxWidth("calc(100vw - 32px)");

        TextField name = new TextField("Name contains");
        name.setValue(nullSafeValue(playerFilter.name()));
        Select<String> gender = new Select<>();
        gender.setLabel("Gender");
        gender.setItems("", "male", "female");
        gender.setItemLabelGenerator(value -> value == null || value.isBlank() ? "Any" : value);
        gender.setValue(nullSafeValue(playerFilter.gender()));
        ComboBox<String> playingNation = comboBox("Playing nation", players.findPlayingNations(), playerFilter.playingNation());
        ComboBox<String> playingCompetition = comboBox("Playing competition", players.findPlayingCompetitions(), playerFilter.playingCompetition());
        TextField nationality = new TextField("Nationality contains");
        nationality.setValue(nullSafeValue(playerFilter.nationality()));

        IntegerField ageMin = intField("Age min", playerFilter.ageMin(), 1, 80);
        IntegerField ageMax = intField("Age max", playerFilter.ageMax(), 1, 80);
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
        DatePicker contractFrom = new DatePicker("Contract end from");
        contractFrom.setValue(playerFilter.contractEndDateFrom());
        DatePicker contractTo = new DatePicker("Contract end to");
        contractTo.setValue(playerFilter.contractEndDateTo());

        FormLayout basicFilters = new FormLayout(
                name, gender,
                playingNation, playingCompetition,
                nationality,
                ageMin, ageMax,
                currentRepMin, currentRepMax,
                homeRepMin, homeRepMax,
                worldRepMin, worldRepMax,
                caMin, caMax,
                paMin, paMax,
                contractFrom, contractTo,
                askingMin.field(), askingMax.field());
        basicFilters.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("720px", 2));

        Map<String, PositionLevel> selectedPositions = new LinkedHashMap<>();
        Div positionGrid = new Div();
        positionGrid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(auto-fit, minmax(190px, 1fr))")
                .set("gap", "8px")
                .set("margin-top", "12px");
        for (FieldDef field : AttributeDefinitions.POSITION_FIELDS) {
            String column = PlayerColumnNames.toColumnName(field.name()).toUpperCase();
            PositionLevel initial = PositionLevel.fromMinimum(playerFilter.positionMinimums().get(column));
            selectedPositions.put(column, initial);
            Button button = new Button(positionLabel(field.name(), initial));
            button.setWidthFull();
            applyPositionColor(button, initial);
            button.addClickListener(event -> {
                PositionLevel next = selectedPositions.get(column).next();
                selectedPositions.put(column, next);
                button.setText(positionLabel(field.name(), next));
                applyPositionColor(button, next);
            });
            positionGrid.add(button);
        }

        VerticalLayout playerTab = new VerticalLayout(basicFilters, positionGrid);
        playerTab.setPadding(false);
        playerTab.setSpacing(true);

        Map<String, IntegerField> attributeFields = new LinkedHashMap<>();
        FormLayout attributeLayout = new FormLayout();
        attributeLayout.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 2),
                new FormLayout.ResponsiveStep("720px", 4));

        Tab filtersTab = new Tab("Filters");
        Tab attributesTab = new Tab("Attributes");
        Tabs dialogTabs = new Tabs(filtersTab, attributesTab);
        Div dialogContent = new Div(playerTab);
        dialogContent.setWidthFull();
        dialogContent.getStyle().set("max-height", "70vh").set("overflow", "auto");
        dialogTabs.addSelectedChangeListener(event -> {
            dialogContent.removeAll();
            if (event.getSelectedTab() == filtersTab) {
                dialogContent.add(playerTab);
            } else {
                createAttributeFields(attributeFields, attributeLayout);
                dialogContent.add(attributeLayout);
            }
        });

        Button apply = new Button("Apply", event -> {
            createAttributeFields(attributeFields, attributeLayout);
            if (!validPlayerFilter(
                    currentRepMin, currentRepMax,
                    homeRepMin, homeRepMax,
                    worldRepMin, worldRepMax,
                    caMin, caMax,
                    paMin, paMax,
                    attributeFields)) {
                return;
            }
            playerFilter = new PlayerFilterCriteria(
                    name.getValue(),
                    gender.getValue(),
                    playingNation.getValue(),
                    playingCompetition.getValue(),
                    ageMin.getValue(), ageMax.getValue(),
                    nationality.getValue(),
                    defaultInt(currentRepMin.getValue(), 1), currentRepMax.getValue(),
                    defaultInt(homeRepMin.getValue(), 1), homeRepMax.getValue(),
                    defaultInt(worldRepMin.getValue(), 1), worldRepMax.getValue(),
                    defaultInt(caMin.getValue(), 1), caMax.getValue(),
                    defaultInt(paMin.getValue(), 1), paMax.getValue(),
                    contractFrom.getValue(), contractTo.getValue(),
                    askingMin.value(), askingMax.value(),
                    selectedPositionMinimums(selectedPositions),
            selectedAttributeMinimums(attributeFields));
            showPlayers();
            dialog.close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button clear = new Button("Clear", event -> {
            playerFilter = PlayerFilterCriteria.empty();
            showPlayers();
            updateStatus(null);
            dialog.close();
        });
        Button cancel = new Button("Cancel", event -> dialog.close());

        dialog.add(dialogTabs, dialogContent);
        dialog.getFooter().add(clear, cancel, apply);
        dialog.open();
    }

    private void updateStatus(DatabaseLoadAllService.LoadAllResult result) {
        if (result == null) {
            status.setText("Players " + players.countPlayers()
                    + " | Clubs " + clubs.countClubs()
                    + " | Competitions " + competitions.countCompetitions());
            return;
        }
        status.setText("PID " + result.pid()
                + " | Game date " + nullSafe(result.gameDate())
                + " | Players " + result.players()
                + " | Clubs " + result.clubs()
                + " | Competitions " + result.competitions());
    }

    private static String display(Object value) {
        return value == null ? "" : Objects.toString(value);
    }

    private static int compareColumn(Map<String, Object> left, Map<String, Object> right, String column) {
        if (NUMERIC_SORT_COLUMNS.contains(column)) {
            return compareLongs(sortableLong(left.get(column)), sortableLong(right.get(column)));
        }
        return display(left.get(column)).compareToIgnoreCase(display(right.get(column)));
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

    private void createAttributeFields(Map<String, IntegerField> attributeFields, FormLayout attributeLayout) {
        if (!attributeFields.isEmpty()) {
            return;
        }
        for (FieldDef field : AttributeDefinitions.VISIBLE_FIELDS) {
            String column = PlayerColumnNames.toColumnName(field.name()).toUpperCase();
            IntegerField attribute = intField(displayName(field.name()), playerFilter.attributeMinimums().getOrDefault(column, 1), 1, 20);
            attributeFields.put(column, attribute);
            attributeLayout.add(attribute);
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
        for (IntegerField field : attributeFields.values()) {
            if (!validIntegerField(field, 1, 20)) {
                return false;
            }
        }
        return validRange("Current reputation", defaultInt(currentRepMin.getValue(), 1), currentRepMax.getValue())
                && validRange("Home reputation", defaultInt(homeRepMin.getValue(), 1), homeRepMax.getValue())
                && validRange("World reputation", defaultInt(worldRepMin.getValue(), 1), worldRepMax.getValue())
                && validRange("CA", defaultInt(caMin.getValue(), 1), caMax.getValue())
                && validRange("PA", defaultInt(paMin.getValue(), 1), paMax.getValue());
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
        attributeFields.forEach((column, field) -> {
            Integer value = defaultInt(field.getValue(), 1);
            if (value != null && value > 1) {
                out.put(column, value);
            }
        });
        return out;
    }

    private static String positionLabel(String fieldName, PositionLevel level) {
        return displayName(fieldName) + " - " + level.label;
    }

    private static String displayName(String fieldName) {
        return toColumnName(fieldName).replace('_', ' ');
    }

    private static void applyPositionColor(Button button, PositionLevel level) {
        button.getStyle()
                .set("background", level.color)
                .set("color", level.textColor)
                .set("border", "1px solid var(--lumo-contrast-20pct)");
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
