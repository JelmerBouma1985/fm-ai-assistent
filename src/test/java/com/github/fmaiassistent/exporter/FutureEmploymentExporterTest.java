package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.FutureEmploymentLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class FutureEmploymentExporterTest {
    @Test void readsEightCapturedContractsByOfferIdIncludingThreeNonFirstRecordCases() throws Exception {
        int[] offers={74,75,77,79,91,94,112,113};
        int[] wages={4109,1919,6902,9196,305,13281,11695,3062};
        long[] clubs={731,744,1661,1729,2000074286L,713,116403,131162};
        String[] ends={"2038-06-30","2039-06-30","2038-06-30","2038-06-30","2039-06-30","2038-06-30","2037-12-31","2037-12-31"};
        int terms=0;
        for(int i=0;i<offers.length;i++) {
            var f=ContractCaptureFixture.futureEmployment(i);
            var c=exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,offers[i]).orElseThrow();
            assertThat(c.offerIndex()).isEqualTo(offers[i]);assertThat(c.state()).isEqualTo(2);
            assertThat(c.wageRaw()).isEqualTo(wages[i]);assertThat(c.endDate()).isEqualTo(LocalDate.parse(ends[i]));
            assertThat(c.endDateUsesGameDateFlag()).isFalse();assertThat(c.recordHex()).hasSize(144);
            assertThat(c.contract().ownerUid()).isEqualTo(f.uid);assertThat(c.contract().teamOwnerUid()).isEqualTo(clubs[i]);
            assertThat(c.contract().teamOwnerKind()).isEqualTo("CLUB");
            assertThat(c.contract().terms()).allMatch(t->"FULL_68".equals(t.group()));
            terms+=c.contract().terms().size();
            assertThat(exporter(f).read(ContractCaptureFixture.PERSON,f.uid)).isEmpty();
            assertThat(exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,999999)).isEmpty();
            assertThat(f.largestRead).isLessThanOrEqualTo(2048);
        }
        assertThat(terms).isEqualTo(43);
    }

    @Test void shiftedRecordSizeAndContractPointerAndWageOffsetsRejectTheSameCapturedFixture() throws Exception {
        var f=ContractCaptureFixture.futureEmployment(0);var l=FutureEmploymentLayout.current();
        var variants=java.util.List.of(
                new FutureEmploymentLayout(l.personRecords(),l.recordSize()-1,l.recordContract(),l.recordOfferIndex(),l.recordState(),l.contractWage(),l.contractDateFlags(),l.gameDateFlagMask(),l.contractEnd(),l.gameDateRva(),l.peopleRegistry()),
                new FutureEmploymentLayout(l.personRecords(),l.recordSize(),l.recordContract()+1,l.recordOfferIndex(),l.recordState(),l.contractWage(),l.contractDateFlags(),l.gameDateFlagMask(),l.contractEnd(),l.gameDateRva(),l.peopleRegistry()),
                new FutureEmploymentLayout(l.personRecords(),l.recordSize(),l.recordContract(),l.recordOfferIndex(),l.recordState(),l.contractWage()-1,l.contractDateFlags(),l.gameDateFlagMask(),l.contractEnd(),l.gameDateRva(),l.peopleRegistry()));
        for(var shifted:variants)assertThatThrownBy(()->exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74,shifted)).isInstanceOf(IOException.class);
    }

    @Test void rejectsWrongOwnerAndChangedRecordPointerBeforePublishing() throws Exception {
        var f=ContractCaptureFixture.futureEmployment(0);
        long table=f.readU64(0x500000),record=f.readU64(table),contract=f.readU64(record+0x10);
        f.pointer(contract+8,ContractCaptureFixture.PERSON+16);
        assertThatThrownBy(()->exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("owner anchor");
        var changed=ContractCaptureFixture.futureEmployment(0);
        changed.onRead(record,2,()->changed.pointer(record+0x10,0));
        assertThatThrownBy(()->exporter(changed).readFutureEmployment(ContractCaptureFixture.PERSON,changed.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("changed");
        var moved=ContractCaptureFixture.futureEmployment(0);
        long slot=0x900000+moved.readI32(ContractCaptureFixture.PERSON+8)*8L;
        moved.onRead(slot,2,()->moved.pointer(slot,0));
        assertThatThrownBy(()->exporter(moved).readFutureEmployment(ContractCaptureFixture.PERSON,moved.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("relationship identity");
    }

    @Test void nativeGameDateBranchPreservesOrdinalAndClampsAtNextYearsEnd() throws Exception {
        var f=ContractCaptureFixture.futureEmployment(0);
        long table=f.readU64(0x500000),record=f.readU64(table),contract=f.readU64(record+0x10);
        // Synthetic branch fixture; all eight real captured contracts had this flag clear.
        f.put(contract+0x2B,new byte[]{8});
        f.put(f.base+FutureEmploymentLayout.current().gameDateRva(),ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short)366).putShort((short)2036).array());
        var result=exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74).orElseThrow();
        assertThat(result.endDateUsesGameDateFlag()).isTrue();assertThat(result.endDate()).isEqualTo(LocalDate.of(2037,12,31));
    }

    @Test void refusesOversizedVectorBeforeReadingTheTableAndDoesNotGuessWhenAbsent() throws Exception {
        var f=ContractCaptureFixture.futureEmployment(0);
        long begin=f.readU64(0x500000);f.pointer(0x500008,begin+257*8);f.pointer(0x500010,begin+257*8);
        assertThatThrownBy(()->exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("bounds/count");
        f.pointer(ContractCaptureFixture.PERSON+0xA0,0);
        assertThat(exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74)).isEmpty();
        assertThatThrownBy(()->exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,-1)).isInstanceOf(IOException.class);
    }

    @Test void emptyVectorStillValidatesItsPointerAndMatchingStateThreeIsNotRenamedOrFiltered() throws Exception {
        var f=ContractCaptureFixture.futureEmployment(0);
        f.pointer(0x500000,1);f.pointer(0x500008,1);f.pointer(0x500010,1);
        assertThatThrownBy(()->exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("pointer/range");
        var state=ContractCaptureFixture.futureEmployment(0);
        long record=state.readU64(state.readU64(0x500000));
        state.put(record+0x3E,new byte[]{3}); // Synthetic state branch; real matched samples all had state2.
        assertThat(exporter(state).readFutureEmployment(ContractCaptureFixture.PERSON,state.uid,74).orElseThrow().state()).isEqualTo(3);
    }

    @Test void sameUidOutsideTheCentralPersonSlotAndRegistryChangesAreRejected() throws Exception {
        var f=ContractCaptureFixture.futureEmployment(0);
        int index=f.readI32(ContractCaptureFixture.PERSON+8);
        f.pointer(0x900000+index*8L,ContractCaptureFixture.PERSON+16);
        assertThatThrownBy(()->exporter(f).readFutureEmployment(ContractCaptureFixture.PERSON,f.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("relationship identity");
        var changed=ContractCaptureFixture.futureEmployment(0);
        changed.onRead(changed.base+FutureEmploymentLayout.current().peopleRegistry(),2,
                ()->changed.pointer(changed.base+FutureEmploymentLayout.current().peopleRegistry(),0));
        assertThatThrownBy(()->exporter(changed).readFutureEmployment(ContractCaptureFixture.PERSON,changed.uid,74))
                .isInstanceOf(IOException.class).hasMessageContaining("changed");
    }

    private static ContractExporter exporter(ContractCaptureFixture f) {
        return new ContractExporter(f,f.base,ContractRecordLayout.current());
    }
}
