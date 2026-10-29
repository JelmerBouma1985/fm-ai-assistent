package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.StoredContract;
import com.github.fmaiassistent.exporter.ContractCaptureFixture;
import com.github.fmaiassistent.exporter.ContractExporter;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractDetailsPanelTest {
    @Test void capturedCompetitionBonusesShowSeparateNamesAndKeepStageQualificationExplicit()throws Exception {
        var names=java.util.Map.of(32L,"Italian Serie A",1301394L,"UEFA Champions League");
        var winning=ContractCaptureFixture.competitionBonus(0).decode().getFirst();
        assertThat(ContractDetailsPanel.details(winning,n->"RAW "+n,false,java.util.Map.of(),names))
                .contains(new ContractDetailsPanel.Detail("Competition bonus: UEFA Champions League","RAW 108779 (stage conditions apply)"));
        var qualifying=ContractCaptureFixture.competitionBonus(1).decode().getFirst();
        assertThat(ContractDetailsPanel.details(qualifying,n->"RAW "+n,false,java.util.Map.of(),names))
                .contains(new ContractDetailsPanel.Detail("Qualification bonus: Italian Serie A / UEFA Champions League","RAW 35072"));
        assertThat(ContractDetailsPanel.details(qualifying,n->"RAW "+n,false,java.util.Map.of(),java.util.Map.of(32L,"Italian Serie A")))
                .noneMatch(d->d.label().startsWith("Qualification bonus"));
        var t=winning.terms().getFirst();byte[] raw=java.util.HexFormat.of().parseHex(t.rawHex());raw[30]=1;
        var opaque=new com.github.fmaiassistent.domain.ContractSnapshot.Term(t.group(),0,java.util.HexFormat.of().formatHex(raw),null,null,null,null,null,t.competitionReferences());
        assertThat(ContractDetailsPanel.details(withTerms(winning,List.of(opaque)),n->"RAW "+n,false,java.util.Map.of(),names))
                .noneMatch(d->d.label().startsWith("Competition bonus"));
    }
    @Test void capturedAwardNamesShowAllSierdAwardsAndLeaveUnknownStateExplicitlyUnavailable() throws Exception {
        var row=com.github.fmaiassistent.exporter.AwardNameCaptureTest.capturedRows().stream().filter(r->((Number)r.get("unique_id")).longValue()==2002079297L).findFirst().orElseThrow();
        var names=new java.util.HashMap<Long,String>();for(var n:com.github.fmaiassistent.exporter.AwardNameCaptureTest.names(row))names.put(n.uniqueId(),n.name());
        @SuppressWarnings("unchecked") var awards=((List<com.github.fmaiassistent.domain.ContractSnapshot>)row.get("_contracts")).getFirst();
        var original=ContractCaptureFixture.full().decode().getFirst();
        var snapshot=withTerms(original,java.util.stream.Stream.concat(original.terms().stream(),awards.terms().stream()).toList());
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,ContractExporter.MODULE_SHA256,snapshot,null,null,names)),n->"GBP "+n,true);
        assertThat(rows(panel)).contains(new ContractDetailsPanel.Detail("World Footballer of the Year","GBP 190421"),
                new ContractDetailsPanel.Detail("World Player of the Year","GBP 152584"),new ContractDetailsPanel.Detail("World Golden Ball","GBP 190421"),
                new ContractDetailsPanel.Detail("Appearance Fee","GBP 12365"));
        assertThat(text(panel)).noneMatch(s->s.contains("additional contract terms"));
        var one=awards.terms().getFirst();byte[] raw=java.util.HexFormat.of().parseHex(one.rawHex());raw[com.github.fmaiassistent.memory.AwardNameLayout.current().stateRel()]=1;
        var opaque=new com.github.fmaiassistent.domain.ContractSnapshot.Term(one.group(),one.ordinal(),java.util.HexFormat.of().formatHex(raw),null,null,null,one.referenceKind(),one.referenceUid());
        var unavailable=new ContractDetailsPanel(List.of(new StoredContract(1,ContractExporter.MODULE_SHA256,withTerms(original,List.of(opaque)),null,null,names)),n->"GBP "+n,true);
        assertThat(text(unavailable)).anyMatch(s->s.contains("1 additional contract terms"));
        assertThat(rows(unavailable)).noneMatch(r->r.label().equals("World Footballer of the Year"));
    }
    @Test void rendersCapturedSierdBonusesAlongsideBothSeasonalMilestones() throws Exception {
        var snapshot=ContractCaptureFixture.full().decode().getFirst();
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,ContractExporter.MODULE_SHA256,snapshot)),n->"GBP "+n,true);
        assertThat(rows(panel)).contains(new ContractDetailsPanel.Detail("Appearance Fee","GBP 12365"),
                new ContractDetailsPanel.Detail("Goal Bonus","GBP 11592"),
                new ContractDetailsPanel.Detail("Unused Substitute Fee","GBP 3091"),
                new ContractDetailsPanel.Detail("Seasonal Landmark Goal Bonus","GBP 212678 after 10 league goals"),
                new ContractDetailsPanel.Detail("Seasonal Landmark Goal Bonus","GBP 53169 after 15 league goals"));
        assertThat(text(panel)).noneMatch(s->s.contains("additional contract terms"));
    }

    @Test void ordinaryBonusLabelsMatchNativeCatalogueAndRespectCollectionAndSentinels() throws Exception {
        var snapshot=ContractCaptureFixture.full().decode().getFirst();
        var file=java.nio.file.Path.of("docs/reverse-engineering/fm26/evidence/modern_contract_bonus_labels_20261022.json");
        final byte[] json;
        try(var in=java.nio.file.Files.newInputStream(file)){json=in.readNBytes(1024*1024+1);}
        assertThat(json.length).isLessThanOrEqualTo(1024*1024);
        var nativeModel=tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
        int checked=0;
        for(var label:nativeModel.get("labelCases")) {
            int code=label.get("code").asInt();if(code<32||code>39)continue;
            String name=label.get("labels").get(0).get("variants").get(0).get("text").asString();
            var term=new com.github.fmaiassistent.domain.ContractSnapshot.Term("FULL_68",0,"00000000ffff0000",0L,-1,code);
            assertThat(ContractDetailsPanel.details(withTerms(snapshot,List.of(term)),n->"GBP "+n,false))
                    .containsExactly(new ContractDetailsPanel.Detail(name,"GBP 0"));
            var negative=new com.github.fmaiassistent.domain.ContractSnapshot.Term("FULL_68",0,"ffffffffffff0000",-1L,-1,code);
            var unrelated=new com.github.fmaiassistent.domain.ContractSnapshot.Term("SHARED_18",0,"00000000ffff0000",100L,-1,code);
            assertThat(ContractDetailsPanel.details(withTerms(snapshot,List.of(negative,unrelated)),n->"GBP "+n,false)).isEmpty();
            checked++;
        }
        assertThat(checked).isEqualTo(8);
    }

    private static com.github.fmaiassistent.domain.ContractSnapshot withTerms(
            com.github.fmaiassistent.domain.ContractSnapshot c,List<com.github.fmaiassistent.domain.ContractSnapshot.Term> terms) {
        return new com.github.fmaiassistent.domain.ContractSnapshot(c.kind(),c.slot(),c.ownerUid(),c.teamUid(),c.agreedCode(),
                c.baseFee(),c.totalFee(),c.feeKind(),c.threshold(),c.played(),c.competitionIndex(),c.competitionUid(),
                c.conditionKind(),c.internalCompetition(),c.context1(),c.context2(),c.flagsA0(),c.flagsA8(),c.coreHex(),
                c.auxiliaryHex(),terms,c.teamOwnerKind(),c.teamOwnerUid());
    }

    @Test void futureContractShowsNewDatesWageAndClausesWithSeparateWageFormatting()throws Exception {
        var fixture=ContractCaptureFixture.futureEmployment(0);
        var e=new ContractExporter(fixture,fixture.base,com.github.fmaiassistent.memory.ContractRecordLayout.current())
                .readFutureEmployment(ContractCaptureFixture.PERSON,fixture.uid,74).orElseThrow();
        var f=new com.github.fmaiassistent.domain.FutureTransferSnapshot(java.time.LocalDate.of(2036,6,16),e);
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,ContractExporter.MODULE_SHA256,e.contract(),f,"Native destination club")),
                n->"MONEY "+n,n->"WEEKLY "+n,true);
        assertThat(rows(panel)).contains(new ContractDetailsPanel.Detail("Transfer date","2036-06-16"),
                new ContractDetailsPanel.Detail("Contract end","2038-06-30"),new ContractDetailsPanel.Detail("Weekly wage","WEEKLY 4109"),
                new ContractDetailsPanel.Detail("Club ID","731"),new ContractDetailsPanel.Detail("Club","Native destination club"),
                new ContractDetailsPanel.Detail("Promotion Wage Rise","45%"));
        assertThat(panel.getChildren().filter(com.vaadin.flow.component.html.H3.class::isInstance)
                .map(c->((com.vaadin.flow.component.html.H3)c).getText())).containsExactly("Agreed future contract");
    }
    @Test void basicAgreementShowsItsRegisteredClubAndKeepsOtherDetailsUnavailable() throws Exception {
        var snapshot=ContractCaptureFixture.basicStaff(0).decode().getFirst();
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,ContractExporter.MODULE_SHA256,snapshot)),n->"GBP "+n,false);
        assertThat(rows(panel)).containsExactly(new ContractDetailsPanel.Detail("Club ID","432"));
        assertThat(text(panel)).anyMatch(s->s.contains("Further details are not yet available"));
        assertThat(rows(panel)).noneMatch(r->r.label().contains("playing time")||r.label().contains("date"));
    }
    @Test void rendersActualStaffPercentageClausesWithoutTreatingMinusOneAsMoney() throws Exception {
        var snapshot=ContractCaptureFixture.standaloneStaff(1).decode().getFirst();
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,ContractExporter.MODULE_SHA256,snapshot)),n->"GBP "+n,false);
        assertThat(rows(panel)).contains(new ContractDetailsPanel.Detail("Top Division Promotion Wage Rise","25%"),new ContractDetailsPanel.Detail("Relegation Wage Drop","25%"));
        assertThat(rows(panel)).noneMatch(r->r.value().equals("GBP -1")||r.label().equals("Agreed playing time"));
    }

    @Test void preservesActualLoanAndPrimaryPlayingTimeAndMandatoryFeeSentinel() throws Exception {
        var captured=ContractCaptureFixture.loan().decode();
        var primary=ContractDetailsPanel.details(captured.get(0),n->"GBP "+n,true);
        var loan=ContractDetailsPanel.details(captured.get(1),n->"GBP "+n,true);
        assertThat(primary).contains(new ContractDetailsPanel.Detail("Agreed playing time","Important player"));
        assertThat(loan).contains(new ContractDetailsPanel.Detail("Agreed playing time","Squad player"),new ContractDetailsPanel.Detail("Mandatory transfer fee","GBP 698277"));
        assertThat(loan).noneMatch(r->r.label().contains("100 appearances"));
    }

    @Test void oldOrUnsupportedSnapshotsDoNotReceiveInventedTermInterpretations() throws Exception {
        var snapshot=ContractCaptureFixture.standaloneStaff(0).decode().getFirst();
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,null,snapshot)),n->"GBP "+n,false);
        assertThat(rows(panel)).isEmpty();
        assertThat(text(panel)).anyMatch(s->s.contains("unavailable"));
        assertThat(text(new ContractDetailsPanel(List.of(),n->"GBP "+n,true))).anyMatch(s->s.contains("No contract data"));
    }

    @SuppressWarnings("unchecked")
    private static List<ContractDetailsPanel.Detail> rows(Component c) {
        var result=new ArrayList<ContractDetailsPanel.Detail>();
        if(c instanceof Grid<?> grid) ((Grid<ContractDetailsPanel.Detail>)grid).getDataProvider().fetch(new Query<>()).forEach(result::add);
        c.getChildren().forEach(child->result.addAll(rows(child)));return result;
    }
    private static List<String> text(Component c) {
        var result=new ArrayList<String>();if(c instanceof Span span)result.add(span.getText());
        c.getChildren().forEach(child->result.addAll(text(child)));return result;
    }
}
