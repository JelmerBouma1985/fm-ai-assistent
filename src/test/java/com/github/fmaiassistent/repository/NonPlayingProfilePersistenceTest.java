package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.*;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.NonPlayingProfileLayout;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class,ContractRepository.class})
class NonPlayingProfilePersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired PlayerRepository players;
    @Autowired StaffRepository staff;
    @Autowired JdbcTemplate jdbc;

    @Test void atomicWriterAndEntityReadersKeepAll52BytesSourceHashAndOwnerKindSeparate() {
        String raw="01".repeat(45)+"ff1f"+"05".repeat(5);assertThat(raw).hasSize(104);
        String hash=NonPlayingProfileLayout.MODULE_SHA256;
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(Map.of("unique_id",222138L,"name","Combined player",
                "non_playing_profile_hex",raw,"non_playing_profile_sha256",hash),Map.of("unique_id",17L,"name","Pure player"))),Map.of());
        writer.saveStaff(new StaffExporter.ExportResult("",List.of(Map.of("unique_id",47L,"name","Coach",
                "non_playing_profile_hex",raw,"non_playing_profile_sha256",hash),Map.of("unique_id",99L,"name","Legacy staff"))),Map.of());
        var p=players.findById(1L).orElseThrow();var s=staff.findById(1L).orElseThrow();
        assertThat(p.getNonPlayingProfileHex()).isEqualTo(raw);assertThat(p.getNonPlayingProfileSha256()).isEqualTo(hash);
        assertThat(s.getNonPlayingProfileHex()).isEqualTo(raw);assertThat(s.toApiMap()).containsEntry("non_playing_profile_sha256",hash);
        assertThat(players.findById(2L).orElseThrow().getNonPlayingProfileHex()).isNull();
        assertThat(staff.findById(2L).orElseThrow().getNonPlayingProfileSha256()).isNull();
        assertThat(StaffEntity.fromExportRow(Map.of()).getNonPlayingProfileHex()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE unique_id=222138",Integer.class)).isZero();
        assertThat(NonPlayingProfileLayout.values(p.getNonPlayingProfileHex(),p.getNonPlayingProfileSha256())).hasSize(50);
    }
}
