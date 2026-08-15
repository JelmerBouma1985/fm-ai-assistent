package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;

import java.util.ArrayList;
import java.util.List;

final class ManagedClubContextPanel extends Div {
    private final ManagedClubContextService contexts;
    private final Runnable contextChanged;
    private final Icon icon = VaadinIcon.OFFICE.create();
    private final Span heading = new Span();
    private final Span details = new Span();
    private final Checkbox includeInAi = new Checkbox("Include in AI chats");

    ManagedClubContextPanel(ManagedClubContextService contexts, Runnable contextChanged) {
        this.contexts = contexts;
        this.contextChanged = contextChanged;
        addClassName("managed-club-context-panel");
        icon.addClassName("managed-club-context-icon");
        heading.addClassName("managed-club-context-heading");
        details.addClassName("managed-club-context-details");
        includeInAi.setValue(contexts.aiContextEnabled());
        includeInAi.addValueChangeListener(event -> {
            contexts.setAiContextEnabled(Boolean.TRUE.equals(event.getValue()));
            contextChanged.run();
        });
        includeInAi.addClassNames("ai-context-toggle", "managed-club-context-toggle");
        Div copy = new Div(heading, details);
        copy.addClassName("managed-club-context-copy");
        add(icon, copy, includeInAi);
        refresh();
    }

    void refresh() {
        ManagedClubContext context = contexts.current();
        removeClassName("is-unavailable");
        if (context.available()) {
            heading.setText("Managed club · " + context.clubName());
            List<String> values = new ArrayList<>();
            values.add("Manager " + context.managerName());
            addIfPresent(values, context.competition());
            addIfPresent(values, context.nation());
            details.setText(String.join(" · ", values));
            setTitle("Detected from FM26 RAM at the most recent data load");
            return;
        }
        addClassName("is-unavailable");
        if (context.state() == ManagedClubContext.State.NOT_LOADED) {
            heading.setText("Managed club · waiting for FM26 data");
        } else {
            heading.setText("Managed club · unavailable");
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
