package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.ClubFinancialCompetitionCaptureTest;
import com.github.fmaiassistent.memory.*;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialCompetitionsPanelTest {
    static Stream<Component> descendants(Component c){return Stream.concat(Stream.of(c),c.getChildren().flatMap(ClubFinancialCompetitionsPanelTest::descendants));}
    @Test void capturedHeerenveenShowsTwoKindsFourPeriodsAndDistinctUidRows()throws Exception {
        var p=ClubFinancialCompetitionLayout.values(ClubFinancialCompetitionCaptureTest.expectedPayloads().get(1036L),0x07F41A8D,ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
        var panel=new ClubFinancialCompetitionsPanel(p);var details=descendants(panel).filter(Details.class::isInstance).map(Details.class::cast).toList();assertThat(details).hasSize(2);
        for(var d:details){assertThat(d.getWidth()).isEqualTo("100%");var grid=(Grid<?>)d.getContent().findFirst().orElseThrow();assertThat(grid.getColumns()).hasSize(6);assertThat(grid.getListDataView().getItemCount()).isEqualTo(3);}
        var tv=(Grid<?>)details.getFirst().getContent().findFirst().orElseThrow();
        var row=(ClubFinancialCompetitionsPanel.Row)tv.getListDataView().getItem(0);assertThat(row.uid()).isEqualTo(29);assertThat(row.amounts().getFirst()).isEqualTo(70047);
    }
}
