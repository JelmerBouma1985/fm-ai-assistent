package com.github.fmaiassistent.web.ui;

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

/** Session-bound fixtures are intentionally explicit: never accept an old heap address after restart. */
@Tag("fm26-live")
class ClubObservedRestartApplicationLiveTest {
    @Test @EnabledIfSystemProperty(named="fm26.restart.clubs.live",matches="true")
    void exporterRebuildsEveryIdentityAndOptionalProfileFromNewProcessRoots()throws Exception {
        assertThat(Integer.getInteger("fm26.live.pid",-1)).isEqualTo(620);long start=System.nanoTime();
        var coefficients=csv("club_uefa_coefficients_population_fm620_20261026.csv");var finances=csv("club_finance_payroll_population_fm620_20261026.csv");
        var result=new ClubExporter().exportAllClubs(620,0x238BDD,null);assertThat(result.identityProfile()).isEqualTo(ClubFinanceLayout.MODULE_SHA256);
        assertThat(result.nativeRecords()).hasSize(26839);int profiles=0;
        for(var r:result.nativeRecords()) {
            var c=coefficients.get(r.uniqueId());
            if(c==null){assertThat(r.kind()).isEqualTo("LEAGUE_BODY_CLUB");assertThat(r.uefaCoefficientHex()).isNull();continue;}
            assertThat(r.sourceAddress()).isEqualTo(address(c[2]));assertThat(r.prefixHex()).isEqualTo(c[3]);
            assertThat(r.uefaCoefficientHex()).isEqualTo(c[6].isEmpty()?null:c[6]);
            if(r.uefaCoefficientHex()!=null){assertThat(r.uefaRuleVersion()).isEqualTo(1639);profiles++;}
        }
        assertThat(profiles).isEqualTo(1903);var financeOwners=new HashSet<Long>();int known=0;
        for(var row:result.rows()) {
            long uid=((Number)row.get("uniqueId")).longValue();var f=finances.get(uid);
            if(f==null)assertThat(row.get("currentWagesIncludingBonuses")).isNull();
            else {assertThat(((Number)row.get("sourceAddress")).longValue()).isEqualTo(address(f[2]));assertThat(row.get("currentWagesIncludingBonuses")).isEqualTo((long)wage(f));financeOwners.add(uid);known++;}
        }
        assertThat(financeOwners).hasSize(680);assertThat(known).isEqualTo(1424);
        report("fm26.restart.clubs.live.report",Map.of("pid",620,"elapsedMillis",(System.nanoTime()-start)/1_000_000,"canonicalClubs",26839,
                "coefficientProfiles",profiles,"fullFinancialOwners",financeOwners.size(),"wageViews",known,"allFreshFramesMatch",true));
    }

    @Test @EnabledIfSystemProperty(named="fm26.restart.clubs.database",matches="true")
    void actualReplacementSnapshotContainsNewAddressesAndFreshRawUnknownBytes()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("620");
        var coefficients=csv("club_uefa_coefficients_population_fm620_20261026.csv");var finances=csv("club_finance_payroll_population_fm620_20261026.csv");
        var canonical=jdbc.queryForList("SELECT unique_id,source_address,prefix_hex,uefa_coefficient_hex,uefa_rule_version FROM club_records");assertThat(canonical).hasSize(26839);int profiles=0;
        for(var row:canonical) {
            long uid=((Number)row.get("UNIQUE_ID")).longValue();var c=coefficients.get(uid);
            if(c==null){assertThat(row.get("UEFA_COEFFICIENT_HEX")).isNull();continue;}
            assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(address(c[2]));assertThat(row.get("PREFIX_HEX")).isEqualTo(c[3]);
            assertThat(row.get("UEFA_COEFFICIENT_HEX")).isEqualTo(c[6].isEmpty()?null:c[6]);
            if(!c[6].isEmpty()){assertThat(((Number)row.get("UEFA_RULE_VERSION")).intValue()).isEqualTo(1639);profiles++;}
        }
        assertThat(profiles).isEqualTo(1903);var owners=new HashSet<Long>();int known=0;
        var views=jdbc.queryForList("SELECT unique_id,source_address,current_wages_including_bonuses FROM clubs");assertThat(views).hasSize(31610);
        for(var row:views) {
            long uid=((Number)row.get("UNIQUE_ID")).longValue();var f=finances.get(uid);
            if(f==null)assertThat(row.get("CURRENT_WAGES_INCLUDING_BONUSES")).isNull();else {
                assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(address(f[2]));assertThat(row.get("CURRENT_WAGES_INCLUDING_BONUSES")).isEqualTo((long)wage(f));owners.add(uid);known++;
            }
        }
        assertThat(owners).hasSize(680);assertThat(known).isEqualTo(1424);
        var counts=new LinkedHashMap<String,Long>();for(String table:List.of("players","staff","player_contracts","contract_terms")) {
            // Explicit table names only, no caller-controlled SQL.
            counts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        }
        assertThat(counts).containsEntry("players",44467L).containsEntry("staff",25059L).containsEntry("player_contracts",59533L).containsEntry("contract_terms",188254L);
        report("fm26.restart.clubs.database.report",Map.of("pid",620,"profiles",profiles,"fullFinancialOwners",owners.size(),"knownWageViews",known,
                "allCurrentFramesMatch",true,"counts",counts,"snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class)));
    }
    private static int wage(String[] r){return ByteBuffer.wrap(HexFormat.of().parseHex(r[6])).order(ByteOrder.LITTLE_ENDIAN).getInt(0x81C-0x7C0);}
    private static long address(String s){return Long.parseUnsignedLong(s,16);}
    private static Map<Long,String[]> csv(String name)throws Exception {byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence",name))){b=in.readNBytes(4194305);}assertThat(b.length).isLessThanOrEqualTo(4194304);var result=new HashMap<Long,String[]>();new String(b,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(l->l.split(",",-1)).forEach(r->result.put(Long.parseLong(r[1]),r));return result;}
    private static void report(String property,Object data)throws Exception {String out=System.getProperty(property);assertThat(out).isNotBlank();Files.write(Path.of(out),JsonMapper.builder().build().writeValueAsBytes(data),StandardOpenOption.CREATE_NEW);}
}
