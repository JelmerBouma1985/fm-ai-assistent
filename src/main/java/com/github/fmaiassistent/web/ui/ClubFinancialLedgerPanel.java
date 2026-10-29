package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.ClubFinancialLedgerLayout;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Four named financial periods for this canonical club's own financial record. */
public class ClubFinancialLedgerPanel extends VerticalLayout {
    public ClubFinancialLedgerPanel(ClubFinancialLedgerLayout.Profile profile) {
        setPadding(false);add(new H4("Club income and expenditure"));
        add(new Span("Club's own financial records as of "+profile.capturedDate().format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))+". Amounts in £."));
        var income=new Details("Income",grid(profile,true));var expenditure=new Details("Expenditure",grid(profile,false));
        var taxContent=new VerticalLayout(new Span("Amounts are included in Tax above. Codes distinguish categories with the same name."),taxGrid(profile));
        taxContent.setPadding(false);var tax=new Details("Tax breakdown",taxContent);
        income.setWidthFull();expenditure.setWidthFull();tax.setWidthFull();add(income,expenditure,tax);
    }
    private Grid<Row> grid(ClubFinancialLedgerLayout.Profile p,boolean income) {
        var grid=new Grid<Row>();grid.setWidthFull();grid.setAllRowsVisible(true);
        grid.addColumn(Row::label).setHeader("Category").setWidth("18rem").setFlexGrow(2);
        for(int index=0;index<p.periods().size();index++) {final int i=index;
            grid.addColumn(r->money(r.values().get(i))).setHeader(p.periods().get(i).name().label()).setWidth("11rem").setFlexGrow(1);
        }
        var rows=new ArrayList<Row>();
        for(var category:ClubFinancialLedgerLayout.Category.values())if(category.income()==income)
            rows.add(new Row(category.label(),p.periods().stream().map(period->period.amount(category)).toList()));
        if(income)rows.add(new Row("Turnover",p.periods().stream().map(ClubFinancialLedgerLayout.Period::turnover).toList()));
        rows.add(new Row(income?"Total income":"Total expenditure",p.periods().stream()
                .map(period->income?period.totalIncome():period.totalExpenditure()).toList()));
        grid.setItems(rows);return grid;
    }
    private Grid<TaxRow> taxGrid(ClubFinancialLedgerLayout.Profile p) {
        var grid=new Grid<TaxRow>();grid.setWidthFull();grid.setAllRowsVisible(true);
        grid.addColumn(TaxRow::label).setHeader("Tax type").setWidth("18rem").setFlexGrow(2);
        grid.addColumn(r->r.code()==null?"":r.code().toString()).setHeader("Category code").setWidth("8rem").setFlexGrow(0);
        for(int index=0;index<p.periods().size();index++){final int i=index;
            grid.addColumn(r->money(r.values().get(i))).setHeader(p.periods().get(i).name().label()).setWidth("11rem").setFlexGrow(1);}
        var rows=new ArrayList<TaxRow>();for(var type:ClubFinancialLedgerLayout.TaxType.values())
            rows.add(new TaxRow(type.code(),type.label(),p.periods().stream().map(period->period.taxAmount(type)).toList()));
        rows.add(new TaxRow(null,"Total tax",p.periods().stream().map(period->period.amount(ClubFinancialLedgerLayout.Category.TAX)).toList()));
        grid.setItems(rows);return grid;
    }
    private static String money(int value){return "£"+NumberFormat.getIntegerInstance(Locale.UK).format(value);}
    record Row(String label,List<Integer> values) { }
    record TaxRow(Integer code,String label,List<Integer> values) { }
}
