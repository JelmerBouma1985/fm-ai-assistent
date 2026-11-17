package com.github.fmaiassistent.service;

import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.CompetitionExporter;
import com.github.fmaiassistent.exporter.ContractExporter;
import com.github.fmaiassistent.exporter.FixtureExporter;
import com.github.fmaiassistent.exporter.PeopleExporter;
import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.managedclub.ManagedClubMemoryReader;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Passive RAM check for the Dordrecht save used to verify Steam build 26620374. */
@EnabledIfSystemProperty(named = "fm27.live.steam26620374", matches = "true")
@DataJpaTest(properties = {"spring.jpa.show-sql=false", "logging.level.liquibase=warn"}, showSql = false)
@Import(SnapshotDatabaseWriter.class)
class Steam26620374LiveTest {
    private final SnapshotDatabaseWriter writer;
    private final JdbcTemplate jdbc;

    @Autowired
    Steam26620374LiveTest(SnapshotDatabaseWriter writer, JdbcTemplate jdbc) {
        this.writer = writer;
        this.jdbc = jdbc;
    }

    @Test
    void validatesCurrentSaveAndNativeFeatures() throws Exception {
        int pid = Integer.getInteger("fm27.live.pid", -1);
        assertThat(pid).isPositive();
        String profile = NativeModuleProfile.STEAM_26620374_SHA256;
        try (var reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            assertThat(FmOffsets.moduleProfile(reader, base)).isEqualTo(profile);
            assertThat(FmOffsets.peopleBounds(reader, FmOffsets.DEFAULT_BUILD, base).count()).isEqualTo(109612);
        }
        var manager = new ManagedClubMemoryReader().read(pid, FmOffsets.DEFAULT_BUILD, null);
        assertThat(manager.managerUniqueId()).isEqualTo(2002082515L);
        assertThat(manager.clubName()).isEqualTo("FC Dordrecht");
        var people = new PeopleExporter().exportAllPeople(pid, FmOffsets.DEFAULT_BUILD, null);
        assertThat(people.players().gameDate()).isEqualTo("2026-07-06");
        assertThat(people.players().rows()).hasSize(46439);
        assertThat(people.staff().rows()).hasSize(29012);
        PlayerSnapshotValidator.validate(people.players().rows(), people.slotCount(), people.players().gameDate());
        PlayerSnapshotValidator.validateStaff(people.staff().rows(), people.staff().gameDate(), people.players().rows().size());
        assertThat(people.diagnostics().invalidBlocks()).isZero();
        assertThat(people.diagnostics().malformedPointers()).isZero();
        assertThat(people.diagnostics().unreadable()).isZero();
        assertThat(people.players().rows()).allSatisfy(row ->
                assertThat(row.get("_contract_profile")).isEqualTo(profile));
        var owners = new ArrayList<>(people.players().rows());
        owners.addAll(people.staff().rows());
        assertThat(owners).allSatisfy(row -> assertThat(row.get("_contract_profile")).isEqualTo(profile));
        var contracts = owners.stream()
                .flatMap(row -> ((List<?>) row.get("_contracts")).stream())
                .map(ContractSnapshot.class::cast).toList();
        assertThat(contracts).isNotEmpty();
        assertThat(contracts).noneMatch(contract -> contract.kind().startsWith("UNSUPPORTED_"));
        assertThat(contracts.stream().map(ContractSnapshot::kind).distinct())
                .contains("FULL", "BASIC", "LOAN", "B_CLUB");
        var futures = owners.stream().flatMap(row -> ((List<?>) row.get("_future_contracts")).stream())
                .map(FutureTransferSnapshot.class::cast).toList();
        assertThat(futures).isNotEmpty();
        System.out.println("Future transfer partial owners: " + owners.stream()
                .filter(row -> "PARTIAL".equals(row.get("_future_transfer_profile"))).count());
        captureContracts(pid, profile, owners);
        var clubs = new ClubExporter().exportAllClubs(pid, FmOffsets.DEFAULT_BUILD, null);
        PlayerSnapshotValidator.validateClubs(clubs.rows(), people.players().rows().size());
        assertThat(clubs.identityProfile()).isEqualTo(profile);
        assertThat(clubs.rows()).hasSize(34892);
        assertThat(clubs.nativeRecords()).anyMatch(club -> club.uefaCoefficientHex() != null);
        assertThat(clubs.nativeRecords()).anyMatch(club -> club.financialLedgerHex() != null);
        var competitions = CompetitionExporter.withClubReferences(
                new CompetitionExporter().exportAllCompetitions(pid, FmOffsets.DEFAULT_BUILD, null), clubs);
        PlayerSnapshotValidator.validateCompetitions(competitions.rows(), people.players().rows().size());
        assertThat(competitions.rows()).hasSize(6625);
        var fixtures = new FixtureExporter(new ManagedClubMemoryReader())
                .exportManagedClubFixtures(pid, FmOffsets.DEFAULT_BUILD, null);
        assertThat(fixtures.rows()).hasSize(42);
        var competitionIds = writer.saveCompetitions(competitions);
        var clubIds = writer.saveClubs(clubs, competitionIds);
        writer.saveFixtures(fixtures, clubIds);
        writer.savePlayers(people.players(), clubIds);
        writer.saveStaff(people.staff(), clubIds);
        for (var count : Map.of("players", 46439, "staff", 29012, "clubs", 34892,
                "competitions", 6625, "fixtures", 42).entrySet()) {
            assertThat(jdbc.queryForObject("select count(*) from " + count.getKey(), Long.class))
                    .as("persisted %s", count.getKey()).isEqualTo(count.getValue().longValue());
        }
        assertThat(jdbc.queryForObject("select count(*) from player_contracts", Long.class))
                .isEqualTo((long) contracts.size() + futures.size());
        var report = new LinkedHashMap<String, Object>(Map.of(
                "steamBuild", 26620374L, "profile", profile,
                "players", people.players().rows().size(), "staff", people.staff().rows().size(),
                "clubs", clubs.rows().size(), "competitions", competitions.rows().size(),
                "fixtures", fixtures.rows().size(), "diagnostics", people.diagnostics(),
                "managedClub", manager.clubName(), "gameDate", people.players().gameDate()));
        report.put("currentContracts", contracts.size());
        report.put("futureContracts", futures.size());
        report.put("partialFutureTransferOwners", owners.stream()
                .filter(row -> "PARTIAL".equals(row.get("_future_transfer_profile"))).count());
        report.put("nativeClubs", clubs.nativeRecords().size());
        report.put("uefaCoefficients", clubs.nativeRecords().stream().filter(c -> c.uefaCoefficientHex() != null).count());
        report.put("financialLedgers", clubs.nativeRecords().stream().filter(c -> c.financialLedgerHex() != null).count());
        report.put("financialLoans", clubs.nativeRecords().stream().filter(c -> c.financialLoansHex() != null).count());
        report.put("financialHistory", clubs.nativeRecords().stream().filter(c -> c.financialHistoryHex() != null).count());
        report.put("persistence", "All exported tables and contracts saved to isolated transactional H2");
        Files.writeString(Path.of("target/steam-26620374-verification.json"),
                JsonMapper.builder().build().writeValueAsString(report));
    }

    private static void captureContracts(int pid, String profile, List<Map<String, Object>> owners) throws Exception {
        var mapper = JsonMapper.builder().build();
        var fixtures = new ArrayList<Map<String, Object>>();
        try (var source = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(source);
            for (String kind : List.of("FULL", "BASIC", "LOAN", "B_CLUB", "FUTURE")) {
                var owner = owners.stream().filter(row -> kind.equals("FUTURE")
                        ? !((List<?>) row.get("_future_contracts")).isEmpty()
                        : ((List<?>) row.get("_contracts")).stream()
                                .map(ContractSnapshot.class::cast).anyMatch(c -> c.kind().equals(kind)))
                        .findFirst().orElseThrow();
                Object address = owner.containsKey("record") ? owner.get("record") : owner.get("record_address");
                long person = address instanceof Number n ? n.longValue() : Long.decode(address.toString());
                long uid = ((Number) owner.get("unique_id")).longValue();
                var memory = new LinkedHashMap<String, String>();
                var recorder = new ProcessMemoryReader() {
                    private int total;
                    public int pid() { return pid; }
                    public Platform platform() { return source.platform(); }
                    public List<MemoryRegion> maps() throws IOException { return source.maps(); }
                    public void close() { }
                    public byte[] readBytes(long at, int size) throws IOException {
                        if (size < 0 || size > 262144 - total) throw new IOException("Fixture capture exceeds cap");
                        total += size;
                        byte[] bytes = source.readBytes(at, size);
                        String previous = memory.putIfAbsent(at + ":" + size, HexFormat.of().formatHex(bytes));
                        if (previous != null && !previous.equals(HexFormat.of().formatHex(bytes)))
                            throw new IOException("Fixture changed during capture");
                        return bytes;
                    }
                };
                var fixture = new LinkedHashMap<String, Object>();
                fixture.put("kind", kind); fixture.put("person", person); fixture.put("uid", uid);
                fixture.put("base", base); fixture.put("memory", memory);
                var exporter = new ContractExporter(recorder, base, ContractRecordLayout.forModule(profile));
                if (kind.equals("FUTURE")) {
                    var transfer = (FutureTransferSnapshot) ((List<?>) owner.get("_future_contracts")).getFirst();
                    int offer = transfer.employment().offerIndex();
                    fixture.put("offer", offer);
                    fixture.put("future", exporter.readFutureEmployment(person, uid, offer).orElseThrow());
                } else fixture.put("contracts", exporter.read(person, uid));
                fixtures.add(fixture);
            }
        }
        Files.writeString(Path.of("target/contracts-26620374-captured.json"), mapper.writeValueAsString(fixtures));
    }
}

