package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.ClubFinancialLedgerLayout;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit PID620 captured-save opt-in, never enabled in deterministic/default gates. */
@Tag("fm26-live")
class ClubFinancialLedgerApplicationLiveTest {
    @Test @EnabledIfSystemProperty(named="fm26.financial.ledger.live",matches="true")
    void actualProductionExportMatches680IndependentlyCapturedOwnFinancialRecords()throws Exception {
        var expected=ClubFinancialLedgerCaptureTest.financeRows();long start=System.nanoTime();
        var result=new ClubExporter().exportAllClubs(620,0x238BDD,null);assertThat(result.nativeRecords()).hasSize(26839);
        assertThat(result.identityProfile()).isEqualTo(ClubFinancialLedgerLayout.MODULE_SHA256);int profiles=0;
        for(var r:result.nativeRecords()){var e=expected.get(r.uniqueId());if(e==null){assertThat(r.financialLedgerHex()).isNull();assertThat(r.financialLedgerClock()).isNull();}
            else{profiles++;assertThat(r.sourceAddress()).isEqualTo(Long.parseUnsignedLong(e[2],16));assertThat(r.financialLedgerHex()).isEqualTo(ClubFinancialLedgerCaptureTest.payload(e));assertThat(r.financialLedgerClock()).isEqualTo(ClubFinancialLedgerCaptureTest.CLOCK);}}
        assertThat(profiles).isEqualTo(680);report("fm26.financial.ledger.live.report",Map.of("pid",620,"canonicalClubs",26839,"profiles",profiles,"allRawProfilesMatch",true,"elapsedMillis",(System.nanoTime()-start)/1_000_000));
    }
    @Test @EnabledIfSystemProperty(named="fm26.financial.ledger.database",matches="true")
    void actualNormalSnapshotContains680RawProfilesAndAllFourHeerenveenPeriods()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var expected=ClubFinancialLedgerCaptureTest.financeRows();int profiles=0,records=0;
        for(var row:jdbc.queryForList("SELECT unique_id,module_sha256,financial_ledger_hex,financial_ledger_clock FROM club_records")){records++;var e=expected.get(((Number)row.get("UNIQUE_ID")).longValue());
            if(e==null){assertThat(row.get("FINANCIAL_LEDGER_HEX")).isNull();assertThat(row.get("FINANCIAL_LEDGER_CLOCK")).isNull();continue;}
            profiles++;assertThat(row.get("FINANCIAL_LEDGER_HEX")).isEqualTo(ClubFinancialLedgerCaptureTest.payload(e));
            assertThat(((Number)row.get("FINANCIAL_LEDGER_CLOCK")).intValue()).isEqualTo(ClubFinancialLedgerCaptureTest.CLOCK);
            var p=ClubFinancialLedgerLayout.values((String)row.get("FINANCIAL_LEDGER_HEX"),((Number)row.get("FINANCIAL_LEDGER_CLOCK")).intValue(),(String)row.get("MODULE_SHA256")).orElseThrow();
            if(Long.parseLong(e[1])==1036){assertThat(p.periods().stream().map(ClubFinancialLedgerLayout.Period::totalIncome)).containsExactly(21609067,16364810,369964713,292098446);assertThat(p.periods().get(2).amount(ClubFinancialLedgerLayout.Category.SPONSORSHIP)).isEqualTo(8414566);}
        }
        assertThat(records).isEqualTo(26839);assertThat(profiles).isEqualTo(680);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players",Integer.class)).isEqualTo(44467);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff",Integer.class)).isEqualTo(25059);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isEqualTo(59533);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms",Integer.class)).isEqualTo(188254);
        report("fm26.financial.ledger.database.report",Map.of("profiles",profiles,"canonicalClubs",records,"allRawProfilesMatch",true,"snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class)));
    }
    private static void report(String property,Map<String,Object> data)throws Exception {String p=System.getProperty(property);assertThat(p).isNotBlank();Files.writeString(Path.of(p),new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data),StandardOpenOption.CREATE_NEW);}
}
