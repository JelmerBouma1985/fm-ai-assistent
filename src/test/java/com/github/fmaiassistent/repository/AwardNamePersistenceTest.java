package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.*;
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
@Import({SnapshotDatabaseWriter.class,ContractRepository.class,DatabaseService.class})
class AwardNamePersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;@Autowired ContractRepository contracts;@Autowired JdbcTemplate jdbc;
    @Autowired DatabaseService database;
    Map<String,Object> capturedOwner()throws Exception {
        return new HashMap<>(AwardNameCaptureTest.capturedRows().stream().filter(r->((Number)r.get("unique_id")).longValue()==2002079297L).findFirst().orElseThrow());
    }
    long save(Map<String,Object> row){writer.savePlayers(new PlayerExporter.ExportResult("",List.of(row)),Map.of());return jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?",Long.class,row.get("unique_id"));}
    @Test void capturedRowsAndNativeNamesRoundTripThroughWriterAndSourceCheckedRepositoryJoin()throws Exception {
        var row=capturedOwner();long id=save(row);var stored=contracts.findPlayerContracts(id,2002079297L).getFirst();
        assertThat(stored.awardNames()).containsExactlyInAnyOrderEntriesOf(Map.of(102407L,"World Footballer of the Year",102408L,"World Player of the Year",102406L,"World Golden Ball"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM award_name_records",Integer.class)).isEqualTo(3);
        assertThat(stored.snapshot().terms()).hasSize(3);assertThat(stored.snapshot().terms().getFirst().rawHex()).isEqualTo(((ContractSnapshot)((List<?>)row.get("_contracts")).getFirst()).terms().getFirst().rawHex());
        for(var n:AwardNameCaptureTest.names(row)){var db=jdbc.queryForMap("SELECT * FROM award_name_records WHERE unique_id=?",n.uniqueId());assertThat(db.get("NAME_HEX")).isEqualTo(n.nameHex());assertThat(db.get("SHORT_NAME_HEX")).isEqualTo(n.shortNameHex());}
        assertThat(contracts.findPlayerContracts(id,2002070983L)).isEmpty();
        jdbc.update("UPDATE award_name_records SET module_sha256=REPEAT('0',64) WHERE unique_id=102407");
        assertThat(contracts.findPlayerContracts(id,2002079297L).getFirst().awardNames()).doesNotContainKey(102407L);
        assertThat(contracts.findPlayerContracts(id,2002079297L).getFirst().snapshot().terms()).hasSize(3);
    }
    @Test void oldSnapshotsRetainTermsWithoutNamesAndMalformedOptionalNamesCannotBorrowAnotherIdentity()throws Exception {
        var row=capturedOwner();row.remove("_award_names");long id=save(row);
        assertThat(contracts.findPlayerContracts(id,2002079297L).getFirst().awardNames()).isEmpty();assertThat(contracts.findPlayerContracts(id,2002079297L).getFirst().snapshot().terms()).hasSize(3);
        database.clearAllTables();row=capturedOwner();id=save(row);
        jdbc.update("UPDATE award_name_records SET prefix_hex=REPEAT('00',16) WHERE unique_id=102407");
        jdbc.update("UPDATE award_name_records SET name_hex='01000000ff' WHERE unique_id=102408");
        assertThat(contracts.findPlayerContracts(id,2002079297L).getFirst().awardNames()).containsOnlyKeys(102406L);
        database.clearAllTables();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM award_name_records",Integer.class)).isZero();
    }
    @Test void incompleteOrUnrelatedCapturedNameSetsFailBeforeContractAndNameInserts()throws Exception {
        var row=capturedOwner();row.put("_award_names",AwardNameCaptureTest.names(row).subList(0,2));
        assertThatThrownBy(()->save(row)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AWARD name references");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM award_name_records",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isZero();
    }
}
