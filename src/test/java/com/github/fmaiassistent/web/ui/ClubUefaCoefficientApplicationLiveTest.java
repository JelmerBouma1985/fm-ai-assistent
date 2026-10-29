package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.memory.ClubUefaCoefficientLayout;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
class ClubUefaCoefficientApplicationLiveTest {
    @Test
    @EnabledIfSystemProperty(named="fm26.uefa.live",matches="true")
    void normalSupportedExporterMatchesTheEntireIndependentPopulation()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isEqualTo(6624);long started=System.nanoTime();
        var export=new ClubExporter().exportAllClubs(pid,0x238BDD,null);var expected=fixture();
        assertThat(export.identityProfile()).isEqualTo(ClubUefaCoefficientLayout.MODULE_SHA256);
        assertThat(export.nativeRecords()).hasSize(26839);int profiles=0;
        var viewIds=new HashSet<Long>();export.rows().forEach(r->viewIds.add(((Number)r.get("uniqueId")).longValue()));
        var onlyCanonical=new ArrayList<Map<String,Object>>();
        for(var club:export.nativeRecords()) {
            var raw=expected.get(club.uniqueId());
            if(raw==null) {assertThat(club.kind()).isEqualTo("LEAGUE_BODY_CLUB");assertThat(club.uefaCoefficientHex()).isNull();continue;}
            assertThat(club.sourceAddress()).isEqualTo(Long.parseUnsignedLong(raw[2],16));
            assertThat(club.uefaCoefficientHex()).isEqualTo(raw[6].isEmpty()?null:raw[6]);
            if(club.uefaCoefficientHex()!=null) {
                profiles++;assertThat(club.uefaRuleVersion()).isEqualTo(1639);
                if(!viewIds.contains(club.uniqueId()))onlyCanonical.add(Map.of("uid",club.uniqueId(),"name",Objects.toString(club.name(),"")));
            }else assertThat(club.uefaRuleVersion()).isNull();
        }
        assertThat(profiles).isEqualTo(1903);assertThat(onlyCanonical).hasSize(6);
        report("fm26.uefa.live.report",Map.of("pid",pid,"elapsedMillis",(System.nanoTime()-started)/1_000_000,
                "nativeClubs",export.nativeRecords().size(),"views",export.rows().size(),"profiles",profiles,"canonicalOnly",onlyCanonical,
                "allRaw76BytesEqual",true,"ruleVersion",1639));
    }

    @Test
    @EnabledIfSystemProperty(named="fm26.uefa.delivery.database",matches="true")
    void actualSnapshotContainsEveryRawProfileAndThePanelShowsIndependentNativeValues()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("6624");
        var expected=fixture();int profiles=0;var samples=new ArrayList<Map<String,Object>>();
        var stored=jdbc.queryForList("SELECT unique_id,source_address,module_sha256,uefa_coefficient_hex,uefa_rule_version FROM club_records");
        assertThat(stored).hasSize(26839);
        for(var row:stored) {
            long uid=((Number)row.get("UNIQUE_ID")).longValue();var raw=expected.get(uid);
            String payload=(String)row.get("UEFA_COEFFICIENT_HEX");
            if(raw==null||raw[6].isEmpty()){assertThat(payload).isNull();assertThat(row.get("UEFA_RULE_VERSION")).isNull();continue;}
            assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(Long.parseUnsignedLong(raw[2],16));
            assertThat(payload).isEqualTo(raw[6]);assertThat(row.get("MODULE_SHA256")).isEqualTo(ClubUefaCoefficientLayout.MODULE_SHA256);
            int rule=((Number)row.get("UEFA_RULE_VERSION")).intValue();assertThat(rule).isEqualTo(1639);profiles++;
            var values=ClubUefaCoefficientLayout.values(payload,rule,(String)row.get("MODULE_SHA256")).orElseThrow();
            if(uid==1036||uid==630||uid==505||uid==102) {
                String text=text(new ClubCoefficientsPanel(values));
                assertThat(text).contains("Five-year coefficient",ClubCoefficientsPanel.number(values.fiveYearCoefficient()),
                        "Ten-year coefficient",ClubCoefficientsPanel.number(values.tenYearCoefficient()),"Current-season points",
                        ClubCoefficientsPanel.number(values.currentSeasonPoints()));
                if(uid==1036){assertThat(text).contains("116.5","169.25","16","28","37");}
                if(uid==102){assertThat(values.fiveYearCoefficient()).isEqualByComparingTo("17.369");}
                samples.add(Map.of("uid",uid,"fiveYear",values.fiveYearCoefficient(),"tenYear",values.tenYearCoefficient(),
                        "current",values.currentSeasonPoints(),"rank5",Objects.toString(values.fiveYearRank(),"null"),"rank10",Objects.toString(values.tenYearRank(),"null")));
            }
        }
        assertThat(profiles).isEqualTo(1903);
        int canonicalOnly=jdbc.queryForObject("SELECT COUNT(*) FROM club_records r WHERE r.uefa_coefficient_hex IS NOT NULL AND NOT EXISTS (SELECT 1 FROM clubs c WHERE c.unique_id=r.unique_id)",Integer.class);
        assertThat(canonicalOnly).isEqualTo(6);
        report("fm26.uefa.delivery.database.report",Map.of("pid",6624,"profiles",profiles,"canonicalOnly",canonicalOnly,
                "rawPopulationEqual",true,"samples",samples,"snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class),
                "limitation","Actual database/raw population and Vaadin component checks; no rendered browser pixels or new FM screenshot for these coefficients."));
    }

    private static Map<Long,String[]> fixture()throws Exception {
        byte[] raw;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_uefa_coefficients_population_live_20261026.csv"))){raw=in.readNBytes(8*1024*1024+1);}
        assertThat(raw.length).isLessThanOrEqualTo(8*1024*1024);var out=new HashMap<Long,String[]>();
        new String(raw,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(l->l.split(",",-1)).forEach(r->out.put(Long.parseLong(r[1]),r));return out;
    }
    private static String text(com.vaadin.flow.component.Component c){return c.getElement().getText()+c.getChildren().map(ClubUefaCoefficientApplicationLiveTest::text).reduce("",(a,b)->a+" "+b);}
    private static void report(String property,Object data)throws Exception {
        String out=System.getProperty(property);assertThat(out).isNotBlank();Files.write(Path.of(out),JsonMapper.builder().build().writeValueAsBytes(data),StandardOpenOption.CREATE_NEW);
    }
}
