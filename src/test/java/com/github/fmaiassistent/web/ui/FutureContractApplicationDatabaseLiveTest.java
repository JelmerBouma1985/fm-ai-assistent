package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.repository.ContractRepository;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.data.provider.Query;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Explicit actual-database read-only check after the normal application committed its RAM refresh. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.delivery.database",matches="true")
class FutureContractApplicationDatabaseLiveTest {
    @Test void committedApplicationSnapshotOpensFutureTermsThroughTheActualMainViewTab()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");
        assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE").startsWith("jdbc:h2:file:");
        var dataSource=new DriverManagerDataSource(url,"sa","");var jdbc=new JdbcTemplate(dataSource);
        var repository=new ContractRepository(jdbc);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records",Long.class)).isEqualTo(26839);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE kind='LEAGUE_BODY_CLUB'",Long.class)).isEqualTo(462);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs WHERE unique_id IS NULL",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs v LEFT JOIN club_records n ON n.unique_id=v.unique_id WHERE n.unique_id IS NULL OR n.source_address<>v.source_address",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records n WHERE NOT EXISTS(SELECT 1 FROM clubs v WHERE v.unique_id=n.unique_id)",Long.class)).isEqualTo(2867);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts c LEFT JOIN club_records n ON c.team_owner_kind='CLUB' AND n.unique_id=c.team_owner_uid AND n.module_sha256=c.module_sha256",Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Long.class));
        long uid=2000105656L;
        long id=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?",Long.class,uid));
        var snapshots=repository.findPlayerContracts(id,uid);
        var future=snapshots.stream().filter(c->c.futureTransfer()!=null&&c.futureTransfer().employment().offerIndex()==74).findFirst().orElseThrow();
        assertThat(future.futureTransfer().employment().wageRaw()).isEqualTo(4109);
        assertThat(future.futureTransfer().employment().endDate().toString()).isEqualTo("2038-06-30");
        assertThat(future.clubName()).isEqualTo("Walsall");
        var ui=new UI();ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));UI.setCurrent(ui);
        var details=new ArrayList<ContractDetailsPanel.Detail>();
        try {
            var view=MainViewTest.view(repository);
            var player=PlayerEntity.fromExportRow(Map.of("unique_id",uid,"name","Dominic Ballard"));
            var field=PlayerEntity.class.getDeclaredField("id");field.setAccessible(true);field.set(player,id);
            var open=MainView.class.getDeclaredMethod("openPlayerDetailsDialog",PlayerEntity.class);open.setAccessible(true);open.invoke(view,player);
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var dialog=children(ui).filter(Dialog.class::isInstance).map(Dialog.class::cast).filter(Dialog::isOpened).findFirst().orElseThrow();
            var tabs=children(dialog).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
            var contractTab=tabs.getChildren().filter(Tab.class::isInstance).map(Tab.class::cast).filter(t->t.getLabel().equals("Contracts")).findFirst().orElseThrow();
            tabs.setSelectedTab(contractTab);
            children(dialog).filter(Grid.class::isInstance).map(Grid.class::cast).forEach(grid->{
                grid.getDataProvider().fetch(new Query<>()).filter(ContractDetailsPanel.Detail.class::isInstance)
                        .forEach(row->details.add((ContractDetailsPanel.Detail)row));
            });
            assertThat(details).contains(new ContractDetailsPanel.Detail("Weekly wage","£4,100"),
                    new ContractDetailsPanel.Detail("Club","Walsall"),
                    new ContractDetailsPanel.Detail("Contract end","2038-06-30"),
                    new ContractDetailsPanel.Detail("Transfer date","2036-06-16"),
                    new ContractDetailsPanel.Detail("Promotion Wage Rise","45%"));
            dialog.close();
        } finally {UI.setCurrent(null);}
        var report=new LinkedHashMap<String,Object>();
        report.put("database","actual application file database, read-only");report.put("players",jdbc.queryForObject("SELECT COUNT(*) FROM players",Long.class));
        report.put("staff",jdbc.queryForObject("SELECT COUNT(*) FROM staff",Long.class));report.put("clubs",jdbc.queryForObject("SELECT COUNT(*) FROM clubs",Long.class));
        report.put("canonicalClubs",jdbc.queryForObject("SELECT COUNT(*) FROM club_records",Long.class));
        report.put("nativeClubKinds",jdbc.queryForList("SELECT kind,COUNT(*) AS records,SUM(CASE WHEN name IS NULL THEN 1 ELSE 0 END) AS unnamed FROM club_records GROUP BY kind"));
        report.put("nativeClubsWithoutViews",2867);report.put("allClubViewsMatchNativeIdentity",true);report.put("contractNameJoinDoesNotMultiplyRows",true);
        report.put("nativeClubMetadata",jdbc.queryForList("SELECT meta_key,meta_value FROM load_metadata WHERE meta_key LIKE 'native_club%'"));
        report.put("contractsBySource",jdbc.queryForList("SELECT source,COUNT(*) AS contracts FROM player_contracts GROUP BY source"));
        report.put("futureByOwner",jdbc.queryForList("SELECT CASE WHEN player_id IS NULL THEN 'STAFF' ELSE 'PLAYER' END AS owner_kind,COUNT(*) AS contracts FROM player_contracts WHERE source='FUTURE_EMPLOYMENT' GROUP BY owner_kind"));
        report.put("uniqueFuturePersonOffers",jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT owner_uid,offer_index FROM player_contracts WHERE source='FUTURE_EMPLOYMENT' GROUP BY owner_uid,offer_index)",Long.class));
        report.put("futureStaff",jdbc.queryForList("SELECT c.owner_uid,c.offer_index,s.name,s.record_address,EXISTS(SELECT 1 FROM players p WHERE p.unique_id=c.owner_uid) AS also_player FROM player_contracts c JOIN staff s ON s.id=c.staff_id WHERE c.source='FUTURE_EMPLOYMENT'"));
        report.put("futureMetadata",jdbc.queryForList("SELECT meta_key,meta_value FROM load_metadata WHERE meta_key LIKE 'future_contract%'"));
        report.put("futureTermsByGroup",jdbc.queryForList("SELECT t.term_group,COUNT(*) AS terms FROM contract_terms t JOIN player_contracts c ON c.id=t.contract_id WHERE c.source='FUTURE_EMPLOYMENT' GROUP BY t.term_group"));
        report.put("allTerms",jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms",Long.class));
        report.put("sampleUid",uid);report.put("futureOffer",74);report.put("displayedRows",details.stream().map(d->Map.of("label",d.label(),"value",d.value())).toList());
        report.put("limitation","Actual repository and MainView Contracts tab server components exercised; no browser pixels/client rendering proof");
        Files.write(Path.of(System.getProperty("fm26.delivery.database.report")),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
    private static java.util.stream.Stream<Component> children(Component root) {
        return root.getChildren().flatMap(c->java.util.stream.Stream.concat(java.util.stream.Stream.of(c),children(c)));
    }
}
