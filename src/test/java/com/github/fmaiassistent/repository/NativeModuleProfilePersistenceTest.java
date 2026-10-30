package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.NativeAwardNameSnapshot;
import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.service.ClubDatabaseService;
import com.github.fmaiassistent.service.CompetitionDatabaseService;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Synthetic persistence fixtures exercise source isolation; they do not prove native FM27 offsets. */
@DataJpaTest(showSql=false,properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class,ContractRepository.class})
class NativeModuleProfilePersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired ContractRepository contracts;
    @Autowired NativeClubRepository nativeClubs;
    @Autowired CompetitionNameRepository competitionNames;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    @Test void preservesActualContractAndAwardHashAndDoesNotJoinAnotherSupportedProfile() {
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(owner(101,NativeModuleProfile.FM27_SHA256))),Map.of());
        long id=jdbc.queryForObject("SELECT id FROM players WHERE unique_id=101",Long.class);
        var stored=contracts.findPlayerContracts(id,101L).getFirst();
        assertThat(stored.moduleSha256()).isEqualTo(NativeModuleProfile.FM27_SHA256);
        assertThat(stored.awardNames()).containsEntry(55L,"Synthetic award");
        assertThat(jdbc.queryForObject("SELECT module_sha256 FROM award_name_records WHERE unique_id=55",String.class))
                .isEqualTo(NativeModuleProfile.FM27_SHA256);

        jdbc.update("UPDATE award_name_records SET module_sha256=? WHERE unique_id=55",NativeModuleProfile.LEGACY_SHA256);
        var mismatched=contracts.findPlayerContracts(id,101L).getFirst();
        assertThat(mismatched.awardNames()).isEmpty();
        assertThat(mismatched.snapshot().terms()).hasSize(1);
    }

    @Test void rejectsUnknownContractProvenanceBeforeWritingTypedRows() {
        var unknown=owner(101,"0".repeat(64));
        assertThatThrownBy(()->writer.savePlayers(new PlayerExporter.ExportResult("",List.of(unknown)),Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source profile");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM award_name_records",Integer.class)).isZero();
    }

    @Test void rejectsMissingContractProvenanceBeforeWritingTypedRows() {
        var missing=owner(102,NativeModuleProfile.FM27_SHA256);missing.remove("_contract_profile");
        assertThatThrownBy(()->writer.savePlayers(new PlayerExporter.ExportResult("",List.of(missing)),Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source profile");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM award_name_records",Integer.class)).isZero();
    }

    @Test void rejectsIdenticalNamesFromMixedProfilesInsteadOfSilentlyRelabellingThem() {
        assertThatThrownBy(()->writer.savePlayers(new PlayerExporter.ExportResult("",List.of(
                owner(101,NativeModuleProfile.FM27_SHA256),owner(102,NativeModuleProfile.LEGACY_SHA256))),Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting AWARD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM award_name_records",Integer.class)).isZero();
    }

    @Test void coefficientPaginationIncludesBothProfilesAndDecodesEachWithItsOwnRules() {
        byte[] bytes=new byte[76];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x44,1000);
        String hex=HexFormat.of().formatHex(bytes);
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(new NativeClubSnapshot(
                201,0,"CLUB","A legacy",0x10000000L,prefix(0,201),hex,1650)),NativeModuleProfile.LEGACY_SHA256));
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(new NativeClubSnapshot(
                202,1,"CLUB","B current",0x10000100L,prefix(1,202),hex,1650)),NativeModuleProfile.FM27_SHA256));
        var service=clubs();
        assertThat(jdbc.queryForObject("SELECT module_sha256 FROM club_records WHERE unique_id=202",String.class))
                .isEqualTo(NativeModuleProfile.FM27_SHA256);
        assertThat(service.countCoefficientClubs()).isEqualTo(2);
        var page=service.findCoefficientClubs(PageRequest.of(1,1,Sort.by("name")));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).singleElement().satisfies(c->assertThat(c.uniqueId()).isEqualTo(202));
        assertThat(service.countCoefficientClubs("CURRENT")).isEqualTo(1);
        assertThat(service.findClubCoefficients(201L).orElseThrow().coefficients().currentSeasonPoints()).isEqualByComparingTo("10");
        assertThat(service.findClubCoefficients(202L).orElseThrow().coefficients().currentSeasonPoints()).isEqualByComparingTo("1");
    }

    @Test void financeLabelsRequireTheExactOwnerProfileWhileAmountsRemainAvailable() {
        var ledgerLayout=com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.fm27();
        byte[] ledger=new byte[ledgerLayout.payloadBytes()];
        ByteBuffer.wrap(ledger).order(ByteOrder.LITTLE_ENDIAN).putInt(53*4,17);
        ByteBuffer members=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        members.putInt(1).putInt(301).putInt(17).putInt(0);
        while(members.hasRemaining())members.putInt(0);
        int clock=(2026<<16)|302;
        var club=new NativeClubSnapshot(202,0,"CLUB","Current",0x10000000L,prefix(0,202),
                null,null,null,null,HexFormat.of().formatHex(ledger),clock,HexFormat.of().formatHex(members.array()),null);
        var name=new NativeCompetitionNameSnapshot(301,7,0x20000000L,prefix(7,301),text("Synthetic competition"),null,"Synthetic competition",null);
        writer.saveNativeClubs(new ClubExporter.ExportResult(List.of(),List.of(club),NativeModuleProfile.FM27_SHA256,List.of(name)));
        assertThat(clubs().findClubCompetitionIncome(202L).orElseThrow().names()).containsEntry(301L,"Synthetic competition");

        jdbc.update("UPDATE competition_name_records SET module_sha256=? WHERE unique_id=301",NativeModuleProfile.LEGACY_SHA256);
        em.clear();
        var income=clubs().findClubCompetitionIncome(202L).orElseThrow();
        assertThat(income.names()).isEmpty();
        assertThat(income.profile().periods().getFirst().tvRevenue()).singleElement()
                .satisfies(m->assertThat(m.amount()).isEqualTo(17));
    }

    private ClubDatabaseService clubs() {
        return new ClubDatabaseService(mock(ClubRepository.class),mock(CompetitionRepository.class),
                mock(CompetitionDatabaseService.class),mock(LoadMetadataRepository.class),writer,nativeClubs,competitionNames);
    }

    private static Map<String,Object> owner(long uid,String profile) {
        byte[] bonus=new byte[16];ByteBuffer.wrap(bonus).order(ByteOrder.LITTLE_ENDIAN).putInt(8,150);
        var term=new ContractSnapshot.Term("FULL_98",0,HexFormat.of().formatHex(bonus),null,null,null,"AWARD",55L);
        var contract=new ContractSnapshot("FULL",-1,uid,null,3,null,null,null,null,null,null,null,
                null,null,null,null,null,null,"00".repeat(248),null,List.of(term),null,null);
        var name=new NativeAwardNameSnapshot(55,4,prefix(4,55),text("Synthetic award"),null,"Synthetic award",null);
        return new HashMap<>(Map.of("unique_id",uid,"name","Synthetic owner "+uid,"_contract_profile",profile,
                "_contracts",List.of(contract),"_award_names",List.of(name)));
    }

    private static String prefix(int index,int uid) {
        return HexFormat.of().formatHex(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0x70000000L).putInt(index).putInt(uid).array());
    }

    private static String text(String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(ByteBuffer.allocate(4+bytes.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.length).put(bytes).array());
    }
}
