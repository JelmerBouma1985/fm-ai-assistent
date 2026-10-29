package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.ClubFinancialHistoryCaptureTest;
import com.github.fmaiassistent.memory.ClubFinancialHistoryLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialHistoryPanelTest {
    @Test void capturedHistoricalBalancesAreShownInSixtyCorrectMonthlyRows()throws Exception {
        var p=ClubFinancialHistoryLayout.current().decode(HexFormat.of().parseHex(ClubFinancialHistoryCaptureTest.expectedPayloads().get(1036L)),ClubFinancialHistoryCaptureTest.CLOCK);
        var panel=new ClubFinancialHistoryPanel(p);
        @SuppressWarnings("unchecked") var grid=(Grid<ClubFinancialHistoryPanel.Row>)panel.getChildren().filter(Grid.class::isInstance).findFirst().orElseThrow();
        assertThat(grid.getColumns()).hasSize(5);var rows=grid.getListDataView().getItems().toList();assertThat(rows).hasSize(60);
        assertThat(rows.getFirst().month()).hasToString("2031-05-01");assertThat(rows.getFirst().balance()).isEqualTo(21230397);assertThat(rows.get(1).balance()).isEqualTo(30133822);
        assertThat(rows.getLast().month()).hasToString("2036-04-01");
        assertThat(rows.getFirst().remaining()).isEqualTo(p.transferBudgets().getFirst().amount());
    }
    @Test void observedEmptyHistoryIsExplainedRatherThanFilledWithZeroes()throws Exception {
        var p=ClubFinancialHistoryLayout.current().decode(HexFormat.of().parseHex(ClubFinancialHistoryCaptureTest.expectedPayloads().get(581L)),ClubFinancialHistoryCaptureTest.CLOCK);
        var panel=new ClubFinancialHistoryPanel(p);assertThat(panel.getChildren().filter(Grid.class::isInstance)).isEmpty();
        assertThat(panel.getChildren().filter(Span.class::isInstance).map(c->((Span)c).getText())).contains("No financial history recorded.");
    }
    @Test void unequalSeriesAlignByMonthAndUnknownAmountDoesNotBecomeZero()throws Exception {
        var p=ClubFinancialHistoryLayout.current().decode(HexFormat.of().parseHex(ClubFinancialHistoryCaptureTest.expectedPayloads().get(1036L)),ClubFinancialHistoryCaptureTest.CLOCK);
        var last=p.balances().getLast().month();var adjusted=new ClubFinancialHistoryLayout.Profile(p.capturedDate(),p.summary(),p.expenditure(),p.balances(),p.transferBudgets(),
                java.util.List.of(new ClubFinancialHistoryLayout.Point(last,0)),java.util.List.of(new ClubFinancialHistoryLayout.Point(last,null)),p.rawHex());
        var panel=new ClubFinancialHistoryPanel(adjusted);
        @SuppressWarnings("unchecked") var grid=(Grid<ClubFinancialHistoryPanel.Row>)panel.getChildren().filter(Grid.class::isInstance).findFirst().orElseThrow();
        var rows=grid.getListDataView().getItems().toList();assertThat(rows).hasSize(60);assertThat(rows.getFirst().wages()).isNull();
        assertThat(rows.getLast().wages()).isZero();assertThat(rows.getLast().scouting()).isNull();
    }
}
