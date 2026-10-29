package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.memory.ClubFinanceLayout;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
class ClubCurrentWagesApplicationLiveTest {
    private record Expected(long uid,long address,long wages) { }

    @Test @EnabledIfSystemProperty(named="fm26.payroll.live",matches="true")
    void all680TypedOwnersAppearInTheRealExporterWithTheIndependentNativeValue()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isEqualTo(6624);long started=System.nanoTime();
        var expected=fixture();var result=new ClubExporter().exportAllClubs(pid,0x238BDD,null);
        assertThat(result.identityProfile()).isEqualTo(ClubFinanceLayout.MODULE_SHA256);
        var covered=new HashSet<Long>();int known=0,unknown=0;
        for(var row:result.rows()) {
            long uid=((Number)row.get("uniqueId")).longValue();var e=expected.get(uid);Object value=row.get("currentWagesIncludingBonuses");
            if(e==null){assertThat(value).isNull();unknown++;}
            else {assertThat(((Number)row.get("sourceAddress")).longValue()).isEqualTo(e.address);assertThat(value).isEqualTo(e.wages);covered.add(uid);known++;}
        }
        assertThat(covered).hasSize(680).containsExactlyInAnyOrderElementsOf(expected.keySet());
        report("fm26.payroll.live.report",Map.of("pid",pid,"elapsedMillis",(System.nanoTime()-started)/1_000_000,
                "coveredFullOwners",covered.size(),"knownViews",known,"unknownBaseViews",unknown,"totalViews",result.rows().size(),
                "sourceProfile",result.identityProfile(),"allCapturedWagesEqual",true));
    }

    @Test @EnabledIfSystemProperty(named="fm26.payroll.database",matches="true")
    void actualAtomicDatabaseAndClubDetailRenderAllKnownWagesAndLeaveBaseValuesEmpty()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("6624");
        var expected=fixture();var covered=new HashSet<Long>();int known=0,unknown=0;var examples=new ArrayList<Map<String,Object>>();
        var rows=jdbc.queryForList("SELECT * FROM clubs");
        for(var row:rows) {
            long uid=((Number)row.get("UNIQUE_ID")).longValue();var e=expected.get(uid);Object value=row.get("CURRENT_WAGES_INCLUDING_BONUSES");
            if(e==null){assertThat(value).isNull();unknown++;}else {assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(e.address);assertThat(value).isEqualTo(e.wages);covered.add(uid);known++;}
            if(uid==1036||uid==630||uid==731||uid==58) {
                var exportRow=new HashMap<String,Object>();
                for(String f:ClubExporter.FIELD_NAMES)exportRow.put(f,row.get(f.replaceAll("([a-z0-9])([A-Z])","$1_$2").toUpperCase(Locale.ROOT)));
                var club=ClubEntity.fromExportRow(exportRow);var view=MainViewTest.view(new com.github.fmaiassistent.repository.ContractRepository(jdbc));
                var method=MainView.class.getDeclaredMethod("opponentClubView",ClubEntity.class,String.class);method.setAccessible(true);
                var component=(com.vaadin.flow.component.Component)method.invoke(view,club,club.getName());
                String shown=field(component,"Current wages incl. bonuses (per week)");
                assertThat(shown).isEqualTo(e==null?"":"£"+String.format(Locale.US,"%,d",e.wages));
                if(uid==1036)assertThat(shown).isEqualTo("£1,763,603");
                if(examples.stream().noneMatch(m->((Number)m.get("uid")).longValue()==uid))examples.add(Map.of("uid",uid,"shown",shown));
            }
        }
        assertThat(covered).hasSize(680);assertThat(known).isEqualTo(1424);assertThat(unknown).isEqualTo(30186);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE uefa_coefficient_hex IS NOT NULL",Integer.class)).isEqualTo(1903);
        report("fm26.payroll.database.report",Map.of("pid",6624,"snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class),
                "coveredFullOwners",covered.size(),"knownViews",known,"unknownBaseViews",unknown,"allCapturedWagesEqual",true,"screens",examples));
    }

    private static Map<Long,Expected> fixture()throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_finance_payroll_population_20261026.csv"))){bytes=in.readNBytes(1_048_577);}
        assertThat(bytes.length).isLessThanOrEqualTo(1_048_576);var out=new HashMap<Long,Expected>();
        for(String l:new String(bytes,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).toList()) {
            var r=l.split(",",-1);long uid=Long.parseLong(r[1]);var b=ByteBuffer.wrap(HexFormat.of().parseHex(r[6])).order(ByteOrder.LITTLE_ENDIAN);
            out.put(uid,new Expected(uid,Long.parseUnsignedLong(r[2],16),b.getInt(0x81C-0x7C0)));
        }
        assertThat(out).hasSize(680);return out;
    }
    private static String field(com.vaadin.flow.component.Component c,String label) {
        return descendants(c).filter(v->v.getChildren().anyMatch(n->n instanceof com.vaadin.flow.component.html.Span s&&s.getText().equals(label)))
                .flatMap(v->v.getChildren().filter(n->n instanceof com.vaadin.flow.component.html.Span s&&s.hasClassName("detail-value")))
                .map(v->((com.vaadin.flow.component.html.Span)v).getText()).findFirst().orElseThrow();
    }
    private static java.util.stream.Stream<com.vaadin.flow.component.Component> descendants(com.vaadin.flow.component.Component c){return c.getChildren().flatMap(v->java.util.stream.Stream.concat(java.util.stream.Stream.of(v),descendants(v)));}
    private static void report(String property,Object value)throws Exception {String out=System.getProperty(property);assertThat(out).isNotBlank();Files.write(Path.of(out),JsonMapper.builder().build().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}
}
