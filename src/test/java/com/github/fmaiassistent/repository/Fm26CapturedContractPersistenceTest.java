package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.exporter.ContractCaptureFixture;
import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.player.PlayerMapper;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests persistence of independently decoded captured fields, not live extraction or UI wages. */
@DataJpaTest(properties = {"spring.jpa.show-sql=false", "logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class, ContractRepository.class})
class Fm26CapturedContractPersistenceTest {
    @Autowired private SnapshotDatabaseWriter writer;
    @Autowired private PlayerRepository players;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ContractRepository contracts;

    @Test void canonicalClubNamesJoinOnceAcrossAliasesAndRespectOwnerNamespaceAndBuild()throws Exception {
        var f=ContractCaptureFixture.ownedTeam(false);var snapshot=f.decode().getFirst();
        long uid=snapshot.teamOwnerUid();long address=0x10000000L;
        var prefix=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(0x7FF988310000L+0x44BA518).putInt(0).putInt((int)uid).array();
        var record=new com.github.fmaiassistent.domain.NativeClubSnapshot(uid,0,"CLUB","Native club",address,HexFormat.of().formatHex(prefix));
        var row=Map.<String,Object>of("sourceAddress",address,"uniqueId",uid,"name","View A");
        var alias=Map.<String,Object>of("sourceAddress",address,"uniqueId",uid,"name","View B");
        writer.saveClubs(new com.github.fmaiassistent.exporter.ClubExporter.ExportResult(List.of(row,alias),List.of(record),
                com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256),Map.of());
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(Map.of("unique_id",f.uid,"_contracts",f.decode()))),Map.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs WHERE unique_id=?",Long.class,uid)).isEqualTo(2);
        var stored=contracts.findPlayerContracts(1L,f.uid);
        assertThat(stored).hasSize(f.decode().size());assertThat(stored.getFirst().clubName()).isEqualTo("Native club");
        assertThat(stored.getFirst().snapshot().terms()).hasSize(snapshot.terms().size());
        jdbc.update("UPDATE player_contracts SET team_owner_kind='NATIONAL_TEAM_CONTAINER'");
        assertThat(contracts.findPlayerContracts(1L,f.uid)).allSatisfy(c->assertThat(c.clubName()).isNull());
        jdbc.update("UPDATE player_contracts SET team_owner_kind='CLUB', module_sha256='different-build'");
        assertThat(contracts.findPlayerContracts(1L,f.uid)).allSatisfy(c->assertThat(c.clubName()).isNull());
        assertThatThrownBy(()->writer.saveNativeClubs(new com.github.fmaiassistent.exporter.ClubExporter.ExportResult(
                List.of(Map.of("sourceAddress",address,"uniqueId",uid+1)),List.of(record),
                com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @org.junit.jupiter.api.Tag("fm26-live")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="fm26.club.reference.persistence",matches="true")
    void actualClubCompetitionReferencesSurviveFilteredCatalogueAsNullableLookupNodes()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        var clubs=new com.github.fmaiassistent.exporter.ClubExporter().exportAllClubs(pid,0x238BDD,null);
        var primary=new com.github.fmaiassistent.exporter.CompetitionExporter().exportAllCompetitions(pid,0x238BDD,null);
        var complete=com.github.fmaiassistent.exporter.CompetitionExporter.withClubReferences(primary,clubs);
        var ids=writer.saveCompetitions(complete);writer.saveClubs(clubs,ids);
        assertThat(clubs.nativeRecords()).hasSize(26839);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records",Long.class)).isEqualTo(26839);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs WHERE unique_id IS NULL",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs v JOIN club_records n ON n.unique_id=v.unique_id WHERE n.source_address<>v.source_address",Long.class)).isZero();
        long referenceCount=complete.rows().stream().filter(r->Boolean.TRUE.equals(r.get("_reference_only"))).count();
        assertThat(referenceCount).isEqualTo(14);assertThat(complete.rows()).hasSize(primary.rows().size()+14);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clubs WHERE competition_id IS NULL",Long.class)).isZero();
        for(var row:complete.rows())if(Boolean.TRUE.equals(row.get("_reference_only"))) {
            var stored=jdbc.queryForMap("SELECT name,nation,reputation,gender FROM competitions WHERE source_address=?",row.get("sourceAddress"));
            assertThat(stored.get("NAME")).isEqualTo(row.get("name"));assertThat(stored.get("GENDER")).isEqualTo(row.get("gender"));
            assertThat(stored.get("NATION")).isNull();assertThat(stored.get("REPUTATION")).isNull();
        }
        String output=System.getProperty("fm26.club.reference.persistence.report");
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "pid",pid,"clubRows",clubs.rows().size(),"primaryCompetitions",primary.rows().size(),"referenceOnlyCompetitions",referenceCount,
                "allCompetitions",complete.rows().size(),"missingClubCompetitionForeignKeys",0,
                "optionalCountryAndReputationNull",true,"database","isolated test memory database; transaction rolled back")),java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    @Test void capturedFutureContractUsesOfferIdentityAndRoundTripsWithoutReplacingCurrentSlot()throws Exception {
        var fixture=ContractCaptureFixture.futureTransfer(0);
        var future=new com.github.fmaiassistent.exporter.ContractExporter(fixture,fixture.base,
                com.github.fmaiassistent.memory.ContractRecordLayout.current())
                .readFutureEmployment(ContractCaptureFixture.PERSON,fixture.uid,74).orElseThrow();
        var outer=new com.github.fmaiassistent.domain.FutureTransferSnapshot(LocalDate.of(2036,6,16),future);
        // A separately captured active contract is deliberately re-owned here for a persistence-only collision fixture.
        var old=ContractCaptureFixture.full().decode().getFirst();
        var current=new ContractSnapshot(old.kind(),-1,fixture.uid,old.teamUid(),old.agreedCode(),old.baseFee(),old.totalFee(),
                old.feeKind(),old.threshold(),old.played(),old.competitionIndex(),old.competitionUid(),old.conditionKind(),
                old.internalCompetition(),old.context1(),old.context2(),old.flagsA0(),old.flagsA8(),old.coreHex(),old.auxiliaryHex(),
                old.terms(),old.teamOwnerKind(),old.teamOwnerUid());
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(Map.of("unique_id",fixture.uid,"_contracts",List.of(current),
                "_future_contracts",List.of(outer)))),Map.of());
        var stored=contracts.findPlayerContracts(1L,fixture.uid);assertThat(stored).hasSize(2);
        assertThat(stored.getFirst().snapshot()).isEqualTo(current);assertThat(stored.getFirst().futureTransfer()).isNull();
        assertThat(stored.getLast().futureTransfer()).isEqualTo(outer);assertThat(stored.getLast().snapshot()).isEqualTo(future.contract());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE slot=-1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE source='FUTURE_EMPLOYMENT' AND slot IS NULL AND offer_index=74",Integer.class)).isEqualTo(1);
        assertThat(contracts.findPlayerContracts(1L,fixture.uid+1)).isEmpty();
    }

    @Test void futureStaffOwnershipDoesNotCollideWithPlayerIdAndRejectsMalformedMetadata()throws Exception {
        var fixture=ContractCaptureFixture.futureEmployment(0);
        var e=new com.github.fmaiassistent.exporter.ContractExporter(fixture,fixture.base,
                com.github.fmaiassistent.memory.ContractRecordLayout.current()).readFutureEmployment(ContractCaptureFixture.PERSON,fixture.uid,74).orElseThrow();
        var future=new com.github.fmaiassistent.domain.FutureTransferSnapshot(null,e);
        // Persistence-only staff ownership fixture, not native evidence of a staff future transfer.
        writer.saveStaff(new com.github.fmaiassistent.exporter.StaffExporter.ExportResult("",List.of(Map.of("unique_id",fixture.uid,"_future_contracts",List.of(future)))),Map.of());
        assertThat(contracts.findStaffContracts(1L,fixture.uid).getFirst().futureTransfer()).isEqualTo(future);
        assertThat(contracts.findPlayerContracts(1L,fixture.uid)).isEmpty();
        var invalid=new com.github.fmaiassistent.domain.FutureEmploymentSnapshot(e.offerIndex(),e.state(),e.recordHex(),e.wageRaw()+1,e.endDate(),false,e.contract());
        assertThatThrownBy(()->writer.savePlayers(new PlayerExporter.ExportResult("",List.of(Map.of("unique_id",fixture.uid,"_future_contracts",
                List.of(new com.github.fmaiassistent.domain.FutureTransferSnapshot(null,invalid))))),Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("representation");
    }

    @Test void capturedBasicStaffContractRoundtripsItsFullCoreAndClubOwnership() throws Exception {
        var captured=ContractCaptureFixture.basicStaff(0);var snapshots=captured.decode();
        writer.saveStaff(new com.github.fmaiassistent.exporter.StaffExporter.ExportResult("",List.of(
                Map.of("unique_id",captured.uid,"_contracts",snapshots))),Map.of());
        assertThat(contracts.findStaffContracts(1L,captured.uid)).extracting(c->c.snapshot()).containsExactlyElementsOf(snapshots);
        assertThat(jdbc.queryForObject("SELECT kind FROM player_contracts WHERE staff_id=1",String.class)).isEqualTo("BASIC");
        assertThat(jdbc.queryForObject("SELECT LENGTH(core_hex) FROM player_contracts WHERE staff_id=1",Integer.class)).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT team_owner_uid FROM player_contracts WHERE staff_id=1",Long.class)).isEqualTo(432L);
    }

    @Test
    @org.junit.jupiter.api.Tag("fm26-live")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="fm26.live",matches="true")
    void fullLivePlayerAndStaffContractsPersistInAnIsolatedSnapshot() throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);
        assertThat(pid).isPositive();
        var exported=new com.github.fmaiassistent.exporter.PeopleExporter().exportAllPeople(pid,0x238BDD,null);
        writer.savePlayers(exported.players(),Map.of());
        writer.saveStaff(exported.staff(),Map.of());
        long expectedContracts=0,expectedTerms=0,futureContracts=0;
        for(var rows:List.of(exported.players().rows(),exported.staff().rows())) {
            boolean staff=rows==exported.staff().rows();
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i);
                assertThat(row.get("_contract_profile")).isEqualTo(com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256);
                @SuppressWarnings("unchecked") var snapshots=(List<ContractSnapshot>)row.get("_contracts");
                expectedContracts+=snapshots.size();
                expectedTerms+=snapshots.stream().mapToLong(c->c.terms().size()).sum();
                @SuppressWarnings("unchecked") var futures=(List<com.github.fmaiassistent.domain.FutureTransferSnapshot>)row.get("_future_contracts");
                expectedContracts+=futures.size();futureContracts+=futures.size();
                expectedTerms+=futures.stream().mapToLong(f->f.employment().contract().terms().size()).sum();
                if(i<32) {
                    long uid=((Number)row.get("unique_id")).longValue();
                    var stored=staff?contracts.findStaffContracts(i+1L,uid):contracts.findPlayerContracts(i+1L,uid);
                    assertThat(stored.stream().filter(c->c.futureTransfer()==null).map(c->c.snapshot()).toList()).containsExactlyElementsOf(snapshots);
                    assertThat(stored.stream().filter(c->c.futureTransfer()!=null).map(c->c.futureTransfer()).toList()).containsExactlyInAnyOrderElementsOf(futures);
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Long.class)).isEqualTo(expectedContracts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms",Long.class)).isEqualTo(expectedTerms);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts c JOIN players p ON p.id=c.player_id WHERE c.owner_uid<>p.unique_id",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts c JOIN staff s ON s.id=c.staff_id WHERE c.owner_uid<>s.unique_id",Long.class)).isZero();
        String output=System.getProperty("fm26.live.contract.persistence.report");
        if(output!=null) {
            var report=new java.util.LinkedHashMap<String,Object>(Map.of(
                "pid",pid,"players",exported.players().rows().size(),"staff",exported.staff().rows().size(),
                "contracts",expectedContracts,"futureContracts",futureContracts,"terms",expectedTerms,"sampledOwnerRowsPerDomain",32,
                "database","isolated test memory database; transaction rolled back", "status","PERSISTENCE_MATCH",
                "limitations","Club relationships absent in this test; term equality covers first32 owners per domain, counts/ownership cover all rows; native field semantics and browser rendering not proven."));
            report.put("futureByOwner",jdbc.queryForList("SELECT CASE WHEN player_id IS NULL THEN 'STAFF' ELSE 'PLAYER' END AS owner_kind,COUNT(*) AS contracts FROM player_contracts WHERE source='FUTURE_EMPLOYMENT' GROUP BY owner_kind"));
            report.put("futureTermsByGroup",jdbc.queryForList("SELECT t.term_group,COUNT(*) AS terms FROM contract_terms t JOIN player_contracts c ON c.id=t.contract_id WHERE c.source='FUTURE_EMPLOYMENT' GROUP BY t.term_group"));
            Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    @Test
    void actualStaffContractUsesStaffOwnershipEvenWhenThePlayerDatabaseIdCollides() throws Exception {
        var playerFixture = ContractCaptureFixture.full();
        var staffFixture = ContractCaptureFixture.standaloneStaff(0);
        writer.savePlayers(new PlayerExporter.ExportResult("", List.of(Map.of(
                "unique_id", playerFixture.uid, "name", "Player", "_contracts", playerFixture.decode()))), Map.of());
        writer.saveStaff(new com.github.fmaiassistent.exporter.StaffExporter.ExportResult("", List.of(Map.of(
                "unique_id", staffFixture.uid, "name", "Staff", "_contracts", staffFixture.decode()))), Map.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE player_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE staff_id=1 AND player_id IS NULL AND owner_uid=156", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms t JOIN player_contracts c ON c.id=t.contract_id WHERE c.staff_id=1 AND t.code_raw=15 AND t.condition_raw=25 AND t.value_raw=-1", Integer.class)).isEqualTo(1);
        assertThat(contracts.findStaffContracts(1L, staffFixture.uid)).extracting(c -> c.snapshot()).containsExactlyElementsOf(staffFixture.decode());
        assertThat(contracts.findPlayerContracts(1L, playerFixture.uid)).extracting(c -> c.snapshot()).containsExactlyElementsOf(playerFixture.decode());
        assertThat(contracts.findStaffContracts(1L, playerFixture.uid)).isEmpty();
        assertThat(contracts.findPlayerContracts(1L, staffFixture.uid)).isEmpty();
        assertThat(contracts.findStaffContracts(1L, 999_999L)).isEmpty();
    }

    @Test
    void contractReaderRejectsMoreThanTheNativeFiveSlotsAndHandlesMissingSelections() {
        writer.saveStaff(new com.github.fmaiassistent.exporter.StaffExporter.ExportResult("", List.of(Map.of("unique_id", 156L))),Map.of());
        for(int slot=-1;slot<5;slot++) jdbc.update("INSERT INTO player_contracts(id,staff_id,kind,slot,owner_uid) VALUES(?,1,'FULL',?,156)",slot+2,slot);
        assertThatThrownBy(() -> contracts.findStaffContracts(1L,156L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("slot limit");
        assertThat(contracts.findPlayerContracts(null,156L)).isEmpty();
        assertThat(contracts.findStaffContracts(0L,156L)).isEmpty();
        assertThat(contracts.findStaffContracts(1L,null)).isEmpty();
    }

    @Test
    void actualProductionSnapshotPersistsBothContractClubOwnersForTheSamePlayer() throws Exception {
        byte[] data;
        try(var input=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/contract_pipeline_selected_owners_live_20261023.json"))){data=input.readNBytes(4_194_305);}
        assertThat(data.length).isLessThanOrEqualTo(4_194_304);
        var mapper=JsonMapper.builder().build();List<ContractSnapshot> contracts=new ArrayList<>();
        for(var row:mapper.readTree(data).get("rows"))if(row.get("unique_id").asLong()==43390774L)
            for(var item:row.get("_contracts"))contracts.add(mapper.treeToValue(item,ContractSnapshot.class));
        assertThat(contracts).hasSize(2);
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(Map.of("unique_id",43390774L,"_contracts",contracts))),Map.of());
        assertThat(jdbc.queryForList("SELECT team_owner_uid FROM player_contracts ORDER BY slot",Long.class)).containsExactly(1123L,1149L);
        assertThat(jdbc.queryForList("SELECT team_owner_kind FROM player_contracts ORDER BY slot",String.class)).containsExactly("CLUB","CLUB");
    }

    @Test
    void contractTeamOwnerPersistsKindAndUidWithoutConflatingNationalTeamId() throws Exception {
        var f=ContractCaptureFixture.ownedTeam(true);
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(Map.of("unique_id",f.uid,"_contracts",f.decode()))),Map.of());
        assertThat(jdbc.queryForObject("SELECT team_uid FROM player_contracts",Long.class)).isEqualTo(145174L);
        assertThat(jdbc.queryForObject("SELECT team_owner_uid FROM player_contracts",Long.class)).isEqualTo(765L);
        assertThat(jdbc.queryForObject("SELECT team_owner_kind FROM player_contracts",String.class)).isEqualTo("NATIONAL_TEAM_CONTAINER");
    }

    @Test
    void directLoanBonusCollectionsReachH2AlongsideSharedTerms() throws Exception {
        var f = ContractCaptureFixture.loanWithDirectTerms();
        writer.savePlayers(new PlayerExporter.ExportResult("",List.of(
                Map.of("unique_id",f.uid,"_contracts",f.decode()))),Map.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms WHERE term_group='LOAN_70'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms WHERE term_group='LOAN_88'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT reference_uid FROM contract_terms WHERE reference_kind='AWARD' ORDER BY ordinal",Long.class))
                .containsExactly(150936L,150937L);
        assertThat(jdbc.queryForObject("SELECT total_fee FROM player_contracts WHERE kind='LOAN'",Long.class)).isEqualTo(698277L);
    }

    @Test
    void productionDecoderPersistsBothContractsAndLosslessTermRowsWithPlayerForeignKeys() throws Exception {
        var fixture = ContractCaptureFixture.loan();
        var snapshots = fixture.decode();
        writer.savePlayers(new PlayerExporter.ExportResult("2036-05-18", List.of(
                Map.of("unique_id", fixture.uid, "name", "captured loan", "_contracts", snapshots))), Map.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total_fee FROM player_contracts WHERE kind='LOAN'", Long.class)).isEqualTo(698277L);
        assertThat(jdbc.queryForObject("SELECT match_threshold FROM player_contracts WHERE kind='LOAN'", Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForList("SELECT agreed_code FROM player_contracts ORDER BY slot", Integer.class)).containsExactly(2, 4);
        assertThat(jdbc.queryForObject("SELECT code_raw FROM contract_terms WHERE term_group='SHARED_00'", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT condition_raw FROM contract_terms WHERE term_group='SHARED_18'", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts c JOIN players p ON p.id=c.player_id WHERE c.owner_uid=p.unique_id", Integer.class)).isEqualTo(2);
        new DatabaseService(jdbc).clearAllTables();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts", Integer.class)).isZero();
    }

    @Test
    void retainedActualProductionCaptureReachesH2WithCompetitionAndOpaqueBonusRows() throws Exception {
        byte[] data;
        try (var input = Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/contract_pipeline_selected_final_live_20261023.json"))) {
            data = input.readNBytes(4_194_305);
        }
        assertThat(data.length).isLessThanOrEqualTo(4_194_304);
        var mapper = JsonMapper.builder().build();
        var capture = mapper.readTree(data);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (var source : capture.get("rows")) {
            List<ContractSnapshot> snapshots = new ArrayList<>();
            for (var contract : source.get("_contracts")) snapshots.add(mapper.treeToValue(contract, ContractSnapshot.class));
            rows.add(Map.of("unique_id",source.get("unique_id").asLong(),"_contracts",snapshots));
        }
        writer.savePlayers(new PlayerExporter.ExportResult("",rows),Map.of());
        assertThat(rows).hasSize(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts",Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms",Integer.class)).isEqualTo(53);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE competition_uid IN (11,33)",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT MAX(matches_played) FROM player_contracts WHERE match_threshold=20",Integer.class)).isEqualTo(36);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms WHERE term_group IN ('FULL_80','FULL_98')",Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms WHERE reference_uid IS NOT NULL",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts WHERE team_owner_uid IS NOT NULL",Integer.class)).isZero();
    }

    @Test
    void normalizedActualProductionAwardReferencesReachH2() throws Exception {
        byte[] data;
        try (var input = Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/contract_pipeline_selected_references_live_20261023.json"))) {
            data=input.readNBytes(4_194_305);
        }
        assertThat(data.length).isLessThanOrEqualTo(4_194_304);
        var mapper=JsonMapper.builder().build();
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var player:mapper.readTree(data).get("rows")) {
            if(player.get("unique_id").asLong()!=49048469L) continue;
            List<ContractSnapshot> contracts=new ArrayList<>();
            for(var raw:player.get("_contracts")) contracts.add(mapper.treeToValue(raw,ContractSnapshot.class));
            rows.add(Map.of("unique_id",49048469L,"_contracts",contracts));
        }
        assertThat(rows).hasSize(1);
        writer.savePlayers(new PlayerExporter.ExportResult("",rows),Map.of());
        assertThat(jdbc.queryForList("SELECT reference_uid FROM contract_terms WHERE reference_kind='AWARD' ORDER BY ordinal",Long.class))
                .containsExactly(150936L,150937L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedChildWriteRollsBackSnapshotDeletionAndRestoresPreviousContracts() throws Exception {
        var f = ContractCaptureFixture.loan(); var valid = f.decode();
        var transaction = new TransactionTemplate(transactions);
        var database = new DatabaseService(jdbc);
        try {
            transaction.executeWithoutResult(s -> {
                database.clearAllTables();
                writer.savePlayers(new PlayerExporter.ExportResult("2036-05-18", List.of(
                        Map.of("unique_id", f.uid, "_contracts", valid))), Map.of());
            });
            var original = valid.get(1);
            var bad = new ContractSnapshot(original.kind(), original.slot(), original.ownerUid(), original.teamUid(),
                    original.agreedCode(), original.baseFee(), original.totalFee(), original.feeKind(), original.threshold(),
                    original.played(), original.competitionIndex(), original.competitionUid(), original.conditionKind(),
                    original.internalCompetition(), original.context1(), original.context2(), original.flagsA0(), original.flagsA8(),
                    original.coreHex(), original.auxiliaryHex(), List.of(original.terms().getFirst(), original.terms().getFirst()));
            assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
                database.clearAllTables();
                writer.savePlayers(new PlayerExporter.ExportResult("2036-05-18", List.of(
                        Map.of("unique_id", f.uid, "_contracts", List.of(bad)))), Map.of());
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player_contracts", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms", Integer.class)).isEqualTo(2);
        } finally { transaction.executeWithoutResult(s -> database.clearAllTables()); }
    }

    @Test
    void capturedPrimaryContractFieldsSurviveJdbcJpaAndPublicMapping() throws Exception {
        byte[] fixture;
        try (var input = Files.newInputStream(Path.of(
                "docs/reverse-engineering/fm26/evidence/loyalty_contract_live_20261020.json"))) {
            fixture = input.readNBytes(65_537);
        }
        assertThat(fixture.length).isLessThanOrEqualTo(65_536);
        var samples = JsonMapper.builder().build().readTree(fixture)
                .get("returnValues").get(0).get("players");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var sample : samples) {
            byte[] raw = HexFormat.of().parseHex(sample.get("bytes").asString().replace(" ", ""));
            assertThat(raw).hasSize(0xC8);
            ByteBuffer nativeContract = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            // The fixture has no alternate-expiry flag. These offsets come from native evidence,
            // independently of PlayerRecordLayout. Annual salary is deliberately not inferred.
            assertThat(raw[0x2B] & 8).isZero();
            long salary = Integer.toUnsignedLong(nativeContract.getInt(0x20));
            int day = Short.toUnsignedInt(nativeContract.getShort(0x48)) & 0x1FF;
            int year = Short.toUnsignedInt(nativeContract.getShort(0x4A));
            Map<String, Object> row = new HashMap<>();
            row.put("unique_id", sample.get("uid").asLong());
            row.put("name", sample.get("name").asString());
            row.put("salary_weekly_raw", salary);
            row.put("contract_end_date", LocalDate.ofYearDay(year, day).toString());
            rows.add(row);
        }
        assertThat(rows).hasSize(3);
        writer.savePlayers(new PlayerExporter.ExportResult("2036-05-18", rows), Map.of());
        assertThat(players.count()).isEqualTo(3);
        for (var row : rows) {
            var entity = players.findFirstByUniqueId((Long) row.get("unique_id")).orElseThrow();
            var mapped = new PlayerMapper().apply(entity);
            assertThat(mapped).containsEntry("UNIQUE_ID", row.get("unique_id"))
                    .containsEntry("NAME", row.get("name"))
                    .containsEntry("CONTRACT_END_DATE", row.get("contract_end_date"));
            assertThat(((Number) mapped.get("SALARY_WEEKLY_RAW")).longValue())
                    .isEqualTo(row.get("salary_weekly_raw"));
            assertThat(entity.getSalaryPa()).isNull();
        }
    }

    @Test
    void absentTransferEvidenceCanRemainNullInsteadOfBecomingFalseInPersistence() {
        writer.savePlayers(new PlayerExporter.ExportResult("2036-05-18", List.of(
                Map.of("unique_id", 1L),
                Map.of("unique_id", 2L, "transfer_agreed", false))), Map.of());
        assertThat(players.findFirstByUniqueId(1L).orElseThrow().getTransferAgreed()).isNull();
        assertThat(players.findFirstByUniqueId(2L).orElseThrow().getTransferAgreed()).isFalse();
    }
}
