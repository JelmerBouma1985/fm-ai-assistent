package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.repository.ContractRepository;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.staff.normalization.database",matches="true")
class StaffNormalizationApplicationDatabaseLiveTest {
    @Test void normalApplicationRefreshStoresAndShowsNativeFitnessOne()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        var row=jdbc.queryForMap("SELECT * FROM staff WHERE unique_id=2005797");
        assertThat(((Number)row.get("FITNESS")).intValue()).isEqualTo(1);assertThat(row.get("NAME")).isEqualTo("Adrian Viveash");
        var values=new LinkedHashMap<String,Object>();row.forEach((key,value)->values.put(key.toLowerCase(Locale.ROOT),value));
        var staff=StaffEntity.fromExportRow(values);var id=StaffEntity.class.getDeclaredField("id");id.setAccessible(true);id.set(staff,((Number)row.get("ID")).longValue());
        var ui=new UI();ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));UI.setCurrent(ui);String displayed;
        try {
            var view=MainViewTest.view(new ContractRepository(jdbc));
            var open=MainView.class.getDeclaredMethod("openStaffDetailsDialog",StaffEntity.class);open.setAccessible(true);open.invoke(view,staff);
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var dialog=descendants(ui).filter(Dialog.class::isInstance).map(Dialog.class::cast).filter(Dialog::isOpened).findFirst().orElseThrow();
            var tabs=descendants(dialog).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
            tabs.setSelectedTab(tabs.getChildren().filter(Tab.class::isInstance).map(Tab.class::cast).filter(t->t.getLabel().equals("Attributes")).findFirst().orElseThrow());
            var fitness=descendants(dialog).filter(c->c.getChildren().anyMatch(child->child instanceof Span s&&s.getText().equals("Fitness"))).findFirst().orElseThrow();
            displayed=fitness.getChildren().filter(Span.class::isInstance).map(Span.class::cast).filter(s->s.hasClassName("detail-value")).map(Span::getText).findFirst().orElseThrow();
            assertThat(displayed).startsWith("1 - ");dialog.close();
        }finally{UI.setCurrent(null);}
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/staff_normalization_candidates_live_20261025.json"))){bytes=in.readNBytes(131073);}
        assertThat(bytes.length).isLessThanOrEqualTo(131072);var before=JsonMapper.builder().build().readTree(bytes);int repaired=0;
        for(var sample:before.get("samples")){
            String field=sample.get("javaLabel").asString();assertThat(List.of("fitness","defending","attacking")).contains(field);
            assertThat(jdbc.queryForObject("SELECT "+field+" FROM staff WHERE unique_id=?",Integer.class,sample.get("uid").asLong())).isEqualTo(1);repaired++;
        }
        assertThat(repaired).isEqualTo(9);
        var report=new LinkedHashMap<String,Object>();report.put("database","actual normal application snapshot, read-only verification");
        report.put("uid",2005797);report.put("name",row.get("NAME"));report.put("club",row.get("CLUB"));report.put("rawCaptured",2);
        report.put("storedFitness",1);report.put("operatorReportedInGame",1);report.put("actualMainViewAttribute",displayed);report.put("repairedCapturedComparisons",repaired);
        report.put("players",jdbc.queryForObject("SELECT COUNT(*) FROM players",Long.class));report.put("staff",jdbc.queryForObject("SELECT COUNT(*) FROM staff",Long.class));
        report.put("canonicalClubs",jdbc.queryForObject("SELECT COUNT(*) FROM club_records",Long.class));report.put("contracts",jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Long.class));
        report.put("limitation","MainView server components verified; browser pixels unavailable. Other19field semantic labels/role-suppression/signed invalid bytes not universally certified.");
        Files.write(Path.of(System.getProperty("fm26.staff.normalization.database.report")),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
    private static java.util.stream.Stream<Component> descendants(Component root){return root.getChildren().flatMap(c->java.util.stream.Stream.concat(java.util.stream.Stream.of(c),descendants(c)));}
}
