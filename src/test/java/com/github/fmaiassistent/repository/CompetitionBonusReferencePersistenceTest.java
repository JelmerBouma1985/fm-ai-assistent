package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql=false,properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class,ContractRepository.class})
class CompetitionBonusReferencePersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;@Autowired ContractRepository contracts;@Autowired JdbcTemplate jdbc;

    @Test void twoDifferentRelationshipsRoundTripWithoutChangingRawRowAndOldRowsRemainReadable()throws Exception {
        var fixture=ContractCaptureFixture.competitionBonus(1);var captured=fixture.decode();
        var row=new HashMap<String,Object>();row.put("unique_id",fixture.uid);row.put("_contracts",captured);row.put("_contract_profile",ContractExporter.MODULE_SHA256);
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(row)),Map.of());
        long id=jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?",Long.class,fixture.uid);
        var restored=contracts.findPlayerContracts(id,fixture.uid).getFirst().snapshot().terms().getFirst();
        assertThat(restored).isEqualTo(captured.getFirst().terms().getFirst());
        assertThat(restored.competitionReferences()).isEqualTo(new ContractSnapshot.CompetitionReferences(32L,1301394L));
        assertThat(contracts.findPlayerContracts(id,fixture.uid+1)).isEmpty();
        jdbc.update("UPDATE contract_terms SET subject_competition_uid=NULL,condition_competition_uid=NULL");
        var legacy=contracts.findPlayerContracts(id,fixture.uid).getFirst().snapshot().terms().getFirst();
        assertThat(legacy.competitionReferences()).isNull();assertThat(legacy.rawHex()).isEqualTo(restored.rawHex());
    }

    @Test void unrelatedGroupAndMissingIdentityFailBeforeContractInsert()throws Exception {
        var refs=new ContractSnapshot.CompetitionReferences(32L,1301394L);
        var wrong=new ContractSnapshot.Term("FULL_68",0,"0000000000000000",null,null,null,null,null,refs);
        assertThatThrownBy(()->PlayerSnapshotValidator.validateCompetitionReferences(wrong)).isInstanceOf(java.io.IOException.class);
        var raw="01006c077d020000ffffffffffffffff0a0000001600000000890000ffff0000";
        var missing=new ContractSnapshot.Term("FULL_80",0,raw,null,null,null,null,null,new ContractSnapshot.CompetitionReferences(32L,null));
        assertThatThrownBy(()->PlayerSnapshotValidator.validateCompetitionReferences(missing)).isInstanceOf(java.io.IOException.class);
    }

    @Test void capturedNamesMergeWithFinancialNamesAndRepositoryRejectsWrongSourceAndIndex()throws Exception {
        var f=ContractCaptureFixture.competitionBonus(1);var row=ContractCompetitionNameCaptureTest.owner(f,true);
        new ContractCompetitionNameExporter(f,f.base,com.github.fmaiassistent.memory.CompetitionNameLayout.current()).captureRows(List.of(row));
        var first=ContractCompetitionNameCaptureTest.names(row).getFirst();
        jdbc.update("INSERT INTO competition_name_records(unique_id,record_index,source_address,prefix_hex,module_sha256,name_hex,short_name_hex,name,short_name) VALUES(?,?,?,?,?,?,?,?,?)",
                first.uniqueId(),first.recordIndex(),first.sourceAddress(),first.prefixHex(),ContractExporter.MODULE_SHA256,first.nameHex(),first.shortNameHex(),first.name(),first.shortName());
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(row)),Map.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records",Integer.class)).isEqualTo(30);
        long id=jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?",Long.class,f.uid);
        var restored=contracts.findPlayerContracts(id,f.uid).getFirst();
        assertThat(restored.competitionNames()).containsEntry(32L,"Italian Serie A").containsEntry(1301394L,"UEFA Champions League");
        jdbc.update("UPDATE contract_terms SET subject_competition_uid=1301394 WHERE subject_competition_uid=32");
        var mismatched=contracts.findPlayerContracts(id,f.uid).getFirst();
        assertThat(mismatched.competitionNames()).containsKey(1301394L); // Other rows legitimately reference this same UID.
        assertThat(mismatched.snapshot().terms().stream().filter(t->t.rawHex().equals("01006c077d020000ffffffffffffffff0a0000001600000000890000ffff0000")).toList())
                .isNotEmpty().allSatisfy(t->assertThat(t.competitionReferences()).isNull());
        jdbc.update("UPDATE competition_name_records SET module_sha256=REPEAT('0',64) WHERE unique_id=32");
        jdbc.update("UPDATE competition_name_records SET prefix_hex=REPEAT('00',16) WHERE unique_id=1301394");
        assertThat(contracts.findPlayerContracts(id,f.uid).getFirst().competitionNames()).doesNotContainKeys(32L,1301394L);
        assertThat(contracts.findPlayerContracts(id,f.uid).getFirst().snapshot().terms()).hasSize(863);
    }

    @Test void incompleteCapturedNamesAreRejectedWithoutInsertingContracts()throws Exception {
        var f=ContractCaptureFixture.competitionBonus(1);var row=ContractCompetitionNameCaptureTest.owner(f,false);
        row.put("_contract_competition_names",List.of());
        assertThatThrownBy(()->writer.savePlayers(new PlayerExporter.ExportResult("",List.of(row)),Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("COMP name references");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isZero();
    }
}
