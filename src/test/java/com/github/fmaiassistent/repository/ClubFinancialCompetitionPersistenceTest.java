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
class ClubFinancialCompetitionPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;@Autowired NativeClubRepository nativeClubs;@Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Test void all680ProfilesAnd2747MembersSurviveAtomicWriterAndServiceWithoutCompetitionCatalogue()throws Exception {
        var records=ClubFinancialCompetitionCaptureTest.capturedRecords();
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),records,ClubFinancialLedgerLayout.MODULE_SHA256));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE financial_competitions_hex IS NOT NULL",Integer.class)).isEqualTo(680);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competitions",Integer.class)).isZero();
        var expected=ClubFinancialCompetitionCaptureTest.expectedPayloads();int members=0;
        var service=new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,mock(CompetitionNameRepository.class));
        for(var row:jdbc.queryForList("SELECT unique_id,financial_competitions_hex FROM club_records")){long uid=((Number)row.get("UNIQUE_ID")).longValue();
            assertThat(row.get("FINANCIAL_COMPETITIONS_HEX")).isEqualTo(expected.get(uid));
            var profile=ClubFinancialCompetitionLayout.values((String)row.get("FINANCIAL_COMPETITIONS_HEX"),0x07F41A8D,ClubFinancialLedgerLayout.MODULE_SHA256);assertThat(profile.isPresent()).isEqualTo(expected.containsKey(uid));
            if(profile.isPresent())members+=profile.orElseThrow().periods().stream().mapToInt(p->p.tvRevenue().size()+p.prizeMoney().size()).sum();}
        assertThat(members).isEqualTo(2747);assertThat(service.findClubFinancialCompetitions(null)).isEmpty();
        assertThat(service.findClubFinancialCompetitions(1036L)).isPresent();
        jdbc.update("UPDATE club_records SET financial_competitions_hex=? WHERE unique_id=1036","00".repeat(40));
        entityManager.clear(); // Restore must reject a structurally valid empty collection that contradicts its ledger.
        assertThat(service.findClubFinancialCompetitions(1036L)).isEmpty();
    }
}
