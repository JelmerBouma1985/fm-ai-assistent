package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.repository.ContractRepository;
import com.github.fmaiassistent.memory.AwardNameLayout;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.awards.database",matches="true")
class AwardNameApplicationLiveTest {
    @Test void all43StoredNamesAnd1154AwardRowsMatchIndependentEvidenceAndRenderByNativeUid()throws Exception {
        String url=System.getProperty("fm26.awards.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var repo=new ContractRepository(jdbc);
        var expected=new HashMap<Long,com.github.fmaiassistent.domain.NativeAwardNameSnapshot>();for(var r:AwardNameCaptureTest.capturedRows())for(var n:AwardNameCaptureTest.names(r))expected.put(n.uniqueId(),n);
        var names=new HashMap<Long,String>();var rows=jdbc.queryForList("SELECT * FROM award_name_records");assertThat(rows).hasSize(43);
        for(var r:rows){long uid=((Number)r.get("UNIQUE_ID")).longValue();var n=expected.get(uid);assertThat(n).isNotNull();
            assertThat(r.get("MODULE_SHA256")).isEqualTo(ContractExporter.MODULE_SHA256);assertThat(r.get("RECORD_INDEX")).isEqualTo(n.recordIndex());
            assertThat(r.get("NAME_HEX")).isEqualTo(n.nameHex());assertThat(r.get("SHORT_NAME_HEX")).isEqualTo(n.shortNameHex());assertThat(r.get("NAME")).isEqualTo(n.name());assertThat(r.get("SHORT_NAME")).isEqualTo(n.shortName());names.put(uid,n.name());}
        int terms=0;for(var r:jdbc.queryForList("SELECT t.*,c.module_sha256 FROM contract_terms t JOIN player_contracts c ON c.id=t.contract_id WHERE t.reference_kind='AWARD'")){
            assertThat(r.get("TERM_GROUP")).isEqualTo("FULL_98");assertThat(r.get("MODULE_SHA256")).isEqualTo(ContractExporter.MODULE_SHA256);
            long uid=((Number)r.get("REFERENCE_UID")).longValue();assertThat(names).containsKey(uid);assertThat(AwardNameLayout.current().bonusAmount((String)r.get("RAW_HEX"))).isPresent();terms++;}
        assertThat(terms).isEqualTo(1154);var selected=new LinkedHashMap<String,Object>();
        for(long uid:List.of(2002079297L,2002070983L)){
            long id=jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?",Long.class,Long.toString(uid));
            var contracts=repo.findPlayerContracts(id,uid);var found=new ArrayList<ContractDetailsPanel.Detail>();
            for(var c:contracts)found.addAll(ContractDetailsPanel.details(c.snapshot(),n->"RAW "+n,true,c.awardNames()));
            if(uid==2002079297L)assertThat(found).contains(new ContractDetailsPanel.Detail("World Footballer of the Year","RAW 190421"),new ContractDetailsPanel.Detail("World Player of the Year","RAW 152584"),new ContractDetailsPanel.Detail("World Golden Ball","RAW 190421"));
            else assertThat(found).contains(new ContractDetailsPanel.Detail("UEFA Champions League Player of the Season","RAW 74543"),new ContractDetailsPanel.Detail("UEFA Champions League Dream Team","RAW 67696"));
            selected.put(Long.toString(uid),found);
        }
        var populations=new TreeMap<String,Integer>();for(var p:Map.of("players",44467,"staff",25059,"player_contracts",59533,"contract_terms",188254,"club_records",26839).entrySet()){
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM "+p.getKey(),Integer.class);assertThat(count).isEqualTo(p.getValue());populations.put(p.getKey(),count);}
        var report=new LinkedHashMap<String,Object>();report.put("snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class));
        report.put("awardNames",43);report.put("awardRows",terms);report.put("allRawNamesMatchIndependentCapture",true);report.put("populations",populations);report.put("selectedViews",selected);
        String out=System.getProperty("fm26.awards.database.report");if(out!=null)Files.write(Path.of(out),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
