package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.ClubUefaCoefficientLayout;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.math.BigDecimal;

/** Proven native coefficients; no guessed forecast or raw implementation fields. */
public class ClubCoefficientsPanel extends VerticalLayout {
    public ClubCoefficientsPanel(ClubUefaCoefficientLayout.Values values) {
        setPadding(false);
        var summary = new FormLayout();
        summary.addFormItem(new Span(number(values.fiveYearCoefficient())), "Five-year coefficient");
        summary.addFormItem(new Span(number(values.tenYearCoefficient())), "Ten-year coefficient");
        summary.addFormItem(new Span(rank(values.fiveYearRank())), "Five-year ranking");
        summary.addFormItem(new Span(rank(values.tenYearRank())), "Ten-year ranking");
        summary.addFormItem(new Span(number(values.currentSeasonPoints())), "Current-season points");
        add(summary, new Span("Coefficient history (oldest to newest)"));
        var history = new FormLayout();
        for (int i = 0; i < values.history().size(); i++) {
            history.addFormItem(new Span(number(values.history().get(i))), "History " + (i + 1));
        }
        add(history);
    }

    static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String rank(Integer value) { return value == null ? "Unavailable" : value.toString(); }
}
