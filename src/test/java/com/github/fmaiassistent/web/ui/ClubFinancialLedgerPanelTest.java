package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.ClubFinancialLedgerCaptureTest;
import com.github.fmaiassistent.memory.ClubFinancialLedgerLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.details.Details;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialLedgerPanelTest {
    @Test void incomeAndExpenseTablesExposeAllFourCorrectPeriodsAndDistinctTurnover()throws Exception {
        var p=ClubFinancialLedgerLayout.values(ClubFinancialLedgerCaptureTest.payload(ClubFinancialLedgerCaptureTest.financeRows().get(1036L)),ClubFinancialLedgerCaptureTest.CLOCK,ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
        var panel=new ClubFinancialLedgerPanel(p);var details=panel.getChildren().filter(c->c instanceof Details).map(c->(Details)c).toList();assertThat(details).hasSize(3);
        assertThat(details).allSatisfy(d->assertThat(d.getWidth()).isEqualTo("100%"));
        @SuppressWarnings("unchecked") var income=(Grid<ClubFinancialLedgerPanel.Row>)details.getFirst().getContent().filter(c->c instanceof Grid).findFirst().orElseThrow();
        var rows=income.getListDataView().getItems().toList();assertThat(rows).hasSize(19);
        assertThat(rows.stream().filter(r->r.label().equals("Sponsorship")).findFirst().orElseThrow().values()).containsExactly(0,0,8414566,6662136);
        assertThat(rows.stream().filter(r->r.label().equals("Total income")).findFirst().orElseThrow().values()).containsExactly(21609067,16364810,369964713,292098446);
        assertThat(rows.stream().filter(r->r.label().equals("Turnover")).findFirst().orElseThrow().values().getFirst()).isEqualTo(21594405);
        assertThat(income.getColumns()).hasSize(5);
        @SuppressWarnings("unchecked") var expense=(Grid<ClubFinancialLedgerPanel.Row>)details.get(1).getContent().filter(c->c instanceof Grid).findFirst().orElseThrow();
        assertThat(expense.getListDataView().getItems()).hasSize(22);
        assertThat(expense.getListDataView().getItems().filter(r->r.label().equals("Total expenditure")).findFirst().orElseThrow().values()).containsExactly(9099122,16658745,340350693,269086591);
    }
    @Test void taxBreakdownRetainsNineteenCodesFourPeriodsAndGenericOtherAmounts()throws Exception {
        var p=ClubFinancialLedgerLayout.values(ClubFinancialLedgerCaptureTest.payload(ClubFinancialLedgerCaptureTest.financeRows().get(1036L)),ClubFinancialLedgerCaptureTest.CLOCK,ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
        var panel=new ClubFinancialLedgerPanel(p);var details=panel.getChildren().filter(Details.class::isInstance).map(c->(Details)c).filter(d->d.getSummaryText().equals("Tax breakdown")).findFirst().orElseThrow();
        var content=details.getContent().findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var grid=(Grid<ClubFinancialLedgerPanel.TaxRow>)content.getChildren().filter(Grid.class::isInstance).findFirst().orElseThrow();
        assertThat(grid.getColumns()).hasSize(6);var rows=grid.getListDataView().getItems().toList();assertThat(rows).hasSize(20);
        assertThat(rows.stream().filter(r->r.label().equals("Other")).map(ClubFinancialLedgerPanel.TaxRow::code)).containsExactly(0,12,13,15);
        for(var type:ClubFinancialLedgerLayout.TaxType.values())assertThat(rows.get(type.code()).values()).isEqualTo(p.periods().stream().map(period->period.taxAmount(type)).toList());
        assertThat(rows.get(4).values().getFirst()).isEqualTo(176312);assertThat(rows.getLast().code()).isNull();assertThat(rows.getLast().label()).isEqualTo("Total tax");
        for(int i=0;i<4;i++){int sum=0;for(int t=0;t<19;t++)sum+=rows.get(t).values().get(i);assertThat(rows.getLast().values().get(i)).isEqualTo(sum);}
    }
}
