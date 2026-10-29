package com.github.fmaiassistent.repository;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.*;
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
class ClubFinancialHistoryPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;@Autowired NativeClubRepository nativeClubs;@Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    ClubDatabaseService service(){return new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,mock(CompetitionNameRepository.class));}
    @Test void everyCapturedByteAndAllAbsencesSurviveWriterDatabaseAndService()throws Exception {
        var records=ClubFinancialHistoryCaptureTest.capturedRecords();var expected=ClubFinancialHistoryCaptureTest.expectedPayloads();
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),records,ClubFinancialLedgerLayout.MODULE_SHA256));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE financial_history_hex IS NOT NULL",Integer.class)).isEqualTo(680);
        int members=0,absent=0;var service=service();for(var r:records){var p=service.findClubFinancialHistory(r.uniqueId()).orElseThrow();assertThat(p.rawHex()).isEqualTo(expected.get(r.uniqueId()));
            members+=p.summary().count();if(p.expenditure()==null)absent++;else members+=p.expenditure().count();}
        assertThat(members).isEqualTo(28834);assertThat(absent).isEqualTo(425);assertThat(service.findClubFinancialHistory(null)).isEmpty();
        assertThat(service.findClubFinancialHistory(1036L).orElseThrow().balances().getFirst().amount()).isEqualTo(21230397);
        jdbc.update("UPDATE club_records SET financial_history_hex=? WHERE unique_id=1036","ff".repeat(18));em.clear();assertThat(service.findClubFinancialHistory(1036L)).isEmpty();
        jdbc.update("UPDATE club_records SET financial_history_hex=NULL WHERE unique_id=1036");em.clear();assertThat(service.findClubFinancialHistory(1036L)).isEmpty();
    }
    @Test void invalidOptionalProfileFailsBeforeAnyCanonicalWrite()throws Exception {
        var old=ClubFinancialHistoryCaptureTest.capturedRecords().stream().filter(r->r.uniqueId()==1036).findFirst().orElseThrow();
        var bad=new com.github.fmaiassistent.domain.NativeClubSnapshot(old.uniqueId(),old.recordIndex(),old.kind(),old.name(),old.sourceAddress(),old.prefixHex(),null,null,null,null,
                old.financialLedgerHex(),old.financialLedgerClock(),null,"ff".repeat(18));
        assertThatThrownBy(()->writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(bad),ClubFinancialLedgerLayout.MODULE_SHA256))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("financial history");
        assertThat(nativeClubs.count()).isZero();
    }
}
