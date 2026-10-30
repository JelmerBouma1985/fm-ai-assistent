package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class Fm27ClubLayoutsTest {
    private static final long BASE=0x7FFFD5260000L;

    @Test void capturedOwnerlessFullFinanceRetainsExactMoneyAndRejectsEveryShiftedLayout()throws Exception {
        var sample=sample(630);long club=hex(sample.path("club").asString()),child=hex(sample.path("finance").asString());
        byte[] raw=HexFormat.of().parseHex(sample.path("financeRaw").asString());
        ProcessMemoryReader reader=memory(Map.of(club+0x150,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(child).array(),child,raw));
        var exporter=new ClubExporter(ClubRecordLayout.current());
        var finance=exporter.readFinance(reader,club,BASE);
        assertThat(finance.balance()).isEqualTo(372_867_212L);
        assertThat(finance.transferBudget()).isEqualTo(52_100_000L);
        assertThat(finance.payrollBudget()).isEqualTo(4_376_487L);
        assertThat(finance.currentWagesIncludingBonuses()).isEqualTo(4_076_487L);
        shifted(ClubFinanceLayout.current(),l->exporter.readFinance(reader,club,BASE,(ClubFinanceLayout)l));
        shifted(ClubRecordLayout.current(),l->new ClubExporter((ClubRecordLayout)l).readFinance(reader,club,BASE,ClubFinanceLayout.current()));
    }

    @Test void baseVariantReadsExactlyItsNativeExtentAndNoExtendedFields()throws Exception {
        long club=0x100000,child=0x200000;
        byte[] raw=ByteBuffer.allocate(0x38).order(ByteOrder.LITTLE_ENDIAN).putLong(BASE+0x4CF07F8).putInt(0).putInt(-12345).array();
        var finance=new ClubExporter(ClubRecordLayout.current()).readFinance(memory(Map.of(
                club+0x150,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(child).array(),child,raw)),club,BASE);
        assertThat(finance.balance()).isEqualTo(-12345L);
        assertThat(finance.transferBudget()).isNull();assertThat(finance.payrollBudget()).isNull();assertThat(finance.currentWagesIncludingBonuses()).isNull();
    }

    @Test void capturedNestedPayloadsDecodeUnderTheirOwnImageAndEveryShiftedNativeAnchorRejects()throws Exception {
        var sample=sample(630);var capture=capture();int clock=capture.path("clock").asInt();
        byte[] full=HexFormat.of().parseHex(sample.path("financeRaw").asString());
        var ledger=ClubFinancialLedgerLayout.current();byte[] payload=new byte[ledger.payloadBytes()];
        for(int i=0;i<5;i++)System.arraycopy(full,0x248+i*0x118+0x30,payload,i*232,232);
        PlayerSnapshotValidator.validateClubFinancialLedger(payload,clock,ledger);
        assertThat(ClubFinancialLedgerLayout.values(HexFormat.of().formatHex(payload),clock,ClubFinanceLayout.MODULE_SHA256)).isPresent();
        shifted(ledger,l->PlayerSnapshotValidator.validateClubFinancialLedger(payload,clock,(ClubFinancialLedgerLayout)l));
        shifted(ClubFinancialHistoryLayout.current(),l->PlayerSnapshotValidator.validateClubFinancialHistoryLayout((ClubFinancialHistoryLayout)l));
        shifted(ClubFinancialCompetitionLayout.current(),l->PlayerSnapshotValidator.validateClubFinancialCompetitionLayout((ClubFinancialCompetitionLayout)l));
        shifted(ClubFinancialLoanLayout.current(),l->PlayerSnapshotValidator.validateClubFinancialLoanLayout((ClubFinancialLoanLayout)l));
        shifted(ClubUefaCoefficientLayout.current(),l->PlayerSnapshotValidator.validateClubUefaLayout((ClubUefaCoefficientLayout)l));
        shifted(ClubIdentityLayout.current(),l->PlayerSnapshotValidator.validateClubIdentityLayout((ClubIdentityLayout)l));
        StringBuilder loans=new StringBuilder();for(var row:sample.path("loanRows"))loans.append(row.asString());
        assertThat(ClubFinancialLoanLayout.values(loans.toString(),clock,ClubFinanceLayout.MODULE_SHA256)).isPresent();
    }

    @Test void fm27CoefficientKeepsNewThousandPointScaleAndHistoricalSeasonRulesKeepTheirScale()throws Exception {
        var layout=ClubUefaCoefficientLayout.current();
        assertThat(layout.pointsScale(0x653)).isEqualTo(4);
        assertThat(layout.pointsScale(0x654)).isEqualTo(100);
        assertThat(layout.pointsScale(0x671)).isEqualTo(100);
        assertThat(layout.pointsScale(0x672)).isEqualTo(1000);
        assertThat(layout.pointsScale(1684)).isEqualTo(1000);
        byte[] raw=HexFormat.of().parseHex(sample(630).path("coefficientRaw").asString());
        PlayerSnapshotValidator.validateClubUefaCoefficient(raw,1684,layout);
        assertThat(ClubUefaCoefficientLayout.values(HexFormat.of().formatHex(raw),1684,ClubFinanceLayout.MODULE_SHA256)).isPresent();
    }

    private static JsonNode capture()throws Exception {
        try(var in=Files.newInputStream(Path.of("src/test/resources/fm27/clubs-captured.json"))) {
            byte[] raw=in.readNBytes(131073);assertThat(raw.length).isLessThanOrEqualTo(131072);
            return JsonMapper.builder().build().readTree(raw);
        }
    }
    private static JsonNode sample(int uid)throws Exception {
        for(var sample:capture().path("samples"))if(sample.path("uid").asInt()==uid)return sample;
        throw new AssertionError("Missing native club fixture "+uid);
    }
    private static long hex(String value){return Long.parseLong(value.substring(2),16);}
    private interface Check {void accept(Object layout)throws Exception;}
    private static void shifted(Record known,Check check)throws Exception {
        var fields=known.getClass().getRecordComponents();var ctor=known.getClass().getDeclaredConstructor(Arrays.stream(fields).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
        for(int i=0;i<fields.length;i++) {
            Object[] values=new Object[fields.length];for(int j=0;j<fields.length;j++)values[j]=fields[j].getAccessor().invoke(known);
            if(values[i] instanceof Integer v)values[i]=v+1;
            else if(values[i] instanceof Long v)values[i]=v+8;
            else if(values[i] instanceof ClubFinancialLedgerLayout v)values[i]=new ClubFinancialLedgerLayout(v.financeRel()+1,v.baseVtableRva(),v.fullVtableRva(),v.ownerRel(),v.fullBytes(),v.blocksRel(),v.blockStride(),v.blockCount(),v.recordRel(),v.recordBytes(),v.tailRel(),v.tailBytes(),v.slotCount(),v.slotBytes(),v.clockRva(),v.clockBytes());
            else throw new AssertionError(fields[i].getName());
            Object altered=ctor.newInstance(values);assertThatThrownBy(()->check.accept(altered)).as(known.getClass().getSimpleName()+"."+fields[i].getName()).isInstanceOf(IOException.class);
        }
    }
    private static ProcessMemoryReader memory(Map<Long,byte[]> spans) {
        return new ProcessMemoryReader() {
            public int pid(){return 1;}
            public byte[] readBytes(long address,int count)throws IOException {
                for(var entry:spans.entrySet())if(address>=entry.getKey()&&address+count<=entry.getKey()+entry.getValue().length)
                    return Arrays.copyOfRange(entry.getValue(),(int)(address-entry.getKey()),(int)(address-entry.getKey())+count);
                throw new IOException("Unmapped native fixture range");
            }
            public List<MemoryRegion> maps(){return List.of();}
            public void close(){}
        };
    }
}
