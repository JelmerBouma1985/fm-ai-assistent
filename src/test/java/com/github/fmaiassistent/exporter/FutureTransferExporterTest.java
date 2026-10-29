package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.FutureTransferLayout;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FutureTransferExporterTest {
    @Test void unsupportedWindowsBinaryNeverRunsNativeOffsetsOrReportsNoTransfer(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception {
        var module=java.nio.file.Files.write(dir.resolve("game_plugin.dll"),new byte[]{1});
        var reader=new com.github.fmaiassistent.memory.ProcessMemoryReader() {
            public int pid(){return 1;}
            public Platform platform(){return Platform.WINDOWS;}
            public byte[] readBytes(long a,int n){throw new AssertionError("Unsupported native profile must not read RAM");}
            public List<com.github.fmaiassistent.memory.MemoryRegion> maps(){return List.of();}
            public void close(){}
        };
        var row=new HashMap<String,Object>();row.put("transfer_agreed",null);row.put("_future_transfer_profile","UNVERIFIED");
        String previous=System.getProperty(ContractExporter.MODULE_PROPERTY);
        try {
            System.setProperty(ContractExporter.MODULE_PROPERTY,module.toString());
            ContractExporter.capture(reader,0x100000L,List.of(row));
            assertThat(row.get("_future_transfer_profile")).isEqualTo("UNSUPPORTED_BINARY_HASH");
            assertThat(row.get("transfer_agreed")).isNull();assertThat(row).doesNotContainKey("_future_contracts");
        } finally {if(previous==null)System.clearProperty(ContractExporter.MODULE_PROPERTY);else System.setProperty(ContractExporter.MODULE_PROPERTY,previous);}
    }
    @Test void eightIndependentOfferCapturesSelectSeparateFutureContractsThroughIndices()throws Exception {
        int[] offers={74,75,77,79,91,94,112,113};int total=0;
        for(int i=0;i<8;i++) {
            var f=ContractCaptureFixture.futureTransfer(i);var exporter=exporter(f);
            var result=exporter.read(ContractCaptureFixture.PERSON,f.uid);exporter.verify();
            assertThat(result.agreed()).isTrue();assertThat(result.complete()).isEqualTo(i!=5);
            assertThat(result.contracts()).hasSize(1);
            var future=result.contracts().getFirst();assertThat(future.employment().offerIndex()).isEqualTo(offers[i]);
            assertThat(future.transferDate()).isEqualTo(i==5?null:LocalDate.parse(i==0||i==1?"2036-06-16":"2036-07-01"));
            total+=future.employment().contract().terms().size();
        }
        assertThat(total).isEqualTo(43);
    }
    @Test void batchReusesOneManagerTableAndPreservesSourceAndScalarDates()throws Exception {
        var f=ContractCaptureFixture.futureTransfer(0);var row=new HashMap<String,Object>();
        row.put("record",ContractCaptureFixture.PERSON);row.put("unique_id",f.uid);row.put("transfer_agreed",false);
        var other=new HashMap<>(row);
        new ContractExporter(f,f.base,ContractRecordLayout.current()).captureRows(List.of(row,other));
        assertThat((List<?>)row.get("_contracts")).isEmpty();assertThat((List<?>)row.get("_future_contracts")).hasSize(1);
        assertThat(row.get("transfer_agreed")).isEqualTo(true);assertThat(row.get("future_transfer_date")).isEqualTo("2036-06-16");
        assertThat(row.get("future_transfer_contract_end_date")).isEqualTo("2038-06-30");
        assertThat(f.readCount(f.base+0x4E37720)).isEqualTo(2);
    }
    @Test void unknownOfferAndComputedDateStayExplicitlyIncompleteAndNeverBecomeFalse()throws Exception {
        var f=ContractCaptureFixture.futureTransfer(0);long offer=f.readU64(0xC00000+74*8);
        f.pointer(offer,f.base+0x4474C68);
        var unknown=exporter(f).read(ContractCaptureFixture.PERSON,f.uid);
        assertThat(unknown.complete()).isFalse();assertThat(unknown.contracts()).isEmpty();
        var computed=ContractCaptureFixture.futureTransfer(0);long o=computed.readU64(0xC00000+74*8);
        computed.put(o+0x86,new byte[]{1});var result=exporter(computed).read(ContractCaptureFixture.PERSON,computed.uid);
        assertThat(result.agreed()).isTrue();assertThat(result.complete()).isFalse();
        assertThat(result.contracts().getFirst().transferDate()).isNull();
    }
    @Test void refusesShiftedOfferStatusAndMutatingManagerBeforePublishingRows()throws Exception {
        var f=ContractCaptureFixture.futureTransfer(0);var l=FutureTransferLayout.current();
        var shifted=new FutureTransferLayout(l.personIndices(),l.managerRoot(),l.managerVtable(),l.tableOffset(),l.fullOfferVtable(),l.loanOfferVtable(),
                l.offerPrefixSize(),l.person(),l.buyerTeam(),l.index(),l.date(),l.dateKind(),l.status()+1,l.exchangeVector(),l.exchangeStride());
        assertThatThrownBy(()->new FutureTransferExporter(f,f.base,shifted).read(ContractCaptureFixture.PERSON,f.uid))
                .isInstanceOf(IOException.class).hasMessageContaining("profile anchor");
        var changed=ContractCaptureFixture.futureTransfer(0);
        changed.onRead(0xB00008,2,()->changed.pointer(0xB00008,0));
        var row=new HashMap<String,Object>();row.put("record",ContractCaptureFixture.PERSON);row.put("unique_id",changed.uid);
        assertThatThrownBy(()->new ContractExporter(changed,changed.base,ContractRecordLayout.current()).captureRows(List.of(row)))
                .isInstanceOf(IOException.class).hasMessageContaining("changed");
        assertThat(row).doesNotContainKeys("_contracts","_future_contracts","_contract_profile");
    }
    @Test void capturedOfferWithConstructedExchangeMembershipUsesTwentyFourByteRows()throws Exception {
        var f=ContractCaptureFixture.futureTransfer(0);long offer=f.readU64(0xC00000+74*8);
        f.pointer(offer+0x38,0xD00000); // Constructed alternate PERSON, not a new native exchange capture.
        f.put(offer+0x90,new byte[24]);f.pointer(offer+0x90,0xD01000);f.pointer(offer+0x98,0xD01018);f.pointer(offer+0xA0,0xD01018);
        f.put(0xD01000,new byte[24]);f.pointer(0xD01000,ContractCaptureFixture.PERSON);
        var result=exporter(f).read(ContractCaptureFixture.PERSON,f.uid);
        assertThat(result.agreed()).isTrue();assertThat(result.contracts()).hasSize(1);
        f.pointer(0xD01000,0);
        var unresolved=exporter(f).read(ContractCaptureFixture.PERSON,f.uid);
        assertThat(unresolved.complete()).isFalse();assertThat(unresolved.contracts()).isEmpty();
    }
    @Test void nativeDateSentinelDoesNotBecomeARealYearNineteenHundredTransfer()throws Exception {
        var f=ContractCaptureFixture.futureTransfer(0);long offer=f.readU64(0xC00000+74*8);
        f.put(offer+0x7C,java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort((short)1).putShort((short)1900).array());
        var result=exporter(f).read(ContractCaptureFixture.PERSON,f.uid);
        assertThat(result.agreed()).isTrue();assertThat(result.complete()).isFalse();
        assertThat(result.contracts().getFirst().transferDate()).isNull();
    }
    private static FutureTransferExporter exporter(ContractCaptureFixture f){return new FutureTransferExporter(f,f.base,FutureTransferLayout.current());}
}
