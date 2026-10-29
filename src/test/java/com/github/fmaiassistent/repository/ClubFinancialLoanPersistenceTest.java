package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.ClubFinancialLoanCaptureTest;
import com.github.fmaiassistent.memory.ClubFinancialLoanLayout;
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
class ClubFinancialLoanPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired NativeClubRepository nativeClubs;
    @Autowired JdbcTemplate jdbc;
    @Test void allRawMembersIncludingUnknownAndVerifiedEmptySurviveCanonicalPersistence()throws Exception {
        var rows=ClubFinancialLoanCaptureTest.rows();var snapshots=new ArrayList<NativeClubSnapshot>();
        for(var r:rows)snapshots.add(new NativeClubSnapshot(Long.parseLong(r[1]),Integer.parseInt(r[0]),"CLUB",r[1].equals("1036")?"sc Heerenveen":null,
                Long.parseUnsignedLong(r[2],16),r[3],null,null,ClubFinancialLoanCaptureTest.payload(r),ClubFinancialLoanCaptureTest.CLOCK));
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),snapshots,ClubFinancialLoanLayout.MODULE_SHA256));
        assertThat(nativeClubs.count()).isEqualTo(26377);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE LENGTH(financial_loans_hex)>0",Integer.class)).isEqualTo(228);
        assertThat(jdbc.queryForObject("SELECT SUM(LENGTH(financial_loans_hex)/56) FROM club_records",Integer.class)).isEqualTo(477);
        var persisted=new HashMap<Long,Map<String,Object>>();
        for(var r:jdbc.queryForList("SELECT unique_id,financial_loans_hex,financial_loan_clock FROM club_records"))persisted.put(((Number)r.get("UNIQUE_ID")).longValue(),r);
        for(var expected:snapshots){var actual=persisted.get(expected.uniqueId());assertThat(actual.get("FINANCIAL_LOANS_HEX")).isEqualTo(expected.financialLoansHex());assertThat(actual.get("FINANCIAL_LOAN_CLOCK")).isEqualTo(expected.financialLoanClock());}
        var service=new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,mock(CompetitionNameRepository.class));
        var one=service.findClubFinancialLoans(1036L).orElseThrow().loans().getFirst();assertThat(one.typeName()).isEqualTo("Bank loan");assertThat(one.remainingPrincipal()).isEqualTo(72807966);
        assertThat(service.findClubFinancialLoans(null)).isEmpty();
    }
}
