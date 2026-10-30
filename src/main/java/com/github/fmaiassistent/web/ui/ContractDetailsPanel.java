package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.StoredContract;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Presents native-evidenced values; opaque terms remain stored and explicitly unavailable. */
final class ContractDetailsPanel extends VerticalLayout {
    record Detail(String label,String value) { }

    ContractDetailsPanel(List<StoredContract> contracts,Function<Number,String> money,boolean player) {
        this(contracts,money,money,player);
    }

    ContractDetailsPanel(List<StoredContract> contracts,Function<Number,String> money,Function<Number,String> weeklyWage,boolean player) {
        setPadding(false);setWidthFull();
        if(contracts.isEmpty()) {
            add(new Span("No contract data is available in this snapshot. Refresh game data to capture contracts."));
            return;
        }
        for(var stored:contracts) {
            var c=stored.snapshot();
            String kind=switch(c.kind()){case "FULL"->"Employment contract";case "LOAN"->"Loan agreement";case "B_CLUB"->"Additional club agreement";case "BASIC"->"Club agreement";default->"Other agreement";};
            add(new H3(stored.futureTransfer()!=null?"Agreed future contract":(c.slot()==-1?"Main contract: ":"Additional contract: ")+kind));
            if(!NativeModuleProfile.supported(stored.moduleSha256())
                    || !List.of("FULL","LOAN","B_CLUB","BASIC").contains(c.kind())) {
                add(new Span("Detailed terms are unavailable for this snapshot. Refresh game data."));
                continue;
            }
            var details=new ArrayList<Detail>();
            if(stored.clubName()!=null&&!stored.clubName().isBlank())details.add(new Detail("Club",stored.clubName()));
            if(stored.futureTransfer()!=null) {
                var f=stored.futureTransfer();var e=f.employment();
                details.add(new Detail("Transfer date",f.transferDate()==null?"Not available":f.transferDate().toString()));
                details.add(new Detail("Contract end",e.endDate()==null?"Not available":e.endDate().toString()));
                details.add(new Detail("Weekly wage",weeklyWage.apply(e.wageRaw())));
            }
            details.addAll(details(c,money,player,stored.awardNames(),stored.competitionNames()));
            if(!details.isEmpty()) {
                Grid<Detail> grid=new Grid<>();grid.setWidthFull();
                if(details.size()<=12)grid.setAllRowsVisible(true);else grid.setHeight("420px");
                grid.addColumn(Detail::label).setHeader("Term").setAutoWidth(true).setFlexGrow(1);
                grid.addColumn(Detail::value).setHeader("Value").setAutoWidth(true).setFlexGrow(1);
                grid.setItems(details);add(grid);
            }
            long unavailable=c.terms().stream().filter(t->term(t,money,stored.awardNames(),stored.competitionNames())==null).count();
            if(unavailable>0)add(new Span("Details are not yet available for "+unavailable+" additional contract terms."));
            if("BASIC".equals(c.kind()))add(new Span("Further details are not yet available for this agreement."));
            else if(details.isEmpty()&&unavailable==0)add(new Span("No additional contract terms were captured."));
        }
    }

    static List<Detail> details(ContractSnapshot c,Function<Number,String> money,boolean player) {
        return details(c,money,player,java.util.Map.of());
    }
    static List<Detail> details(ContractSnapshot c,Function<Number,String> money,boolean player,java.util.Map<Long,String> awardNames) {
        return details(c,money,player,awardNames,java.util.Map.of());
    }
    static List<Detail> details(ContractSnapshot c,Function<Number,String> money,boolean player,java.util.Map<Long,String> awardNames,java.util.Map<Long,String> competitionNames) {
        var rows=new ArrayList<Detail>();
        if(player) {
            String label=c.agreedCode()==null?null:c.agreedCode()==0?"Not specified":playingTime(c.agreedCode());
            rows.add(new Detail("Agreed playing time",label==null?"Not available":label));
        }
        if(c.teamOwnerUid()!=null)rows.add(new Detail("NATIONAL_TEAM_CONTAINER".equals(c.teamOwnerKind())?"National team ID":"Club ID",Long.toString(c.teamOwnerUid())));
        if("LOAN".equals(c.kind())&&c.totalFee()!=null&&c.totalFee()>=0&&c.feeKind()!=null&&!"ABSENT".equals(c.feeKind())) {
            String label=switch(c.feeKind()) {
                case "MANDATORY"->"Mandatory transfer fee";
                case "AFTER_MATCHES"->"Transfer fee after "+c.threshold()+" appearances";
                case "OPTIONAL"->"Optional transfer fee";
                case "PROMOTION_FROM_COMPETITION"->"Transfer fee on promotion";
                case "AVOID_RELEGATION_FROM_COMPETITION"->"Transfer fee if relegation is avoided";
                default->"Conditional transfer fee";
            };
            rows.add(new Detail(label,money.apply(c.totalFee())));
            if(c.played()!=null&&c.played()>=0)rows.add(new Detail("Appearances recorded",c.played().toString()));
        }
        for(var term:c.terms()){var d=term(term,money,awardNames,competitionNames);if(d!=null)rows.add(d);}
        return List.copyOf(rows);
    }

    // Native labels197A280 + format197A760/English.ltc; see ADDITIONAL_CLAUSE_UNITS_AND_CONTEXT_20261022.md.
    private static Detail term(ContractSnapshot.Term t,Function<Number,String> money,java.util.Map<Long,String> awardNames,java.util.Map<Long,String> competitionNames) {
        if("FULL_80".equals(t.group())&&t.competitionReferences()!=null) {
            try {
                com.github.fmaiassistent.exporter.PlayerSnapshotValidator.validateCompetitionReferences(t);
                var r=com.github.fmaiassistent.memory.CompetitionBonusLayout.current().decode(t.rawHex());
                var refs=t.competitionReferences();String subject=refs.subjectUid()==null?null:competitionNames.get(refs.subjectUid());
                if(subject==null||r.amountRaw()<0||r.minimumGames()!=-1||r.stateRaw()!=0||r.opaqueRaw()!=0)return null;
                String label=switch(r.conditionKind()) {
                    case 0,14->"Competition bonus: "+subject;
                    case 3->"Promotion bonus: "+subject;
                    case 6->"Avoid relegation bonus: "+subject;
                    case 10->refs.conditionUid()!=null&&competitionNames.containsKey(refs.conditionUid())
                            ?"Qualification bonus: "+subject+" / "+competitionNames.get(refs.conditionUid()):null;
                    default->null;
                };
                if(label==null)return null;
                return new Detail(label,money.apply(r.amountRaw())+(r.stageUid()!=-1||r.secondaryStageUid()!=-1?" (stage conditions apply)":""));
            }catch(java.io.IOException|IllegalArgumentException|IndexOutOfBoundsException invalid){return null;}
        }
        if("FULL_98".equals(t.group())&&"AWARD".equals(t.referenceKind())&&t.referenceUid()!=null&&awardNames.containsKey(t.referenceUid())) {
            var amount=com.github.fmaiassistent.memory.AwardNameLayout.current().bonusAmount(t.rawHex());
            return amount.isEmpty()?null:new Detail(awardNames.get(t.referenceUid()),money.apply(amount.getAsInt()));
        }
        if(!"FULL_68".equals(t.group())||t.codeRaw()==null)return null;
        int code=t.codeRaw();Integer n=t.conditionRaw();Long value=t.valueRaw();
        String percentage=switch(code) {
            case 3->"Yearly Wage Rise";case 4->"Promotion Wage Rise";case 5->"Relegation Wage Drop";
            case 8->"Sell On Fee Percentage";case 14->"Top Division Promotion Wage Rise";case 15->"Top Division Relegation Wage Drop";
            case 20->"Percentage of Wages paid by Sponsor";case 21->"Percentage of Gate Receipts paid to Player";
            case 24->"Percentage Of Club Compensation Required For Managerial Role";default->null;
        };
        if(percentage!=null)return n!=null&&n>=0?new Detail(percentage,n+"%"):null;
        if((code==22||code==29)&&n!=null&&n>0)return new Detail(code==22?"Optional Contract Extension By Club":"Contract Extension After Promotion",n+(n==1?" year":" years"));
        if(value==null||value<0)return null;
        return switch(code) {
            case 0->new Detail("Minimum Fee Release Clause",money.apply(value));
            case 1->new Detail("Relegation Release Clause",money.apply(value));
            // FULL68 -> Cbla/Cbty/Cbrf (1976300 ->11B161E0); names from197A280.
            case 32->new Detail("Appearance Fee",money.apply(value));
            case 33->new Detail("Goal Bonus",money.apply(value));
            case 34->new Detail("Clean Sheet Bonus",money.apply(value));
            case 35->new Detail("Team Of The Year Bonus (Division)",money.apply(value));
            case 36->new Detail("Top Goalscorer Bonus (Division)",money.apply(value));
            case 37->new Detail("International Cap Bonus",money.apply(value));
            case 38->new Detail("Unused Substitute Fee",money.apply(value));
            case 39->new Detail("Assist Bonus",money.apply(value));
            case 10,40,41->n!=null&&n>0?new Detail(code==10?"Seasonal Landmark Goal Bonus":code==40?"Seasonal Landmark Assist Bonus":"Seasonal Landmark Combined Goal and Assist Bonus",
                    money.apply(value)+" after "+n+(code==10?" league goals":code==40?" league assists":" combined league goals and assists")):null;
            default->null;
        };
    }

    // Parser17001D0 and consumer1700E30; unknown codes and B-club effective overrides are not invented.
    private static String playingTime(int code) {
        return switch(code) {
            case 1->"Star player";case 2->"Important player";case 3->"Regular starter";case 4->"Squad player";
            case 5->"Impact substitute";case 6->"Squad rotation";case 7->"Occasional first-team opportunities";
            case 8->"Fringe player";case 9->"Emergency backup";case 10->"Breakthrough prospect";
            case 11->"Future prospect";case 12->"Hot prospect";case 13->"Youngster";case 14->"B-club regular";
            case 15->"First-choice goalkeeper";case 16->"All cups goalkeeper";case 17->"Domestic cup goalkeeper";
            case 18->"Continental cup goalkeeper";case 19->"Cup goalkeeper";case 20->"Backup goalkeeper";
            case 21->"Emergency backup goalkeeper";case 22->"Surplus to requirements";default->null;
        };
    }
}
