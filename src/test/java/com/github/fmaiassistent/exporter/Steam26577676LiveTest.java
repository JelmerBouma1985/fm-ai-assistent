package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.managedclub.ManagedClubMemoryReader;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Passive RAM check for the Dordrecht save used to verify Steam build 26577676. */
@EnabledIfSystemProperty(named = "fm27.live.steam26577676", matches = "true")
class Steam26577676LiveTest {
    @Test
    void validatesCurrentSaveAndNativeFeatures() throws Exception {
        int pid = Integer.getInteger("fm27.live.pid", -1);
        assertThat(pid).isPositive();
        String profile = NativeModuleProfile.STEAM_26577676_SHA256;
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
        var contracts = people.players().rows().stream()
                .flatMap(row -> ((List<?>) row.get("_contracts")).stream())
                .map(ContractSnapshot.class::cast).toList();
        assertThat(contracts).isNotEmpty();
        assertThat(contracts).noneMatch(contract -> contract.kind().startsWith("UNSUPPORTED_"));
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
        Files.writeString(Path.of("target/steam-26577676-verification.json"),
                JsonMapper.builder().build().writeValueAsString(Map.of(
                        "steamBuild", 26577676L, "profile", profile,
                        "players", people.players().rows().size(), "staff", people.staff().rows().size(),
                        "clubs", clubs.rows().size(), "competitions", competitions.rows().size(),
                        "fixtures", fixtures.rows().size(), "diagnostics", people.diagnostics(),
                        "managedClub", manager.clubName(), "gameDate", people.players().gameDate())));
    }
}
