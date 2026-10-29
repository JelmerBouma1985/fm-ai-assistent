package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ContractRecordLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ContractExporterTest {
    @Test void actualBasicStaffContractsPreserveTheirCompleteSmallCoreAndRegisteredClubOwner() throws Exception {
        long[] uids={57,359,856},clubs={432,13215537,2000034642};
        for(int i=0;i<uids.length;i++) {
            var fixture=ContractCaptureFixture.basicStaff(i);
            var result=fixture.decode();assertThat(result).hasSize(1);
            var c=result.getFirst();
            assertThat(c.kind()).isEqualTo("BASIC");assertThat(c.ownerUid()).isEqualTo(uids[i]);
            assertThat(c.coreHex()).hasSize(80);assertThat(c.teamOwnerKind()).isEqualTo("CLUB");
            assertThat(c.teamOwnerUid()).isEqualTo(clubs[i]);assertThat(c.teamUid()).isEqualTo(clubs[i]);
            assertThat(c.agreedCode()).isNull();assertThat(c.totalFee()).isNull();assertThat(c.terms()).isEmpty();
        }
    }
    @Test void shiftedBasicAllocationSizeFailsTheAnchorAgainstTheSameCapturedObject() throws Exception {
        var fixture=ContractCaptureFixture.basicStaff(0);var c=ContractRecordLayout.current();
        assertThat(fixture.decode().getFirst().coreHex()).hasSize(80);
        var shifted=new ContractRecordLayout(c.links(),c.full(),c.loan(),c.sharedVectors(),c.fullTerm(),c.sharedTerm(),
                c.bClub(),new ContractRecordLayout.Plain(c.basic().size()-1,c.basic().vtable(),c.basic().kind()),c.award(),c.teamOwner());
        assertThatThrownBy(()->new ContractExporter(fixture,fixture.base,shifted).read(ContractCaptureFixture.PERSON,fixture.uid))
                .isInstanceOf(IOException.class).hasMessageContaining("BASIC contract size/representation anchor");
    }
    @Test void stagedCaptureAcceptsStaffRecordAddressWithoutRequiringAPlayerRowKey() throws Exception {
        var fixture = ContractCaptureFixture.standaloneStaff(0);
        java.util.Map<String,Object> staff = new java.util.HashMap<>();
        staff.put("record_address", "0x" + Long.toHexString(ContractCaptureFixture.PERSON));
        staff.put("unique_id", fixture.uid);
        new ContractExporter(fixture, fixture.base, ContractRecordLayout.current()).captureRows(java.util.List.of(staff));
        assertThat((java.util.List<?>) staff.get("_contracts")).hasSize(1);
        assertThat(staff.get("_contract_profile")).isEqualTo(ContractExporter.MODULE_SHA256);
    }

    @Test void actualStandaloneStaffContractsUseTheSharedPersonOwnerAndFullTermDecoder() throws Exception {
        long[] owners = {156, 166, 356};
        long[] clubs = {1009, 1000, 1014};
        java.util.List<java.util.List<Integer>> codes = java.util.List.of(java.util.List.of(15), java.util.List.of(14, 5), java.util.List.of(15));
        for (int i = 0; i < owners.length; i++) {
            var fixture = ContractCaptureFixture.standaloneStaff(i);
            var contracts = fixture.decode();
            assertThat(contracts).hasSize(1);
            var contract = contracts.getFirst();
            assertThat(contract.kind()).isEqualTo("FULL");
            assertThat(contract.ownerUid()).isEqualTo(owners[i]);
            assertThat(contract.teamOwnerKind()).isEqualTo("CLUB");
            assertThat(contract.teamOwnerUid()).isEqualTo(clubs[i]);
            assertThat(contract.terms()).extracting(t -> t.codeRaw()).containsExactlyElementsOf(codes.get(i));
            assertThat(contract.terms()).allSatisfy(t -> {
                assertThat(t.group()).isEqualTo("FULL_68");
                assertThat(t.valueRaw()).isEqualTo(-1);
                assertThat(t.conditionRaw()).isEqualTo(25);
            });
        }
    }

    @Test void batchReusesRegistryMetadataButPublishesOnlyAfterFinalValidation() throws Exception {
        var f=ContractCaptureFixture.ownedTeam(false);
        var first=new HashMap<String,Object>();first.put("record","0x100000");first.put("unique_id",f.uid);
        var second=new HashMap<String,Object>(first);
        new ContractExporter(f,f.base,ContractRecordLayout.current()).captureRows(List.of(first,second));
        assertThat(first).containsKey("_contracts").containsEntry("_contract_profile",ContractExporter.MODULE_SHA256);
        assertThat(second.get("_contracts")).isEqualTo(first.get("_contracts"));
        assertThat(f.readCount(f.base+ContractRecordLayout.current().links().teamsRegistry())).isEqualTo(2);
        var unstable=ContractCaptureFixture.ownedTeam(false);
        var a=new HashMap<String,Object>();a.put("record","0x100000");a.put("unique_id",unstable.uid);
        var b=new HashMap<String,Object>(a);
        unstable.onRead(ContractCaptureFixture.FULL,6,()->unstable.pointer(unstable.base+ContractRecordLayout.current().links().teamsRegistry(),0x600008));
        assertThatThrownBy(()->new ContractExporter(unstable,unstable.base,ContractRecordLayout.current()).captureRows(List.of(a,b)))
                .isInstanceOf(IOException.class).hasMessageContaining("changed during capture");
        assertThat(a).doesNotContainKeys("_contracts","_contract_profile");assertThat(b).doesNotContainKeys("_contracts","_contract_profile");
    }
    @Test void reusingDecoderResolvesFreshRegistryMetadataAfterARegistryReplacement() throws Exception {
        var f=ContractCaptureFixture.ownedTeam(false);var layout=ContractRecordLayout.current();
        var decoder=new ContractExporter(f,f.base,layout);
        var before=decoder.read(ContractCaptureFixture.PERSON,f.uid);
        f.pointer(f.base+layout.links().teamsRegistry(),0x660000);f.pointer(0x660080,0x661000);
        f.pointer(0x661000,0x670000);f.pointer(0x661008,0x670000+1307*8);f.pointer(0x670000+1306*8,0x700000);
        assertThat(decoder.read(ContractCaptureFixture.PERSON,f.uid)).isEqualTo(before);
        assertThat(f.readCount(f.base+layout.links().teamsRegistry())).isEqualTo(4);
    }
    @Test void registryRootHeaderAndBoundsChangesAtTheEndOfCaptureAreRejected() throws Exception {
        for(int mutation=0;mutation<3;mutation++) {
            var f=ContractCaptureFixture.ownedTeam(false);
            long address=mutation==0?f.base+ContractRecordLayout.current().links().teamsRegistry():mutation==1?0x600080:0x601008;
            long changed=mutation==0?0x600008:mutation==1?0x601008:0x610000+1308*8;
            f.onRead(ContractCaptureFixture.FULL,3,()->f.pointer(address,changed));
            assertThatThrownBy(f::decode).isInstanceOf(IOException.class).hasMessageContaining("changed during capture");
        }
    }
    @Test void teamOwnershipSeparatesClubFromNationalContainerAndPreservesTypedNull() throws Exception {
        var club=ContractCaptureFixture.ownedTeam(false).decode().getFirst();
        assertThat(club.teamUid()).isEqualTo(1744L);
        assertThat(club.teamOwnerKind()).isEqualTo("CLUB");assertThat(club.teamOwnerUid()).isEqualTo(1744L);
        var national=ContractCaptureFixture.ownedTeam(true).decode().getFirst();
        assertThat(national.teamUid()).isEqualTo(145174L);
        assertThat(national.teamOwnerKind()).isEqualTo("NATIONAL_TEAM_CONTAINER");assertThat(national.teamOwnerUid()).isEqualTo(765L);
        var absent=ContractCaptureFixture.ownedTeam(true);absent.pointer(0x700030,0);
        var missing=absent.decode().getFirst();
        assertThat(missing.teamOwnerKind()).isEqualTo("NATIONAL_TEAM_CONTAINER");assertThat(missing.teamOwnerUid()).isNull();
    }
    @Test void shiftedTeamOwnershipOffsetsRootsAndTypesFailAgainstBothCapturedBranches() throws Exception {
        var c=ContractRecordLayout.current();var o=c.teamOwner();
        for(boolean national:List.of(false,true)) {
            var f=ContractCaptureFixture.ownedTeam(national);assertThat(f.decode().getFirst().teamOwnerUid()).isNotNull();
            var wrong=List.of(new ContractRecordLayout.TeamOwner(o.flag()+1,o.reference(),o.clubsRegistry(),o.nationalContainersRegistry(),o.clubVtable(),o.nationalContainerVtable()),
                    new ContractRecordLayout.TeamOwner(o.flag(),o.reference()+1,o.clubsRegistry(),o.nationalContainersRegistry(),o.clubVtable(),o.nationalContainerVtable()),
                    new ContractRecordLayout.TeamOwner(o.flag(),o.reference(),o.clubsRegistry()+(national?0:1),o.nationalContainersRegistry()+(national?1:0),o.clubVtable(),o.nationalContainerVtable()),
                    new ContractRecordLayout.TeamOwner(o.flag(),o.reference(),o.clubsRegistry(),o.nationalContainersRegistry(),o.clubVtable()+(national?0:1),o.nationalContainerVtable()+(national?1:0)));
            for(var shiftedOwner:wrong) {
                var shifted=new ContractRecordLayout(c.links(),c.full(),c.loan(),c.sharedVectors(),c.fullTerm(),c.sharedTerm(),c.bClub(), c.basic(),c.award(),shiftedOwner);
                assertThatThrownBy(()->new ContractExporter(f,f.base,shifted).read(ContractCaptureFixture.PERSON,f.uid)).isInstanceOf(IOException.class);
            }
        }
    }
    @Test void loanKeepsDirectCompetitionAndAwardRowsSeparateFromSharedCollections() throws Exception {
        var loan = ContractCaptureFixture.loanWithDirectTerms().decode().get(1);
        assertThat(loan.terms()).hasSize(5);
        assertThat(loan.terms().stream().map(t -> t.group()).toList())
                .containsExactly("LOAN_70", "LOAN_88", "LOAN_88", "SHARED_00", "SHARED_18");
        assertThat(loan.terms().get(0).rawHex()).isEqualTo("01006c077d020000ffffffffffffffff0a0000001600000000890000ffff0000");
        assertThat(loan.terms().get(1).rawHex()).isEqualTo("e883a0e241020000be4f040000000000");
        assertThat(loan.terms().get(2).ordinal()).isEqualTo(1);
        assertThat(loan.terms().get(1).referenceKind()).isEqualTo("AWARD");
        assertThat(loan.terms().get(1).referenceUid()).isEqualTo(150936L);
        assertThat(loan.terms().get(2).referenceUid()).isEqualTo(150937L);
        assertThat(loan.totalFee()).isEqualTo(698277L); // These separate bonus groups never enter Clff.
    }
    @Test void shiftedAwardReferenceAndRegistryAreRejectedAgainstTheSameCapturedRows() throws Exception {
        var f = ContractCaptureFixture.loanWithDirectTerms();
        assertThat(f.decode().get(1).terms().get(1).referenceUid()).isEqualTo(150936L);
        var c = ContractRecordLayout.current(); var a = c.award();
        for (var bad : List.of(new ContractRecordLayout.Award(a.registry(),a.reference()+1,a.vtable()),
                new ContractRecordLayout.Award(a.registry()+1,a.reference(),a.vtable()),
                new ContractRecordLayout.Award(a.registry(),a.reference(),a.vtable()+1))) {
            var shifted = new ContractRecordLayout(c.links(),c.full(),c.loan(),c.sharedVectors(),
                    c.fullTerm(),c.sharedTerm(),c.bClub(), c.basic(),bad,c.teamOwner());
            assertThatThrownBy(() -> new ContractExporter(f,f.base,shifted).read(ContractCaptureFixture.PERSON,f.uid))
                    .isInstanceOf(IOException.class);
        }
    }
    @Test void nullAwardReferenceIsPreservedAsTypedAbsentRelationship() throws Exception {
        var f = ContractCaptureFixture.loanWithDirectTerms(); f.pointer(0x520000,0);
        var row = f.decode().get(1).terms().get(1);
        assertThat(row.referenceKind()).isEqualTo("AWARD");
        assertThat(row.referenceUid()).isNull();
    }
    @Test void nativeFullRowsKeepDuplicateMilestonesAndSignedConditions() throws Exception {
        var contracts = ContractCaptureFixture.full().decode();
        var terms = contracts.getFirst().terms();
        assertThat(terms).hasSize(5);
        assertThat(terms.stream().filter(t -> t.codeRaw() == 10).map(t -> t.conditionRaw()).toList()).containsExactly(10, 15);
        assertThat(contracts.getFirst().agreedCode()).isEqualTo(3);
        assertThat(terms).allMatch(t -> t.group().equals("FULL_68"));
    }
    @Test void shiftedLoanCollectionLayoutIsRejectedAgainstTheSameNonemptyFixture() throws Exception {
        var f = ContractCaptureFixture.loanWithDirectTerms();
        assertThat(f.decode().get(1).terms()).hasSize(5);
        var c = ContractRecordLayout.current(); var l = c.loan();
        var shiftedLoan = new ContractRecordLayout.Loan(l.size(), l.vtable(), l.shared(), l.flagsA0(), l.flagsA8(),
                l.baseFee(), l.competition(), l.threshold(), l.played(), l.internalCompetition(), l.context1(),
                l.context2(), l.conditionKind(), l.sharedSize(), l.competitionBonuses()+1, l.awards(),
                l.competitionStride(), l.awardStride());
        var shifted = new ContractRecordLayout(c.links(), c.full(), shiftedLoan, c.sharedVectors(),
                c.fullTerm(), c.sharedTerm(), c.bClub(), c.basic(), c.award(), c.teamOwner());
        assertThatThrownBy(() -> new ContractExporter(f, f.base, shifted).read(ContractCaptureFixture.PERSON, f.uid))
                .isInstanceOf(IOException.class).hasMessageContaining("anchor");
        var shiftedAward = new ContractRecordLayout.Loan(l.size(), l.vtable(), l.shared(), l.flagsA0(), l.flagsA8(),
                l.baseFee(), l.competition(), l.threshold(), l.played(), l.internalCompetition(), l.context1(),
                l.context2(), l.conditionKind(), l.sharedSize(), l.competitionBonuses(), l.awards()+1,
                l.competitionStride(), l.awardStride());
        var badAward = new ContractRecordLayout(c.links(), c.full(), shiftedAward, c.sharedVectors(),
                c.fullTerm(), c.sharedTerm(), c.bClub(), c.basic(), c.award(), c.teamOwner());
        assertThatThrownBy(() -> new ContractExporter(f, f.base, badAward).read(ContractCaptureFixture.PERSON, f.uid))
                .isInstanceOf(IOException.class).hasMessageContaining("anchor");
    }
    @Test void typedPrimaryAndLoanRemainDistinctAndAppearanceAmountDoesNotEnterClff() throws Exception {
        var contracts = ContractCaptureFixture.loan().decode();
        assertThat(contracts).hasSize(2);
        assertThat(contracts.get(0).slot()).isEqualTo(-1);
        assertThat(contracts.get(0).agreedCode()).isEqualTo(2);
        var loan = contracts.get(1);
        assertThat(loan.slot()).isZero();
        assertThat(loan.agreedCode()).isEqualTo(4);
        assertThat(loan.feeKind()).isEqualTo("MANDATORY");
        assertThat(loan.threshold()).isEqualTo(100);
        assertThat(loan.totalFee()).isEqualTo(698277L);
        assertThat(loan.terms()).hasSize(2);
        assertThat(loan.terms().get(0).codeRaw()).isEqualTo(6);
        assertThat(loan.terms().get(1).valueRaw()).isEqualTo(162477L);
        assertThat(loan.terms().get(1).conditionRaw()).isEqualTo(50);
    }
    @Test void shiftedLinkLayoutIsRejectedByPointerAnchorAgainstSameNativeFixture() throws Exception {
        var fixture = ContractCaptureFixture.full();
        assertThat(fixture.decode()).hasSize(1);
        var c = ContractRecordLayout.current(); var l = c.links();
        var shifted = new ContractRecordLayout(new ContractRecordLayout.Links(l.primary()+1, l.additional(), l.index(),
                l.uid(), l.owner(), l.team(), l.agreed(), l.registryHeader(), l.teamsRegistry(), l.competitionsRegistry(),
                l.vectorEnd(), l.vectorCapacity(), l.additionalSlots()), c.full(), c.loan(), c.sharedVectors(), c.fullTerm(), c.sharedTerm(), c.bClub(), c.basic(), c.award(), c.teamOwner());
        assertThatThrownBy(() -> new ContractExporter(fixture, fixture.base, shifted)
                .read(ContractCaptureFixture.PERSON, fixture.uid)).isInstanceOf(IOException.class).hasMessageContaining("anchor");
    }
    @Test void oversizedCollectionFailsBeforePayloadReadOrAllocation() throws Exception {
        var f = ContractCaptureFixture.full();
        f.pointer(ContractCaptureFixture.FULL + 0x68, 0x500000);
        f.pointer(ContractCaptureFixture.FULL + 0x70, 0x500000 + 2049 * 8);
        f.pointer(ContractCaptureFixture.FULL + 0x78, 0x500000 + 2049 * 8);
        assertThatThrownBy(f::decode).isInstanceOf(IOException.class).hasMessageContaining("bounds/count anchor");
        assertThat(f.largestRead).isLessThanOrEqualTo(248);
    }
    @Test void nativeBClubIsASeparate104ByteAdditionalContract() throws Exception {
        var snapshots = ContractCaptureFixture.bClub().decode();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().kind()).isEqualTo("B_CLUB");
        assertThat(snapshots.getFirst().slot()).isEqualTo(1);
        assertThat(snapshots.getFirst().coreHex()).hasSize(208);
        assertThat(snapshots.getFirst().agreedCode()).isNull(); // Native override341C950 does not read raw54.
        assertThat(java.util.HexFormat.of().parseHex(snapshots.getFirst().coreHex())[0x54]).isEqualTo((byte) 11);
        assertThat(snapshots.getFirst().terms()).isEmpty();
    }
    @Test void unsupportedPlatformAndModuleStayExplicitlyUnavailable(@TempDir Path temp) throws Exception {
        var row = new HashMap<String,Object>(); row.put("unique_id", 1L);
        ContractExporter.capture(ContractCaptureFixture.full(), null, List.of(row));
        assertThat(row).containsEntry("_contract_profile", "UNSUPPORTED_PLATFORM_OR_MODULE").doesNotContainKey("_contracts");
        Path bad = temp.resolve("game_plugin.dll"); Files.write(bad, new byte[8]);
        assertThat(ContractExporter.supportedModule(bad)).isFalse();
    }
}
