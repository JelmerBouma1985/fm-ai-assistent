package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.exporter.Fm27CapturedContractsTest;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.exporter.StaffExporter;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.show-sql=false", "logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class, ContractRepository.class})
class Fm27CapturedContractPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired ContractRepository contracts;
    @Autowired JdbcTemplate jdbc;

    @Test void allCapturedContractKindsRoundTripWithExactTermsAndSeparatePlayerStaffOwnership() throws Exception {
        var fixtures = Fm27CapturedContractsTest.fixtures();
        record Expected(long uid, boolean staff, List<com.github.fmaiassistent.domain.ContractSnapshot> contracts) { }
        var expected = new ArrayList<Expected>();
        var playerRows = new ArrayList<Map<String, Object>>();
        var staffRows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 4; i++) {
            var fixture = fixtures.get(i);
            long uid = fixture.path("uid").asLong();
            var captured = Fm27CapturedContractsTest.exporter(fixture, ContractRecordLayout.current())
                    .read(fixture.path("person").asLong(), uid);
            var row = Map.<String, Object>of("unique_id", uid, "_contract_profile", NativeModuleProfile.FM27_SHA256,
                    "_contracts", captured);
            boolean staff = i == 1;
            (staff ? staffRows : playerRows).add(row);
            expected.add(new Expected(uid, staff, captured));
        }
        writer.savePlayers(new PlayerExporter.ExportResult("2026-08-03", playerRows), Map.of());
        writer.saveStaff(new StaffExporter.ExportResult("2026-08-03", staffRows), Map.of());
        for (var capture : expected) {
            Long id = jdbc.queryForObject("SELECT id FROM " + (capture.staff() ? "staff" : "players") + " WHERE unique_id=?", Long.class, capture.uid());
            var restored = capture.staff() ? contracts.findStaffContracts(id, capture.uid()) : contracts.findPlayerContracts(id, capture.uid());
            assertThat(restored).extracting(c -> c.snapshot()).containsExactlyElementsOf(capture.contracts());
            assertThat(restored).allSatisfy(c -> assertThat(c.moduleSha256()).isEqualTo(NativeModuleProfile.FM27_SHA256));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE staff_id IS NOT NULL AND player_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts", Integer.class)).isEqualTo(6);
    }

    @Test void capturedFutureContractKeepsOfferIdentityWageEndDateAndAllTerms() throws Exception {
        var fixture = Fm27CapturedContractsTest.fixtures().get(4);
        long uid = fixture.path("uid").asLong();
        var employment = Fm27CapturedContractsTest.exporter(fixture, ContractRecordLayout.current())
                .readFutureEmployment(fixture.path("person").asLong(), uid, 0).orElseThrow();
        var future = new FutureTransferSnapshot(LocalDate.of(2026, 8, 4), employment);
        writer.savePlayers(new PlayerExporter.ExportResult("2026-08-03", List.of(Map.of(
                "unique_id", uid, "_contract_profile", NativeModuleProfile.FM27_SHA256, "_future_contracts", List.of(future)))), Map.of());
        Long id = jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?", Long.class, uid);
        var restored = contracts.findPlayerContracts(id, uid);
        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst().snapshot()).isEqualTo(employment.contract());
        assertThat(restored.getFirst().futureTransfer()).isEqualTo(future);
    }
}
