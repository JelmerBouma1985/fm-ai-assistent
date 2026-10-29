package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.ClubFinancialLoanLayout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-ins only; fixture is independently captured PID620, not a generic live smoke test. */
@Tag("fm26-live")
class ClubFinancialLoanApplicationLiveTest {
    @Test @EnabledIfSystemProperty(named="fm26.financial.loans.live",matches="true")
    void supportedProductionExporterMatchesAllIndependentLoanOwnersMembersAndClock()throws Exception {
        long start=System.nanoTime();var expected=rows();
        var result=new ClubExporter().exportAllClubs(620,0x238BDD,null);
        assertThat(result.identityProfile()).isEqualTo(ClubFinancialLoanLayout.MODULE_SHA256);assertThat(result.nativeRecords()).hasSize(26839);
        int owners=0,records=0,ordinary=0;
        for(var r:result.nativeRecords()) {
            var e=expected.get(r.uniqueId());
            if(e==null){assertThat(r.kind()).isEqualTo("LEAGUE_BODY_CLUB");assertThat(r.financialLoansHex()).isNull();assertThat(r.financialLoanClock()).isNull();continue;}
            ordinary++;assertThat(r.sourceAddress()).isEqualTo(Long.parseUnsignedLong(e[2],16));assertThat(r.prefixHex()).isEqualTo(e[3]);
            assertThat(r.financialLoansHex()).isEqualTo(ClubFinancialLoanCaptureTest.payload(e));assertThat(r.financialLoanClock()).isEqualTo(ClubFinancialLoanCaptureTest.CLOCK);
            var v=ClubFinancialLoanLayout.values(r.financialLoansHex(),r.financialLoanClock(),result.identityProfile()).orElseThrow();
            records+=v.loans().size();if(!v.loans().isEmpty())owners++;
        }
        assertThat(ordinary).isEqualTo(26377);assertThat(owners).isEqualTo(228);assertThat(records).isEqualTo(477);
        report("fm26.financial.loans.live.report",Map.of("pid",620,"canonicalClubs",26839,"ordinaryClubs",ordinary,"owners",owners,"records",records,"allRawFramesMatch",true,"elapsedMillis",(System.nanoTime()-start)/1_000_000));
    }
    @Test @EnabledIfSystemProperty(named="fm26.financial.loans.database",matches="true")
    void actualNormalSnapshotContainsAll477RawLoanRecordsAndComputedHeerenveenDetails()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var expected=rows();
        int owners=0,records=0,ordinary=0;
        for(var row:jdbc.queryForList("SELECT unique_id,kind,module_sha256,financial_loans_hex,financial_loan_clock FROM club_records")) {
            long id=((Number)row.get("UNIQUE_ID")).longValue();var e=expected.get(id);
            if(e==null){assertThat(row.get("KIND")).isEqualTo("LEAGUE_BODY_CLUB");assertThat(row.get("FINANCIAL_LOANS_HEX")).isNull();continue;}
            ordinary++;assertThat(row.get("FINANCIAL_LOANS_HEX")).isEqualTo(ClubFinancialLoanCaptureTest.payload(e));
            assertThat(((Number)row.get("FINANCIAL_LOAN_CLOCK")).intValue()).isEqualTo(ClubFinancialLoanCaptureTest.CLOCK);
            var v=ClubFinancialLoanLayout.values((String)row.get("FINANCIAL_LOANS_HEX"),((Number)row.get("FINANCIAL_LOAN_CLOCK")).intValue(),(String)row.get("MODULE_SHA256")).orElseThrow();
            records+=v.loans().size();if(!v.loans().isEmpty())owners++;
            if(id==1036){var loan=v.loans().getFirst();assertThat(loan.remainingPrincipal()).isEqualTo(72807966);assertThat(loan.remainingInterest()).isEqualTo(13816572);}
        }
        assertThat(ordinary).isEqualTo(26377);assertThat(owners).isEqualTo(228);assertThat(records).isEqualTo(477);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players",Integer.class)).isEqualTo(44467);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff",Integer.class)).isEqualTo(25059);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isEqualTo(59533);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms",Integer.class)).isEqualTo(188254);
        report("fm26.financial.loans.database.report",Map.of("pid",620,"ordinaryClubs",ordinary,"owners",owners,"records",records,"allRawProfilesMatch",true,"snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class)));
    }
    private static Map<Long,String[]> rows()throws Exception {var out=new HashMap<Long,String[]>();for(var r:ClubFinancialLoanCaptureTest.rows())out.put(Long.parseLong(r[1]),r);return out;}
    private static void report(String property,Map<String,Object> data)throws Exception {
        String output=System.getProperty(property);assertThat(output).isNotBlank();
        Files.writeString(Path.of(output),new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data),StandardOpenOption.CREATE_NEW);
    }
}
