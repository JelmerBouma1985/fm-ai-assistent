package com.github.fmaiassistent.repository;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.*;
import com.github.fmaiassistent.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(showSql=false,properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import(SnapshotDatabaseWriter.class)
class CompetitionNamePersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;@Autowired NativeClubRepository clubs;@Autowired CompetitionNameRepository names;@Autowired JdbcTemplate jdbc;@Autowired jakarta.persistence.EntityManager em;
    ClubDatabaseService service(){return new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,clubs,names);}
    @Test void all60ReferencesSurviveWithoutCatalogueAnd21NamelessNonzeroMembersRemainAvailable()throws Exception {
        var result=CompetitionNameCaptureTest.capturedResult();writer.saveNativeClubs(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competitions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records",Integer.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records WHERE name IS NULL AND short_name IS NULL",Integer.class)).isEqualTo(20);
        var expected=result.competitionNames().stream().collect(java.util.stream.Collectors.toMap(com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot::uniqueId,n->n));
        for(var row:jdbc.queryForList("SELECT * FROM competition_name_records")){var e=expected.get(((Number)row.get("UNIQUE_ID")).longValue());assertThat(row.get("NAME_HEX")).isEqualTo(e.nameHex());assertThat(row.get("SHORT_NAME_HEX")).isEqualTo(e.shortNameHex());assertThat(row.get("NAME")).isEqualTo(e.name());assertThat(row.get("SHORT_NAME")).isEqualTo(e.shortName());}
        var service=service();var income=service.findClubCompetitionIncome(1036L).orElseThrow();assertThat(income.names()).containsEntry(29L,"VriendenLoterij Eredivisie").containsEntry(1301394L,"UEFA Champions League").containsEntry(1301411L,"Dutch Cup");
        byte[] input;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_competition_unnamed_members_20261027.json"))){input=in.readNBytes(1048577);}assertThat(input.length).isLessThanOrEqualTo(1048576);var observations=new tools.jackson.databind.json.JsonMapper().readTree(input).path("rows");assertThat(observations.size()).isEqualTo(21);
        for(var n:observations){long club=n.path("clubUid").asLong(),uid=n.path("competitionUid").asLong();var value=service.findClubCompetitionIncome(club).orElseThrow();assertThat(value.names()).doesNotContainKey(uid);var period=value.profile().periods().get(n.path("period").asInt());var members=n.path("kind").asInt()==0?period.tvRevenue():period.prizeMoney();assertThat(members.get(n.path("ordinal").asInt()).competitionUid()).isEqualTo(uid);assertThat(members.get(n.path("ordinal").asInt()).amount()).isEqualTo(n.path("amount").asInt()).isNotZero();}
    }
    @Test void incompleteReferencesRejectBeforeWritesAndLegacyCaptureCannotKeepStaleLabels()throws Exception {
        var result=CompetitionNameCaptureTest.capturedResult();writer.saveNativeClubs(result);
        var incomplete=new ClubExporter.ExportResult(result.rows(),result.nativeRecords(),result.identityProfile(),result.competitionNames().subList(1,60));
        assertThatThrownBy(()->writer.saveNativeClubs(incomplete)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("references");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records",Integer.class)).isEqualTo(60);
        writer.saveNativeClubs(new ClubExporter.ExportResult(result.rows(),result.nativeRecords(),result.identityProfile()));em.clear();
        assertThat(service().findClubCompetitionIncome(1036L).orElseThrow().names()).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records",Integer.class)).isZero();
    }
    @Test void corruptedOptionalNameBytesOrIdentityDoNotHideAmountsOrBorrowAnotherName()throws Exception {
        var result=CompetitionNameCaptureTest.capturedResult();writer.saveNativeClubs(result);
        jdbc.update("UPDATE competition_name_records SET name_hex='01000000ff' WHERE unique_id=1301394");
        jdbc.update("UPDATE competition_name_records SET prefix_hex=REPEAT('00',16) WHERE unique_id=29");em.clear();
        var income=service().findClubCompetitionIncome(1036L).orElseThrow();assertThat(income.names()).doesNotContainKeys(29L,1301394L).containsEntry(1301411L,"Dutch Cup");assertThat(income.profile().periods().getFirst().prizeMoney()).hasSize(2);
    }
}
