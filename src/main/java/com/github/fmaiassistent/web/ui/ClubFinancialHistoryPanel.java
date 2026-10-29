package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.ClubFinancialHistoryLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;

public class ClubFinancialHistoryPanel extends VerticalLayout {
    public ClubFinancialHistoryPanel(ClubFinancialHistoryLayout.Profile profile) {
        setPadding(false);setWidthFull();add(new H4("Financial history"));
        add(new Span("Monthly history, oldest first. Amounts in £; snapshot as of "+profile.capturedDate()+"."));
        var series=List.of(profile.balances(),profile.transferBudgets(),profile.playerWages(),profile.scouting());
        var rows=new TreeMap<LocalDate,Integer[]>();
        for(int i=0;i<series.size();i++)for(var p:series.get(i))rows.computeIfAbsent(p.month(),d->new Integer[4])[i]=p.amount();
        if(rows.isEmpty()){add(new Span("No financial history recorded."));return;}
        var grid=new Grid<Row>();grid.setWidthFull();grid.setAllRowsVisible(true);
        grid.addColumn(Row::month).setHeader("Month").setKey("month");
        grid.addColumn(r->money(r.balance())).setHeader("Balance").setKey("balance");
        grid.addColumn(r->money(r.remaining())).setHeader("Remaining transfer budget").setKey("remaining");
        grid.addColumn(r->money(r.wages())).setHeader("Player wages").setKey("wages");
        grid.addColumn(r->money(r.scouting())).setHeader("Scouting expenditure").setKey("scouting");
        grid.getColumns().forEach(c->c.setAutoWidth(true));
        grid.setItems(rows.entrySet().stream().map(e->new Row(e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[2],e.getValue()[3])).toList());
        add(grid);
    }
    private static String money(Integer value){return value==null?"Unavailable":"£"+NumberFormat.getIntegerInstance(Locale.UK).format(value);}
    record Row(LocalDate month,Integer balance,Integer remaining,Integer wages,Integer scouting) { }
}
