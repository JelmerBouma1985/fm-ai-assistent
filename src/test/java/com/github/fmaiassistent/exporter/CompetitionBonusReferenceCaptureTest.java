package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;

class CompetitionBonusReferenceCaptureTest {
    @Test void winningAndQualificationResolveIndependentTypedCompetitionUids()throws Exception {
        var winning=ContractCaptureFixture.competitionBonus(0).decode().getFirst().terms().getFirst();
        assertThat(winning.competitionReferences().subjectUid()).isEqualTo(1301394L);
        assertThat(winning.competitionReferences().conditionUid()).isNull();
        assertThat(winning.referenceKind()).isNull();assertThat(winning.referenceUid()).isNull();
        var qualifying=ContractCaptureFixture.competitionBonus(1).decode().getFirst().terms().getFirst();
        assertThat(qualifying.competitionReferences().subjectUid()).isEqualTo(32L);
        assertThat(qualifying.competitionReferences().conditionUid()).isEqualTo(1301394L);
        assertThat(qualifying.rawHex()).isEqualTo("01006c077d020000ffffffffffffffff0a0000001600000000890000ffff0000");
    }

    @Test void plausibleWrongTypeOrWrongSlotIdentityIsRejected()throws Exception {
        var wrongType=ContractCaptureFixture.competitionBonus(0);
        long slot=0x700000+637*8L;
        long target=wrongType.readU64(slot);
        wrongType.pointer(target,wrongType.base+0x44D82B8);
        assertThatThrownBy(wrongType::decode).isInstanceOf(IOException.class).hasMessageContaining("COMP type");
        var changed=ContractCaptureFixture.competitionBonus(0);
        changed.onRead(slot,3,()->changed.pointer(slot,0x12345678));
        assertThatThrownBy(changed::decode).isInstanceOf(IOException.class).hasMessageContaining("changed during capture");
        var changedAtFinal=ContractCaptureFixture.competitionBonus(0);
        changedAtFinal.onRead(slot,4,()->changedAtFinal.pointer(slot,0x12345678));
        assertThatThrownBy(changedAtFinal::decode).isInstanceOf(IOException.class).hasMessageContaining("changed during capture");
    }
}
