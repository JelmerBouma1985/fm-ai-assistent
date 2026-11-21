package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;

import java.util.ArrayList;
import java.util.List;

final class ManagedClubContextPanel extends Div {
    private final ManagedClubContextService contexts;
    private final Runnable contextChanged;
    private final Icon icon = VaadinIcon.OFFICE.create();
    private final Span heading = new Span();
    private final Span details = new Span();
    private final Checkbox includeInAi = new Checkbox("Include in AI chats");
    private final Select<ManagedClubContext> managerSelect = new Select<>();
    private boolean refreshing;

    ManagedClubContextPanel(ManagedClubContextService contexts, Runnable contextChanged) {
        this.contexts = contexts;
        this.contextChanged = contextChanged;
        addClassName("managed-club-context-panel");
        icon.addClassName("managed-club-context-icon");
        heading.addClassName("managed-club-context-heading");
        details.addClassName("managed-club-context-details");
        includeInAi.setValue(contexts.aiContextEnabled());
        includeInAi.addValueChangeListener(event -> {
            if (refreshing) return;
            contexts.setAiContextEnabled(Boolean.TRUE.equals(event.getValue()));
            contextChanged.run();
        });
        includeInAi.addClassNames("ai-context-toggle", "managed-club-context-toggle");
        managerSelect.setLabel("Human manager");
        managerSelect.setWidthFull();
        managerSelect.setItemLabelGenerator(manager -> manager.managerName() + " - "
                + (manager.available() ? manager.clubName() : manager.nationalTeamAvailable()
                ? manager.nationalTeam().name() : "Unemployed"));
        managerSelect.addValueChangeListener(event -> {
            if (refreshing || event.getValue() == null) return;
            contexts.selectManager(event.getValue().managerUniqueId());
            refresh();
            contextChanged.run();
        });
        Div copy = new Div(managerSelect, heading, details);
        copy.addClassName("managed-club-context-copy");
        add(icon, copy, includeInAi);
        refresh();
    }

    void refresh() {
        ManagedClubContext context = contexts.current();
        refreshing = true;
        try {
            List<ManagedClubContext> managers = contexts.managers();
            managerSelect.setItems(managers);
            managerSelect.setValue(managers.stream()
                    .filter(manager -> java.util.Objects.equals(manager.managerUniqueId(), context.managerUniqueId()))
                    .findFirst().orElse(null));
            managerSelect.setEnabled(managers.size() > 1);
            managerSelect.setVisible(!managers.isEmpty());
            includeInAi.setValue(contexts.aiContextEnabled());
        } finally {
            refreshing = false;
        }
        includeInAi.setEnabled(context.adviceAvailable() || context.state() == ManagedClubContext.State.UNEMPLOYED);
        removeClassName("is-unavailable");
        if (context.available()) {
            heading.setText("Managed club · " + context.clubName());
            List<String> values = new ArrayList<>();
            values.add("Manager " + context.managerName());
            if (context.nationalTeamAvailable()) values.add("National team " + context.nationalTeam().name());
            addIfPresent(values, context.competition());
            addIfPresent(values, context.nation());
            details.setText(String.join(" · ", values));
            setTitle("Detected from FM27 RAM at the most recent data load");
            return;
        }
        if (context.nationalTeamAvailable()) {
            heading.setText("National team: " + context.nationalTeam().name());
            details.setText("Manager " + context.managerName() + "; player selection and tactical advice available");
            setTitle(context.message());
            return;
        }
        addClassName("is-unavailable");
        if (context.state() == ManagedClubContext.State.NOT_LOADED) {
            heading.setText("Managed club · waiting for FM27 data");
        } else if (context.state() == ManagedClubContext.State.UNEMPLOYED) {
            heading.setText("Manager is unemployed");
        } else {
            heading.setText("Failed to load managed club");
        }
        details.setText(context.message());
        setTitle(context.message());
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }
}
