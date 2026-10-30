package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.GameDateFinder;
import com.github.fmaiassistent.managedclub.ManagedClubMemoryReader;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in passive verification against the independently captured new-save census. */
@EnabledIfSystemProperty(named="fm27.live",matches="true")
class Fm27CurrentFeaturesLiveTest {
    private int pid() { int pid=Integer.getInteger("fm27.live.pid",-1);assertThat(pid).isPositive();return pid; }

    @Test
    void rootsAndManagedClubMatchTheNewSave() throws Exception {
        try(var reader=ProcessReaders.open(pid())) {
            long base=FmOffsets.findGamePluginBase(reader);
            assertThat(FmOffsets.peopleBounds(reader,FmOffsets.DEFAULT_BUILD,base).count()).isEqualTo(94815);
            assertThat(new GameDateFinder().find(reader,0,FmOffsets.DEFAULT_BUILD,base)).hasValue(java.time.LocalDate.of(2026,8,3));
            var manager=new ManagedClubMemoryReader().read(reader,FmOffsets.DEFAULT_BUILD,base);
            assertThat(manager.managerName()).isEqualTo("Jelmer Bouma");
            assertThat(manager.clubName()).isEqualTo("Feyenoord");
            assertThat(manager.managerUniqueId()).isEqualTo(2002081878L);
        }
    }

    @Test
    void productionClubAndCompetitionExportsMatchTheNativeRestartCapture() throws Exception {
        var clubs=new ClubExporter().exportAllClubs(pid(),FmOffsets.DEFAULT_BUILD,null);
        assertThat(clubs.identityProfile()).isEqualTo(NativeModuleProfile.FM27_SHA256);
        assertThat(clubs.nativeRecords()).hasSize(23998);
        assertThat(clubs.nativeRecords()).extracting(com.github.fmaiassistent.domain.NativeClubSnapshot::uniqueId).doesNotHaveDuplicates();
        assertThat(clubs.nativeRecords().stream().filter(r->r.uefaCoefficientHex()!=null).count()).isEqualTo(812);
        assertThat(clubs.nativeRecords().stream().filter(r->r.financialLedgerHex()!=null).count()).isEqualTo(370);
        assertThat(clubs.nativeRecords().stream().mapToLong(r->r.financialLoansHex()==null?0:r.financialLoansHex().length()/56).sum()).isEqualTo(406);
        PlayerSnapshotValidator.validateClubs(clubs.rows(),35490);
        var mapper=JsonMapper.builder().build();tools.jackson.databind.JsonNode capture;
        try(var in=Files.newInputStream(Path.of(System.getProperty("fm27.live.clubs.capture",
                "target/fm27-restart/club_nested_inventory.json")))) {
            byte[] bytes=in.readNBytes(262145);assertThat(bytes.length).isLessThanOrEqualTo(262144);capture=mapper.readTree(bytes);
        }
        for(var sample:capture.get("samples")) {
            long uid=sample.get("uid").asLong();
            var record=clubs.nativeRecords().stream().filter(r->r.uniqueId()==uid).findFirst().orElseThrow();
            assertThat(record.prefixHex()).isEqualTo(sample.get("clubPrefix").asString());
            var rows=clubs.rows().stream().filter(r->r.get("uniqueId") instanceof Number n&&n.longValue()==uid).toList();
            if(rows.isEmpty())continue; // A canonical identity may have no qualifying contextual TEAM view.
            byte[] raw=java.util.HexFormat.of().parseHex(sample.get("financeRaw").asString());
            var b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(var row:rows) {
                assertThat(row.get("balance")).isEqualTo((long)b.getInt(0xc));
                if(raw.length>0x38) {
                    assertThat(row.get("transferBudget")).isEqualTo((long)b.getInt(0x7c4));
                    assertThat(row.get("payrollBudget")).isEqualTo((long)b.getInt(0x808));
                    assertThat(row.get("currentWagesIncludingBonuses")).isEqualTo((long)b.getInt(0x814));
                }
            }
        }
        var primary=new CompetitionExporter().exportAllCompetitions(pid(),FmOffsets.DEFAULT_BUILD,null);
        PlayerSnapshotValidator.validateCompetitions(primary.rows(),35490);
        var competitions=CompetitionExporter.withClubReferences(primary,clubs);
        Map<String,Object> report=new LinkedHashMap<>();report.put("nativeClubs",clubs.nativeRecords().size());
        report.put("clubViews",clubs.rows().size());report.put("catalogueCompetitions",primary.rows().size());report.put("competitionsWithReferences",competitions.rows().size());
        report.put("competitionNames",clubs.competitionNames());report.put("profile",clubs.identityProfile());
        report.put("selectedRows",clubs.rows().stream().filter(r->List.of("Feyenoord","Chelsea").contains(r.get("name"))).toList());
        Files.write(Path.of("target/fm27-clubs-production-live.json"),mapper.writeValueAsBytes(report));
    }

    @Test
    void productionPeopleExportMatchesIndependentTypedCensusAndFutureEmploymentRecords() throws Exception {
        var result=new PeopleExporter().exportAllPeople(pid(),FmOffsets.DEFAULT_BUILD,null);
        assertThat(result.players().rows()).hasSize(35490);
        assertThat(result.staff().rows()).hasSize(23556);
        PlayerSnapshotValidator.validate(result.players().rows(),94815,result.players().gameDate());
        PlayerSnapshotValidator.validateStaff(result.staff().rows(),result.staff().gameDate(),35490);
        var owners=new java.util.ArrayList<>(result.players().rows());owners.addAll(result.staff().rows());
        assertThat(owners).allSatisfy(row->assertThat(row.get("_contract_profile")).isEqualTo(NativeModuleProfile.FM27_SHA256));
        long contracts=owners.stream().map(r->(List<?>)r.get("_contracts")).mapToLong(List::size).sum();
        assertThat(contracts).isEqualTo(50948);
        var futures=owners.stream().flatMap(r->((List<?>)r.get("_future_contracts")).stream())
                .map(FutureTransferSnapshot.class::cast).toList();
        assertThat(futures).hasSize(34);
        long compTerms=owners.stream().flatMap(r->((List<?>)r.get("_contracts")).stream())
                .map(ContractSnapshot.class::cast).flatMap(c->c.terms().stream())
                .filter(t->"FULL_80".equals(t.group())).count();
        assertThat(compTerms).isEqualTo(4077);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("players",35490);report.put("staff",23556);report.put("contracts",contracts);
        report.put("futureContracts",futures);report.put("competitionTerms",compTerms);report.put("gameDate",result.players().gameDate());
        report.put("profile",NativeModuleProfile.FM27_SHA256);report.put("diagnostics",result.diagnostics());
        report.put("samplePlayers",result.players().rows().stream().filter(r->String.valueOf(r.get("name")).contains("St. Juste")).toList());
        Files.write(Path.of("target/fm27-people-production-live.json"),JsonMapper.builder().build().writeValueAsBytes(report));
    }
}
