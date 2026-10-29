package com.github.fmaiassistent.repository;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.ClubFinancialLedgerLayout;
import com.github.fmaiassistent.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(showSql=false,properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import(SnapshotDatabaseWriter.class)
class ClubFinancialLedgerPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;@Autowired NativeClubRepository nativeClubs;@Autowired JdbcTemplate jdbc;
    @Test void everyCapturedProfileSurvivesCanonicalWriteAndNullRemainsUnavailable()throws Exception {
        var records=ClubFinancialLedgerCaptureTest.capturedRecords();
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),records,ClubFinancialLedgerLayout.MODULE_SHA256));
        assertThat(nativeClubs.count()).isEqualTo(26377);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE financial_ledger_hex IS NOT NULL",Integer.class)).isEqualTo(680);
        var actual=new HashMap<Long,Map<String,Object>>();for(var row:jdbc.queryForList("SELECT unique_id,financial_ledger_hex,financial_ledger_clock FROM club_records"))actual.put(((Number)row.get("UNIQUE_ID")).longValue(),row);
        for(var record:records){assertThat(actual.get(record.uniqueId()).get("FINANCIAL_LEDGER_HEX")).isEqualTo(record.financialLedgerHex());assertThat(actual.get(record.uniqueId()).get("FINANCIAL_LEDGER_CLOCK")).isEqualTo(record.financialLedgerClock());}
        var service=new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,mock(CompetitionNameRepository.class));
        var p=service.findClubFinancialLedger(1036L).orElseThrow();assertThat(p.periods().get(2).totalIncome()).isEqualTo(369964713);assertThat(p.periods().get(3).totalExpenditure()).isEqualTo(269086591);
        assertThat(service.findClubFinancialLedger(null)).isEmpty();
        var missing=records.stream().filter(r->r.financialLedgerHex()==null).findFirst().orElseThrow();assertThat(service.findClubFinancialLedger(missing.uniqueId())).isEmpty();
    }
}
