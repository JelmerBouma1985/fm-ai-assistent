package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.exporter.ContractExporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in: read the existing snapshot only, without FM attachment or an application refresh. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.contract.bonuses.database",matches="true")
class ContractOrdinaryBonusApplicationLiveTest {
    @Test void allStoredOrdinaryBonusesDecodeFromRawBytesAndReachTheContractView() throws Exception {
        String url=System.getProperty("fm26.contract.bonuses.database.url");
        assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        String snapshot=jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class);
        assertThat(snapshot).isEqualTo("25aa9e7e-44b2-422b-946d-ed8cc6443f2a");
        byte[] bytes;
        try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/modern_contract_bonus_labels_20261022.json"))){bytes=in.readNBytes(1024*1024+1);}
        assertThat(bytes.length).isLessThanOrEqualTo(1024*1024);
        var labels=new TreeMap<Integer,String>();
        for(var c:JsonMapper.builder().build().readTree(bytes).get("labelCases")){
            int code=c.get("code").asInt();if(code>=32&&code<=39)labels.put(code,c.get("labels").get(0).get("variants").get(0).get("text").asString());
        }
        assertThat(labels).hasSize(8);
        var counts=new TreeMap<Integer,Integer>();int[] total={0},shown={0};
        String sql="SELECT c.owner_uid,c.module_sha256,c.player_id,c.staff_id,p.unique_id AS player_uid,s.unique_id AS staff_uid,"
                +"t.ordinal,t.raw_hex,t.value_raw,t.condition_raw,t.code_raw FROM contract_terms t JOIN player_contracts c ON c.id=t.contract_id "
                +"LEFT JOIN players p ON p.id=c.player_id LEFT JOIN staff s ON s.id=c.staff_id "
                +"WHERE c.kind='FULL' AND t.term_group='FULL_68' AND t.code_raw BETWEEN 32 AND 39 ORDER BY c.id,t.ordinal";
        jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler)r->{
            assertThat(r.getString("module_sha256")).isEqualTo(ContractExporter.MODULE_SHA256);
            boolean player=r.getObject("player_id")!=null,staff=r.getObject("staff_id")!=null;
            assertThat(player^staff).isTrue();long uid=r.getLong("owner_uid");
            assertThat(Long.parseLong(r.getString(player?"player_uid":"staff_uid"))).isEqualTo(uid);
            byte[] raw=HexFormat.of().parseHex(r.getString("raw_hex"));assertThat(raw).hasSize(8);
            var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int code=Byte.toUnsignedInt(raw[6]);
            assertThat(r.getInt("code_raw")).isEqualTo(code);
            assertThat(r.getLong("value_raw")).isEqualTo((long)b.getInt(0));
            assertThat(r.getInt("condition_raw")).isEqualTo((int)b.getShort(4));
            var term=new ContractSnapshot.Term("FULL_68",r.getInt("ordinal"),r.getString("raw_hex"),(long)b.getInt(0),(int)b.getShort(4),code);
            var contract=new ContractSnapshot("FULL",-1,uid,null,null,null,null,null,null,null,null,null,null,null,null,null,
                    null,null,null,null,List.of(term),null,null);
            var view=ContractDetailsPanel.details(contract,n->"RAW "+n,false);
            if(b.getInt(0)>=0){assertThat(view).containsExactly(new ContractDetailsPanel.Detail(labels.get(code),"RAW "+b.getInt(0)));shown[0]++;}
            else assertThat(view).isEmpty();
            counts.merge(code,1,Integer::sum);total[0]++;
        });
        assertThat(total[0]).isPositive();assertThat(shown[0]).isPositive();
        var populations=new TreeMap<String,Integer>();
        for(var p:Map.of("players",44467,"staff",25059,"player_contracts",59533,"contract_terms",188254).entrySet()){
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM "+p.getKey(),Integer.class);assertThat(count).isEqualTo(p.getValue());populations.put(p.getKey(),count);
        }
        var report=new LinkedHashMap<String,Object>();report.put("snapshot",snapshot);report.put("rawTermsChecked",total[0]);
        report.put("nonnegativeTermsShown",shown[0]);report.put("codeCounts",counts);report.put("populations",populations);
        report.put("scope","Read-only actual stored raw bytes/owner IDs -> current contract view model; not browser or native UI invocation");
        String output=System.getProperty("fm26.contract.bonuses.database.report");
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
