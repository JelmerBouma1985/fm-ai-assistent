package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Independent CE680-owner child capture; synthetic registry/zero ledger context is not history data. */
public class ClubFinancialHistoryCaptureTest {
    public static final ClubFinancialHistoryLayout L=ClubFinancialHistoryLayout.current();
    public static final long BASE=0x7FFE41950000L;public static final int CLOCK=0x07F41A8D;
    static List<String[]> rows()throws Exception {
        byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_history_population_fm22268_20261027.csv"))){b=in.readNBytes(16*1024*1024+1);}
        assertThat(b.length).isLessThanOrEqualTo(16*1024*1024);
        return new String(b,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(s->s.split(",",-1)).toList();
    }
    public static Map<Long,String> expectedPayloads()throws Exception {
        var buffers=new LinkedHashMap<Long,ByteBuffer>();for(var r:rows()) {
            long uid=Long.parseLong(r[1]);if(r[0].equals("owner")){buffers.put(uid,ByteBuffer.allocate(16714).put((byte)1));continue;}
            var b=buffers.get(uid);if(r[0].equals("absent_ring")){b.put((byte)0);continue;}
            byte[] raw=HexFormat.of().parseHex(r[9]);if(r[0].equals("ring")) {
                if(r[5].equals("230"))b.put((byte)1);b.put(raw[0]).put(raw[0x1E8]).put(raw,1,7).put(raw,0x1E9,7);
            }else{b.put((byte)(r[8].equals("0")?0:1));b.put(raw);}
        }
        var result=new LinkedHashMap<Long,String>();buffers.forEach((uid,b)->result.put(uid,HexFormat.of().formatHex(Arrays.copyOf(b.array(),b.position()))));return result;
    }
    static Fixture fixture()throws Exception {
        var f=new Fixture();var slots=ByteBuffer.allocate(26839*8).order(ByteOrder.LITTLE_ENDIAN);
        for(var r:rows()){long club=hex(r[3]),finance=hex(r[4]);byte[] raw=HexFormat.of().parseHex(r[9]);
            switch(r[0]) {
                case "owner" -> {slots.putLong(Integer.parseInt(r[2])*8,club);f.put(club,Arrays.copyOf(raw,16));f.put(club+0x150,longs(finance));
                    var body=new byte[0x8C0];System.arraycopy(raw,16,body,0,16);f.put(finance,body);}
                case "ring" -> {if(r[5].equals("40"))System.arraycopy(raw,0,f.blocks.get(finance),0x40,raw.length);
                    else{ByteBuffer.wrap(f.blocks.get(finance)).order(ByteOrder.LITTLE_ENDIAN).putLong(0x230,hex(r[8]));f.put(hex(r[8]),raw);}}
                case "record" -> {if(!r[8].equals("0")){f.put(hex(r[8]),raw);f.children.add(hex(r[8]));}}
                default -> { }
            }
        }
        f.put(BASE+0x4E49588,longs(0x20000000));f.put(0x20000080,longs(0x20001000));
        f.put(0x20001000,longs(0x20010000,0x20010000+slots.capacity(),0x20010000+slots.capacity()));f.put(0x20010000,slots.array());
        f.put(BASE+0x4DF3C18,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(CLOCK).array());return f;
    }
    static ClubIdentitySnapshot capture(Fixture f,ClubFinancialHistoryLayout l)throws Exception {
        return new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,L.ledger(),null,null,l);
    }
    public static List<NativeClubSnapshot> capturedRecords()throws Exception {var f=fixture();var c=capture(f,L);c.verify();return c.records();}
    @Test void everyRawRecordAndAbsenceSurvivesAndAdjacentReadsAreBatched()throws Exception {
        var f=fixture();var c=capture(f,L);var expected=expectedPayloads();assertThat(c.records()).hasSize(680);
        int members=0,empty=0,absent=0;for(var r:c.records()){assertThat(r.financialHistoryHex()).isEqualTo(expected.get(r.uniqueId()));
            var p=L.decode(HexFormat.of().parseHex(r.financialHistoryHex()),CLOCK);members+=p.summary().count();
            if(p.expenditure()==null)absent++;else members+=p.expenditure().count();if(p.summary().count()==0)empty++;}
        assertThat(members).isEqualTo(28834);assertThat(empty).isEqualTo(425);assertThat(absent).isEqualTo(425);
        assertThat(f.reads.stream().filter(r->f.children.contains(r.address())).count()).isEqualTo(510);c.verify();
        var p=L.decode(HexFormat.of().parseHex(expected.get(1036L)),CLOCK);
        assertThat(p.balances().getFirst().amount()).isEqualTo(21230397);assertThat(p.balances().get(1).amount()).isEqualTo(30133822);
        assertThat(p.balances().getFirst().month()).hasToString("2031-05-01");assertThat(p.balances().getLast().month()).hasToString("2036-04-01");
    }
    @Test void everyShiftedComponentRejectsTheSameNativeFixture()throws Exception {
        var f=fixture();var parts=ClubFinancialHistoryLayout.class.getRecordComponents();var types=Arrays.stream(parts).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);var constructor=ClubFinancialHistoryLayout.class.getDeclaredConstructor(types);
        for(int i=0;i<parts.length;i++){Object[] args=new Object[parts.length];for(int j=0;j<args.length;j++)args[j]=parts[j].getAccessor().invoke(L);
            if(types[i]==int.class)args[i]=(Integer)args[i]+1;else{var v=L.ledger();args[i]=new ClubFinancialLedgerLayout(v.financeRel()+1,v.baseVtableRva(),v.fullVtableRva(),v.ownerRel(),v.fullBytes(),v.blocksRel(),v.blockStride(),v.blockCount(),v.recordRel(),v.recordBytes(),v.tailRel(),v.tailBytes(),v.slotCount(),v.slotBytes(),v.clockRva(),v.clockBytes());}
            var shifted=constructor.newInstance(args);assertThatThrownBy(()->capture(f,shifted)).as(parts[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");}
    }
    @Test void changedClockHeadersPayloadAndOptionalPointerInvalidatePublication()throws Exception {
        var f=fixture();var c=capture(f,L);var selected=rows().stream().filter(r->r[1].equals("1036")).toList();
        long finance=hex(selected.getFirst()[4]),child=hex(selected.stream().filter(r->r[0].equals("record")).findFirst().orElseThrow()[8]);
        for(long address:new long[]{BASE+0x4DF3C18,finance+0x40,finance+0x230,child+8}) {
            var e=f.blocks.floorEntry(address);byte[] old=e.getValue(),changed=old.clone();changed[Math.toIntExact(address-e.getKey())]^=1;f.put(e.getKey(),changed);
            assertThatThrownBy(c::verify).isInstanceOf(IOException.class).hasMessageContaining("changed");f.put(e.getKey(),old);
        }c.verify();
        byte[] raw=f.blocks.get(finance).clone();raw[0x40]=(byte)61;f.put(finance,raw);assertThatThrownBy(()->capture(f,L)).isInstanceOf(IOException.class).hasMessageContaining("count/cursor");
    }
    @Test void cursorWrapNullMemberAndScatteredRecordKeepLogicalMembership()throws Exception {
        var f=fixture();var r=rows().stream().filter(x->x[0].equals("owner")&&x[1].equals("1036")).findFirst().orElseThrow();
        long finance=hex(r[4]);byte[] body=f.blocks.get(finance);var b=ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        long last=b.getLong(0x48+59*8),second=b.getLong(0x50);byte[] lastRaw=f.blocks.get(last),secondRaw=f.blocks.get(second);
        body[0x228]=59;b.putLong(0x48,0).putLong(0x50,0x30000000);f.put(0x30000000,secondRaw.clone());
        var c=capture(f,L);var p=L.decode(HexFormat.of().parseHex(c.records().stream().filter(x->x.uniqueId()==1036).findFirst().orElseThrow().financialHistoryHex()),CLOCK);
        assertThat(p.summary().members().getFirst().physicalSlot()).isEqualTo(59);
        assertThat(p.summary().members().getFirst().rawHex()).isEqualTo(HexFormat.of().formatHex(lastRaw));
        assertThat(p.balances().get(1).amount()).isNull();assertThat(p.summary().members().get(2).rawHex()).isEqualTo(HexFormat.of().formatHex(secondRaw));c.verify();
    }
    static long hex(String s){return Long.parseUnsignedLong(s,16);}
    static byte[] longs(long... values){var b=ByteBuffer.allocate(8*values.length).order(ByteOrder.LITTLE_ENDIAN);for(long v:values)b.putLong(v);return b.array();}
    record Read(long address,int bytes) { }
    static class Fixture implements ProcessMemoryReader {
        final NavigableMap<Long,byte[]> blocks=new TreeMap<>();final Set<Long> children=new HashSet<>();final List<Read> reads=new ArrayList<>();
        void put(long a,byte[] b){blocks.put(a,b);}public int pid(){return-1;}public List<MemoryRegion> maps(){return List.of();}public void close(){}
        public byte[] readBytes(long a,int n)throws IOException {reads.add(new Read(a,n));byte[] out=new byte[n];int at=0;while(at<n){var e=blocks.floorEntry(a+at);if(e==null)throw new IOException("Uncaptured fixture");int start=Math.toIntExact(a+at-e.getKey()),copy=Math.min(n-at,e.getValue().length-start);if(copy<=0)throw new IOException("Uncaptured fixture gap");System.arraycopy(e.getValue(),start,out,at,copy);at+=copy;}return out;}
    }
}
