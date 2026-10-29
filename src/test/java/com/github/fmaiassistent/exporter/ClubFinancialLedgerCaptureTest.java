package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

public class ClubFinancialLedgerCaptureTest {
    static final ClubFinancialLedgerLayout L=ClubFinancialLedgerLayout.current();
    static final long BASE=ClubFinancialLoanCaptureTest.BASE;
    public static final int CLOCK=ClubFinancialLoanCaptureTest.CLOCK;
    public static Map<Long,String[]> financeRows()throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_finance_nested_inventory_fm620_20261026.csv"))){bytes=in.readNBytes(8*1024*1024+1);}
        assertThat(bytes.length).isLessThanOrEqualTo(8*1024*1024);var rows=new LinkedHashMap<Long,String[]>();
        new String(bytes,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(s->s.split(",",-1))
                .filter(r->r[0].equals("full_finance")).forEach(r->{assertThat(rows.put(Long.parseLong(r[1]),r)).isNull();});
        assertThat(rows).hasSize(680);return rows;
    }
    /** Independent capture-file byte selection, literal native boundaries from checkpoint39. */
    public static String payload(String[] row) {
        byte[] full=HexFormat.of().parseHex(row[5]);assertThat(full).hasSize(2240);var raw=ByteBuffer.allocate(1160);
        for(int i=0;i<5;i++){raw.put(full,0x280+i*0x118,228);raw.put(full,0x364+i*0x118,4);}
        return HexFormat.of().formatHex(raw.array());
    }
    static ClubUefaCoefficientCaptureTest.Blocks fixture()throws Exception {
        var f=ClubFinancialLoanCaptureTest.fixture();var finance=financeRows();
        for(var row:f.rows.values()){long uid=Long.parseLong(row[1]),club=Long.parseUnsignedLong(row[2],16);var full=finance.get(uid);
            long pointer=full==null?0:Long.parseUnsignedLong(full[4],16);f.put(club+0x150,ClubFinancialLoanCaptureTest.longs(pointer));
            if(full!=null)f.put(pointer,HexFormat.of().parseHex(full[5]));}
        return f;
    }
    public static List<NativeClubSnapshot> capturedRecords()throws Exception {
        return new ClubIdentitySnapshot(fixture(),BASE,ClubIdentityLayout.current(),null,null,L).records();
    }
    @Test void all680OwnProfilesAreLosslessAndAllOtherClubsRemainUnavailable()throws Exception {
        var expected=financeRows();var f=fixture();var capture=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,L);
        assertThat(capture.records()).hasSize(26377);int profiles=0;
        for(var record:capture.records()){var e=expected.get(record.uniqueId());
            if(e==null){assertThat(record.financialLedgerHex()).isNull();assertThat(record.financialLedgerClock()).isNull();}
            else{profiles++;assertThat(record.financialLedgerHex()).isEqualTo(payload(e));assertThat(record.financialLedgerClock()).isEqualTo(CLOCK);}
        }assertThat(profiles).isEqualTo(680);capture.verify();
        var one=capture.records().stream().filter(r->r.uniqueId()==1036).findFirst().orElseThrow();
        var p=ClubFinancialLedgerLayout.values(one.financialLedgerHex(),CLOCK,L.MODULE_SHA256).orElseThrow();
        assertThat(p.periods().stream().map(ClubFinancialLedgerLayout.Period::totalIncome)).containsExactly(21609067,16364810,369964713,292098446);
        assertThat(p.periods().stream().map(ClubFinancialLedgerLayout.Period::totalExpenditure)).containsExactly(9099122,16658745,340350693,269086591);
        assertThat(p.periods().get(0).turnover()).isEqualTo(21594405);
        assertThat(p.periods().get(2).amount(ClubFinancialLedgerLayout.Category.SPONSORSHIP)).isEqualTo(8414566);
        byte[] machine;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_ledger_categories_verified_v2_20261026.json"))){machine=in.readNBytes(1048577);}
        assertThat(machine.length).isLessThanOrEqualTo(1048576);var groups=new tools.jackson.databind.json.JsonMapper().readTree(machine).path("groups");
        var categories=ClubFinancialLedgerLayout.Category.values();assertThat(groups.size()).isEqualTo(categories.length);
        int index=0;for(var income:List.of(false,true))for(var category:categories)if(category.income()==income){var g=groups.get(index++);
            assertThat(category.slots()).containsExactlyElementsOf(java.util.stream.StreamSupport.stream(g.path("recordSlots").spliterator(),false).map(tools.jackson.databind.JsonNode::asInt).toList());
            for(int period=0;period<4;period++)assertThat(p.periods().get(period).amount(category)).isEqualTo(g.path("values").get(period).path("value").asInt());}
    }
    @Test void everyShiftedNativeLayoutFailsAgainstSameIndependentFixture()throws Exception {
        var f=fixture();var components=ClubFinancialLedgerLayout.class.getRecordComponents();var types=Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        var ctor=ClubFinancialLedgerLayout.class.getDeclaredConstructor(types);
        for(int i=0;i<components.length;i++){var args=new Object[components.length];for(int j=0;j<args.length;j++)args[j]=components[j].getAccessor().invoke(L);
            if(types[i]==long.class)args[i]=(Long)args[i]+1;else args[i]=(Integer)args[i]+1;var shifted=ctor.newInstance(args);
            assertThatThrownBy(()->new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,shifted)).as(components[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");}
    }
    @Test void changedClockOwnerPointerAndAnyFinancialBlockAbortBeforePublication()throws Exception {
        var f=fixture();var capture=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,L);var e=financeRows().get(1036L);
        long club=Long.parseUnsignedLong(e[2],16),finance=Long.parseUnsignedLong(e[4],16);
        for(long address:new long[]{BASE+L.clockRva(),club+0x150,finance,finance+0x250,finance+0x6B0}) {
            var block=f.blocks.floorEntry(address);byte[] old=block.getValue(),changed=old.clone();changed[Math.toIntExact(address-block.getKey())]^=1;f.put(block.getKey(),changed);
            assertThatThrownBy(capture::verify).isInstanceOf(IOException.class).hasMessageContaining("changed");f.put(block.getKey(),old);
        }capture.verify();
        byte[] old=f.blocks.get(finance),changed=old.clone();ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putLong(8,club+8);f.put(finance,changed);
        assertThatThrownBy(()->new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,L)).isInstanceOf(IOException.class).hasMessageContaining("owner identity");
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putLong(8,club).putLong(0,BASE+L.baseVtableRva());f.put(finance,changed);
        var base=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,L);assertThat(base.records().stream().filter(r->r.uniqueId()==1036).findFirst().orElseThrow().financialLedgerHex()).isNull();
    }
}
