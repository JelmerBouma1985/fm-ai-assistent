package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in realprocess and read-only database checks; ordinary tests never attach to FM. */
@Tag("fm26-live")
class ClubFinancialHistoryApplicationLiveTest {
    @Test @EnabledIfSystemProperty(named="fm26.history.live",matches="true")
    void actualProductionExportMatchesEveryIndependentHistoryByteAndPreviouslyDeliveredCollections()throws Exception {
        int pid=Integer.getInteger("fm26.history.pid",0);assertThat(pid).isEqualTo(22268);
        var expected=ClubFinancialHistoryCaptureTest.expectedPayloads();long start=System.nanoTime();
        var result=new ClubExporter().exportAllClubs(pid,0x238BDD,null);long elapsed=(System.nanoTime()-start)/1_000_000;int profiles=0,members=0,absent=0;
        assertThat(result.nativeRecords()).hasSize(26839);
        var vectors=ClubFinancialCompetitionCaptureTest.expectedPayloads("financial_competition_vectors_population_fm22268_20261027.csv","financial_competition_keys_population_fm22268_20261027.csv");
        for(var r:result.nativeRecords()){assertThat(r.financialHistoryHex()).isEqualTo(expected.get(r.uniqueId()));assertThat(r.financialCompetitionsHex()).isEqualTo(vectors.get(r.uniqueId()));if(r.financialHistoryHex()==null)continue;
            profiles++;var p=ClubFinancialHistoryLayout.current().decode(HexFormat.of().parseHex(r.financialHistoryHex()),r.financialLedgerClock());members+=p.summary().count();if(p.expenditure()==null)absent++;else members+=p.expenditure().count();}
        assertThat(profiles).isEqualTo(680);assertThat(members).isEqualTo(28834);assertThat(absent).isEqualTo(425);assertThat(result.competitionNames()).hasSize(60);
        report("fm26.history.live.report",Map.of("pid",pid,"profiles",profiles,"members",members,"absentOptional",absent,"allIndependentHistoryBytesMatch",true,"all680CompetitionPayloadsUnchanged",true,"elapsedMillis",elapsed));
    }
    @Test @EnabledIfSystemProperty(named="fm26.history.database",matches="true")
    void actualNormalSnapshotPreservesEveryHistoryPayloadAndPreviousEntityPopulations()throws Exception {
        String url=System.getProperty("fm26.history.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var expected=ClubFinancialHistoryCaptureTest.expectedPayloads();int count=0,profiles=0,members=0;
        for(var r:jdbc.queryForList("SELECT unique_id,module_sha256,financial_history_hex,financial_ledger_clock FROM club_records")){count++;String hex=(String)r.get("FINANCIAL_HISTORY_HEX");assertThat(hex).isEqualTo(expected.get(((Number)r.get("UNIQUE_ID")).longValue()));
            if(hex!=null){profiles++;var p=ClubFinancialHistoryLayout.values(hex,((Number)r.get("FINANCIAL_LEDGER_CLOCK")).intValue(),(String)r.get("MODULE_SHA256")).orElseThrow();members+=p.summary().count()+(p.expenditure()==null?0:p.expenditure().count());}}
        assertThat(count).isEqualTo(26839);assertThat(profiles).isEqualTo(680);assertThat(members).isEqualTo(28834);
        var preserved=new LinkedHashMap<String,Integer>();for(var p:Map.of("players",44467,"staff",25059,"player_contracts",59533,"contract_terms",188254,"competitions",6619,"competition_name_records",60).entrySet()){
            int n=jdbc.queryForObject("SELECT COUNT(*) FROM "+p.getKey(),Integer.class);assertThat(n).isEqualTo(p.getValue());preserved.put(p.getKey(),n);}
        String snapshot=jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class);
        report("fm26.history.database.report",Map.of("profiles",profiles,"members",members,"allIndependentHistoryBytesMatch",true,"preserved",preserved,"snapshot",snapshot));
    }
    private static void report(String key,Map<String,Object> value)throws Exception {String name=System.getProperty(key);if(name!=null)Files.write(Path.of(name),JsonMapper.builder().build().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}
}
