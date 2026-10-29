package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.*;
import com.github.fmaiassistent.memory.CompetitionNameLayout;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

public class ContractCompetitionNameCaptureTest {
    public static Map<String,Object> owner(ContractCaptureFixture f,boolean allReferences)throws Exception {
        var c=f.decode().getFirst();
        if(allReferences) {
            // Relocate retained raw payloads into one synthetic owner for name-census coverage only.
            var terms=new ArrayList<ContractSnapshot.Term>();
            for(var r:AwardNameCaptureTest.json("competition_bonus_references_checkpoint56_verified_v3_20261027.json").get("normalizedRows"))
                terms.add(new ContractSnapshot.Term("FULL_80",terms.size(),r.get("rawHex").asString(),null,null,null,null,null,
                        new ContractSnapshot.CompetitionReferences(r.get("cbcpCompetitionUid").isNull()?null:r.get("cbcpCompetitionUid").asLong(),
                                r.get("conditionCompetitionUid").isNull()?null:r.get("conditionCompetitionUid").asLong())));
            c=new ContractSnapshot(c.kind(),c.slot(),c.ownerUid(),c.teamUid(),c.agreedCode(),c.baseFee(),c.totalFee(),c.feeKind(),
                    c.threshold(),c.played(),c.competitionIndex(),c.competitionUid(),c.conditionKind(),c.internalCompetition(),c.context1(),c.context2(),
                    c.flagsA0(),c.flagsA8(),c.coreHex(),c.auxiliaryHex(),terms,c.teamOwnerKind(),c.teamOwnerUid());
        }
        var row=new HashMap<String,Object>();row.put("unique_id",f.uid);row.put("_contract_profile",ContractExporter.MODULE_SHA256);row.put("_contracts",List.of(c));return row;
    }
    @SuppressWarnings("unchecked") public static List<NativeCompetitionNameSnapshot> names(Map<String,Object> row){return (List<NativeCompetitionNameSnapshot>)row.get("_contract_competition_names");}

    @Test void all30ReferencedNamesMatchIndependentCaptureWithOnlyOnePrefixCaptureAndRecheck()throws Exception {
        var f=ContractCaptureFixture.competitionBonus(0);var row=owner(f,true);
        new ContractCompetitionNameExporter(f,f.base,CompetitionNameLayout.current()).captureRows(List.of(row));
        var found=new HashMap<Long,NativeCompetitionNameSnapshot>();for(var n:names(row))found.put(n.uniqueId(),n);
        assertThat(found).hasSize(30);
        for(var expected:AwardNameCaptureTest.json("competition_bonus_references_checkpoint56_live_20261027.json").get("returnValues").get(0).get("names")) {
            var n=found.get(expected.get("uid").asLong());assertThat(n.recordIndex()).isEqualTo(expected.get("index").asInt());
            assertThat(n.nameHex()).isEqualToIgnoringCase(expected.get("names").get(0).get("raw").asString());
            assertThat(n.shortNameHex()).isEqualToIgnoringCase(expected.get("names").get(1).get("raw").asString());
            assertThat(CompetitionNameLayout.current().decode(n.nameHex(),n.shortNameHex()).name()).isEqualTo(n.name());
        }
        assertThat(found.keySet()).contains(127299L,12017574L,2000321669L,2000347777L);
    }

    @Test void finalSlotChangePreventsPublishingNamesToAnyOwner()throws Exception {
        var f=ContractCaptureFixture.competitionBonus(0);var row=owner(f,false);var other=new HashMap<>(row);
        long slot=0x700000+637*8L;f.onRead(slot,f.readCount(slot)+2,()->f.pointer(slot,0x12345678));
        assertThatThrownBy(()->new ContractCompetitionNameExporter(f,f.base,CompetitionNameLayout.current()).captureRows(List.of(row,other)))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("changed during capture");
        assertThat(row).doesNotContainKey("_contract_competition_names");assertThat(other).doesNotContainKey("_contract_competition_names");
    }

    @Test void nullFullNameKeepsShortNameAndUnsupportedSourceReadsNothing()throws Exception {
        var f=ContractCaptureFixture.competitionBonus(0);var row=owner(f,false);long target=f.readU64(0x700000+637*8L);f.pointer(target+0x40,0);
        var exporter=new ContractCompetitionNameExporter(f,f.base,CompetitionNameLayout.current());exporter.captureRows(List.of(row));
        assertThat(names(row).getFirst().name()).isNull();assertThat(names(row).getFirst().shortName()).isNotBlank();
        var unsupported=new HashMap<String,Object>();unsupported.put("_contract_profile","UNSUPPORTED");exporter.captureRows(List.of(unsupported));
        assertThat(unsupported).doesNotContainKey("_contract_competition_names");
    }
}
