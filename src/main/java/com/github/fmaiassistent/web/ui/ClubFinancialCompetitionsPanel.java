package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.text.NumberFormat;
import java.util.*;

/** Per-competition amounts from this club's own financial records, with durable native IDs. */
public class ClubFinancialCompetitionsPanel extends VerticalLayout {
    public ClubFinancialCompetitionsPanel(ClubFinancialCompetitionLayout.Profile profile) {
        this(profile,Map.of());
    }
    public ClubFinancialCompetitionsPanel(ClubFinancialCompetitionLayout.Profile profile,Map<Long,String> names) {
        setPadding(false);setWidthFull();add(new H4("Competition income"));
        add(new Span("Club's own records as of "+profile.capturedDate()+". Amounts in £; prize money before bonuses. Game IDs remain visible when a name is unavailable."));
        var tv=new Details("TV revenue by competition",grid(profile,true,names));tv.setWidthFull();
        var prize=new Details("Prize money by competition",grid(profile,false,names));prize.setWidthFull();add(tv,prize);
    }
    private Grid<Row> grid(ClubFinancialCompetitionLayout.Profile profile,boolean tv,Map<Long,String> names) {
        var grid=new Grid<Row>();grid.setWidthFull();grid.setAllRowsVisible(true);
        grid.addColumn(r->names.getOrDefault(r.uid(),"Name unavailable")).setHeader("Competition").setWidth("18rem").setFlexGrow(2);
        grid.addColumn(Row::uid).setHeader("Game ID").setWidth("8rem").setFlexGrow(0);
        var periods=profile.periods().stream().filter(p->p.index()<4||!p.tvRevenue().isEmpty()||!p.prizeMoney().isEmpty()).toList();
        var ids=new LinkedHashSet<Long>();for(var period:periods)for(var member:tv?period.tvRevenue():period.prizeMoney())ids.add(member.competitionUid());
        for(int n=0;n<periods.size();n++){final int i=n;grid.addColumn(r->money(r.amounts().get(i))).setHeader(periods.get(n).label()).setWidth("11rem").setFlexGrow(1);}
        var rows=new ArrayList<Row>();for(long uid:ids){var amounts=new ArrayList<Integer>();for(var period:periods)
            amounts.add((tv?period.tvRevenue():period.prizeMoney()).stream().filter(m->m.competitionUid()==uid).map(ClubFinancialCompetitionLayout.Member::amount).findFirst().orElse(null));
            rows.add(new Row(uid,Collections.unmodifiableList(amounts)));}grid.setItems(rows);return grid;
    }
    private static String money(Integer amount){return amount==null?"Unavailable":"£"+NumberFormat.getIntegerInstance(Locale.UK).format(amount);}
    record Row(long uid,List<Integer> amounts) { }
}
