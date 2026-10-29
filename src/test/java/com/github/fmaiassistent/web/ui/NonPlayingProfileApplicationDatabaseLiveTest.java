package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.*;
import com.github.fmaiassistent.memory.NonPlayingProfileLayout;
import com.github.fmaiassistent.repository.ContractRepository;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
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
@EnabledIfSystemProperty(named="fm26.nonplaying.database",matches="true")
class NonPlayingProfileApplicationDatabaseLiveTest {
    @Test void actualNormalSnapshotPreservesAllCapturedCombinedBytesAndShowsBothOwnerKinds()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE non_playing_profile_hex IS NOT NULL",Integer.class)).isEqualTo(1408);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE non_playing_profile_hex IS NOT NULL",Integer.class)).isEqualTo(25059);
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/all_combined_profiles_live_20261025.csv"))){bytes=in.readNBytes(1048577);}assertThat(bytes.length).isLessThanOrEqualTo(1048576);
        var expected=new HashMap<Long,String>();new String(bytes,java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).forEach(l->{var c=l.split(",",-1);expected.put(Long.parseLong(c[1]),c[7].substring(16,120));});
        var stored=jdbc.queryForList("SELECT unique_id,non_playing_profile_hex,non_playing_profile_sha256 FROM players WHERE non_playing_profile_hex IS NOT NULL");
        for(var r:stored){assertThat(r.get("NON_PLAYING_PROFILE_HEX")).isEqualTo(expected.get(((Number)r.get("UNIQUE_ID")).longValue()));assertThat(r.get("NON_PLAYING_PROFILE_SHA256")).isEqualTo(NonPlayingProfileLayout.MODULE_SHA256);}
        var reports=new ArrayList<Map<String,Object>>();
        for(var owner:List.of(Map.entry("players",222138L),Map.entry("staff",47L),Map.entry("staff",2002076190L))) {
            String table=owner.getKey();long uid=owner.getValue();
            var row=jdbc.queryForMap("SELECT * FROM "+table+" WHERE unique_id=?",uid);var values=new HashMap<String,Object>();row.forEach((k,v)->values.put(k.toLowerCase(Locale.ROOT),v));
            if(table.equals("players")){values.put("index",values.get("player_index"));values.put("record",values.get("record_address"));}
            Object entity=table.equals("players")?PlayerEntity.fromExportRow(values):StaffEntity.fromExportRow(values);
            var ui=new UI();ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));UI.setCurrent(ui);
            try {
                var view=MainViewTest.view(new ContractRepository(jdbc));var open=MainView.class.getDeclaredMethod(table.equals("players")?"openPlayerDetailsDialog":"openStaffDetailsDialog",entity.getClass());open.setAccessible(true);open.invoke(view,entity);
                ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
                var dialog=descendants(ui).filter(Dialog.class::isInstance).map(Dialog.class::cast).filter(Dialog::isOpened).findFirst().orElseThrow();
                var tabs=descendants(dialog).filter(Tabs.class::isInstance).map(Tabs.class::cast).findFirst().orElseThrow();
                tabs.setSelectedTab(tabs.getChildren().filter(Tab.class::isInstance).map(Tab.class::cast).filter(t->t.getLabel().equals("Management profile")).findFirst().orElseThrow());
                var grid=descendants(dialog).filter(Grid.class::isInstance).map(c->(Grid<?>)c).findFirst().orElseThrow();
                var shown=grid.getGenericDataView().getItems().map(v->(NonPlayingProfileLayout.AttributeValue)v).toList();assertThat(shown).hasSize(50);
                var ecc=shown.stream().filter(v->v.name().equals("Eccentricity")).findFirst().orElseThrow();
                byte[] nativeBytes=HexFormat.of().parseHex((String)row.get("NON_PLAYING_PROFILE_HEX"));assertThat(ecc.nativeValue()).isEqualTo((int)nativeBytes[48]);assertThat(ecc.scale()).isEqualTo(100);
                reports.add(Map.of("ownerTable",table,"uid",uid,"name",row.get("NAME"),"shownNativeAttributes",shown.size(),"eccentricityRaw",ecc.nativeValue(),"scale",ecc.scale()));dialog.close();
            }finally{UI.setCurrent(null);}
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE unique_id=222138",Integer.class)).isZero();
        byte[] human;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/human_nonplaying_profiles_verified_20261025.json"))){human=in.readNBytes(65537);}assertThat(human.length).isLessThanOrEqualTo(65536);
        for(var sample:JsonMapper.builder().build().readTree(human).get("samples"))assertThat(jdbc.queryForObject("SELECT non_playing_profile_hex FROM staff WHERE unique_id=?",String.class,sample.get("uid").asLong())).isEqualTo(sample.get("profileRaw52").asString());
        var report=new LinkedHashMap<String,Object>();report.put("source","Actual normal snapshot/read-only H2/MainView server components");report.put("all1408CombinedRawPayloadsMatch",true);report.put("allCapturedHumanPayloadsMatch",true);report.put("combinedProfiles",1408);report.put("staffProfiles",25059);report.put("humanProfiles",2);report.put("screens",reports);
        for(String table:List.of("players","staff","clubs","club_records","player_contracts","contract_terms"))report.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        report.put("limitation","Native storage values, not claimed identical to all in-game UI numbers; browser pixels and reload/restart stability unverified.");
        Files.write(Path.of(System.getProperty("fm26.nonplaying.database.report")),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
    private static java.util.stream.Stream<Component> descendants(Component c){return c.getChildren().flatMap(v->java.util.stream.Stream.concat(java.util.stream.Stream.of(v),descendants(v)));}
}
