package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicitly enabled read-only existing snapshot check. Never refreshes or attaches to FM. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.tax.database",matches="true")
class ClubTaxBreakdownApplicationLiveTest {
    @Test void existingSnapshotTaxValuesMatchNativeCapturesWithoutReplacingStoredHistory()throws Exception {
        String url=System.getProperty("fm26.tax.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        String snapshot=jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class);
        assertThat(snapshot).isEqualTo("25aa9e7e-44b2-422b-946d-ed8cc6443f2a");
        var original=ClubFinancialLedgerCaptureTest.financeRows();var histories=ClubFinancialHistoryCaptureTest.expectedPayloads();
        var digest=MessageDigest.getInstance("SHA-256");int profiles=0,values=0,historyProfiles=0;var heerenveen=new LinkedHashMap<String,List<Integer>>();
        var rows=jdbc.queryForList("SELECT unique_id,module_sha256,financial_ledger_hex,financial_ledger_clock,financial_history_hex FROM club_records ORDER BY unique_id");
        assertThat(rows).hasSize(26839);
        for(var row:rows){long uid=((Number)row.get("UNIQUE_ID")).longValue();String raw=(String)row.get("FINANCIAL_LEDGER_HEX"),history=(String)row.get("FINANCIAL_HISTORY_HEX"),sha=(String)row.get("MODULE_SHA256");
            Integer clock=row.get("FINANCIAL_LEDGER_CLOCK")==null?null:((Number)row.get("FINANCIAL_LEDGER_CLOCK")).intValue();
            digest.update((uid+"\t"+sha+"\t"+clock+"\t"+raw+"\t"+history+"\n").getBytes(StandardCharsets.US_ASCII));
            assertThat(history).isEqualTo(histories.get(uid));if(history!=null)historyProfiles++;
            if(raw==null){assertThat(original).doesNotContainKey(uid);continue;}
            var p=ClubFinancialLedgerLayout.values(raw,clock,sha).orElseThrow();profiles++;
            var nativeBody=ByteBuffer.wrap(HexFormat.of().parseHex(original.get(uid)[5])).order(ByteOrder.LITTLE_ENDIAN);
            int[] blocks={0x250,0x368,0x480,0x598};for(int i=0;i<4;i++){int sum=0;for(var t:ClubFinancialLedgerLayout.TaxType.values()){
                int v=nativeBody.getInt(blocks[i]+0x58+t.code()*4);assertThat(p.periods().get(i).taxAmount(t)).isEqualTo(v);sum+=v;values++;}
                assertThat(p.periods().get(i).amount(ClubFinancialLedgerLayout.Category.TAX)).isEqualTo(sum);}
            if(uid==1036)for(var t:ClubFinancialLedgerLayout.TaxType.values())heerenveen.put(t.code()+":"+t.label(),p.periods().stream().map(period->period.taxAmount(t)).toList());
        }
        assertThat(profiles).isEqualTo(680);assertThat(values).isEqualTo(51680);assertThat(historyProfiles).isEqualTo(680);
        assertThat(heerenveen.get("4:VAT").getFirst()).isEqualTo(176312);
        var preserved=new TreeMap<String,Integer>();for(var p:Map.of("players",44467,"staff",25059,"player_contracts",59533,"contract_terms",188254,"competitions",6619,"competition_name_records",60).entrySet()){
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM "+p.getKey(),Integer.class);assertThat(count).isEqualTo(p.getValue());preserved.put(p.getKey(),count);}
        var report=new LinkedHashMap<String,Object>();report.put("snapshot",snapshot);report.put("profiles",profiles);report.put("taxValues",values);report.put("allTaxValuesMatchIndependentCapture",true);
        report.put("all680HistoryPayloadsMatchIndependentCapture",true);report.put("clubLedgerHistoryDigest",HexFormat.of().formatHex(digest.digest()));report.put("preserved",preserved);report.put("heerenveen",heerenveen);
        String output=System.getProperty("fm26.tax.database.report");if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
