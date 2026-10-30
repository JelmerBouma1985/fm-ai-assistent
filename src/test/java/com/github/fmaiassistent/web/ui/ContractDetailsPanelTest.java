package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.StoredContract;
import com.github.fmaiassistent.exporter.Fm27CapturedContractsTest;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractDetailsPanelTest {
    @Test void currentProfileRendersTypedTermsAndUnknownProfileRemainsUnavailable() {
        var term=new com.github.fmaiassistent.domain.ContractSnapshot.Term("FULL_68",0,"0000000000000000",123L,null,32);
        var snapshot=new com.github.fmaiassistent.domain.ContractSnapshot("FULL",-1,101,null,3,null,null,null,
                null,null,null,null,null,null,null,null,null,null,"00".repeat(248),null,List.of(term),null,null);
        var current=new ContractDetailsPanel(List.of(new StoredContract(1,
                com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256,snapshot)),n->"GBP "+n,true);
        assertThat(rows(current)).contains(new ContractDetailsPanel.Detail("Appearance Fee","GBP 123"));
        assertThat(text(current)).noneMatch(s->s.contains("Detailed terms are unavailable"));
        var unknown=new ContractDetailsPanel(List.of(new StoredContract(1,"0".repeat(64),snapshot)),n->"GBP "+n,true);
        assertThat(rows(unknown)).isEmpty();
        assertThat(text(unknown)).anyMatch(s->s.contains("Detailed terms are unavailable"));
    }

@Test void fm27CapturedBonusesAndAdditionalClubAgreementReachTheView() throws Exception {
        var captured=captured(3);
        var panel=new ContractDetailsPanel(captured.stream().map(c->new StoredContract(1,NativeModuleProfile.FM27_SHA256,c)).toList(),n->"GBP "+n,true);
        assertThat(rows(panel)).contains(new ContractDetailsPanel.Detail("Appearance Fee","GBP 327"),
                new ContractDetailsPanel.Detail("Clean Sheet Bonus","GBP 163"),
                new ContractDetailsPanel.Detail("Unused Substitute Fee","GBP 218"),
                new ContractDetailsPanel.Detail("Club ID","1661"),new ContractDetailsPanel.Detail("Club ID","7446373"));
    }

    @Test void fm27CapturedLoanKeepsItsConditionalFeeAndSeparatePlayingTime() throws Exception {
        var captured=captured(2);
        var primary=ContractDetailsPanel.details(captured.get(0),n->"GBP "+n,true);
        var loan=ContractDetailsPanel.details(captured.get(1),n->"GBP "+n,true);
        assertThat(primary).anyMatch(r->r.label().equals("Agreed playing time"));
        assertThat(loan).anyMatch(r->r.label().startsWith("Transfer fee after ")&&r.value().equals("GBP 262"));
        assertThat(loan).contains(new ContractDetailsPanel.Detail("Club ID","658"));
    }

    @Test void fm27CapturedBasicAgreementKeepsItsNationalTeamNamespace() throws Exception {
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,NativeModuleProfile.FM27_SHA256,captured(1).getFirst())),n->"GBP "+n,false);
        assertThat(rows(panel)).containsExactly(new ContractDetailsPanel.Detail("National team ID","784"));
        assertThat(text(panel)).anyMatch(s->s.contains("Further details are not yet available"));
    }

    @Test void fm27FutureContractShowsCapturedWageEndAndDestination() throws Exception {
        var fixture=Fm27CapturedContractsTest.fixtures().get(4);
        var e=Fm27CapturedContractsTest.exporter(fixture,ContractRecordLayout.fm27())
                .readFutureEmployment(fixture.path("person").asLong(),fixture.path("uid").asLong(),0).orElseThrow();
        var future=new com.github.fmaiassistent.domain.FutureTransferSnapshot(java.time.LocalDate.of(2026,8,4),e);
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,NativeModuleProfile.FM27_SHA256,e.contract(),future,"Destination")),n->"MONEY "+n,n->"WEEKLY "+n,true);
        assertThat(rows(panel)).contains(new ContractDetailsPanel.Detail("Contract end","2030-06-30"),
                new ContractDetailsPanel.Detail("Weekly wage","WEEKLY 19821"),new ContractDetailsPanel.Detail("Club","Destination"));
    }

    @Test void unsupportedSnapshotsAndMissingContractsRemainExplicitlyUnavailable() throws Exception {
        var panel=new ContractDetailsPanel(List.of(new StoredContract(1,null,captured(0).getFirst())),n->"GBP "+n,false);
        assertThat(rows(panel)).isEmpty();assertThat(text(panel)).anyMatch(s->s.contains("unavailable"));
        assertThat(text(new ContractDetailsPanel(List.of(),n->"GBP "+n,true))).anyMatch(s->s.contains("No contract data"));
    }

    private static List<com.github.fmaiassistent.domain.ContractSnapshot> captured(int index) throws Exception {
        var fixture=Fm27CapturedContractsTest.fixtures().get(index);
        return Fm27CapturedContractsTest.exporter(fixture,ContractRecordLayout.fm27()).read(fixture.path("person").asLong(),fixture.path("uid").asLong());
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
