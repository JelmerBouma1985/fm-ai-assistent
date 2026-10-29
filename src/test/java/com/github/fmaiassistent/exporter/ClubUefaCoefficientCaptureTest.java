package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** The production capture/decoder consumes independent CE bytes; synthetic roots contain no target calls. */
class ClubUefaCoefficientCaptureTest {
    static final long BASE=0x7FFAA0800000L, DESCRIPTOR=0x10000000, HEADER=0x10001000, TABLE=0x10010000;
    static final ClubUefaCoefficientLayout U=ClubUefaCoefficientLayout.current();

    @Test void productionCapturePreservesAll1903ProfilesIncludingAbsentObjects() throws Exception {
        var f=fixture();var capture=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),U);
        assertThat(capture.records()).hasSize(26377);
        assertThat(capture.records().stream().filter(r->r.uefaCoefficientHex()!=null)).hasSize(1903);
        for(var r:capture.records()) {
            var expected=f.rows.get(r.uniqueId());
            assertThat(r.uefaCoefficientHex()).isEqualTo(expected[6].isEmpty()?null:expected[6]);
            assertThat(r.uefaRuleVersion()).isEqualTo(expected[6].isEmpty()?null:1639);
        }
        var heerenveen=capture.records().stream().filter(r->r.uniqueId()==1036).findFirst().orElseThrow();
        var values=ClubUefaCoefficientLayout.values(heerenveen.uefaCoefficientHex(),heerenveen.uefaRuleVersion(),U.MODULE_SHA256).orElseThrow();
        assertThat(values.fiveYearCoefficient()).isEqualByComparingTo("116.5");
        assertThat(values.tenYearCoefficient()).isEqualByComparingTo("169.25");
        assertThat(values.currentSeasonPoints()).isEqualByComparingTo("37");
        assertThat(values.fiveYearRank()).isEqualTo(16);assertThat(values.tenYearRank()).isEqualTo(28);
        capture.verify();
    }

    @Test void everyShiftedLayoutComponentRejectsTheSameCapturedFixtureBeforeReadingWrongFields() throws Exception {
        var f=fixture();var components=ClubUefaCoefficientLayout.class.getRecordComponents();
        var types=Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        var constructor=ClubUefaCoefficientLayout.class.getDeclaredConstructor(types);
        for(int i=0;i<components.length;i++) {
            Object[] args=new Object[components.length];
            for(int n=0;n<args.length;n++)args[n]=components[n].getAccessor().invoke(U);
            if(types[i]==long.class)args[i]=((Long)args[i])+1;else args[i]=((Integer)args[i])+1;
            var shifted=constructor.newInstance(args);
            assertThatThrownBy(()->new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),shifted))
                    .as(components[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
        }
    }

    @Test void finalPublicationRejectsChangedRuleAvailabilityPointersRawObjectAndNullAbsence() throws Exception {
        var f=fixture();var capture=new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),U);
        var row=f.rows.get(1036L);long club=hex(row[2]),data=hex(row[4]),coefficient=hex(row[5]);
        long absentData=f.rows.values().stream().filter(r->r[5].equals("0")).mapToLong(r->hex(r[4])).findFirst().orElseThrow();
        var context=f.contexts.get(1036L);
        long contextSlot=hex(context[3])+U.nationContextRel()+Long.parseLong(context[4])*U.contextStride();
        long[] spans={BASE+U.ruleVersionRva(),club+U.selectorRel(),club+U.nationRel(),club+U.clubDataRel(),
                data+U.coefficientRel(),contextSlot,coefficient,absentData+U.coefficientRel()};
        for(long address:spans) {
            byte[] previous=f.blocks.get(address);assertThat(previous).isNotNull();byte[] changed=previous.clone();changed[0]^=1;f.put(address,changed);
            assertThatThrownBy(capture::verify).isInstanceOf(IOException.class).hasMessageContaining("changed");
            f.put(address,previous);
        }
        capture.verify();
    }

    @Test void corruptRangesAndNativeGuardFailAndOldProfilesStayUnavailable() throws Exception {
        var f=fixture();var r=f.rows.get(1036L);long address=hex(r[5]);byte[] original=f.blocks.get(address);
        for(int offset:new int[]{U.countRel(),U.cursorRel(),U.rank5Rel(),U.value5Rel()}) {
            byte[] changed=original.clone();if(offset==U.countRel()||offset==U.cursorRel())changed[offset]=(byte)255;
            else ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,Integer.MIN_VALUE);
            assertThatThrownBy(()->PlayerSnapshotValidator.validateClubUefaCoefficient(changed,1639,U)).isInstanceOf(IOException.class);
        }
        var c=f.contexts.get(1036L);long contextSlot=hex(c[3])+U.nationContextRel()+Long.parseLong(c[4])*U.contextStride();
        f.put(contextSlot,longs(0));
        assertThatThrownBy(()->new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),U)).isInstanceOf(IOException.class).hasMessageContaining("availability guard");
        assertThat(ClubUefaCoefficientLayout.values(null,null,U.MODULE_SHA256)).isEmpty();
        assertThat(ClubUefaCoefficientLayout.values(r[6],1639,"other-image")).isEmpty();
        var raw=HexFormat.of().parseHex(r[6]);
        assertThat(U.decode(raw,0x653).currentSeasonPoints()).isEqualByComparingTo("925"); // Old native divisor4.
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(U.rank5Rel(),-1);
        assertThat(U.decode(raw,1639).fiveYearRank()).isNull();
    }

    static Blocks fixture() throws Exception {
        var f=new Blocks();
        for(String[] c:csv("club_uefa_coefficient_context_population_20261026.csv"))f.contexts.put(Long.parseLong(c[1]),c);
        var pointers=ByteBuffer.allocate(26839*8).order(ByteOrder.LITTLE_ENDIAN);
        for(String[] r:csv("club_uefa_coefficients_population_live_20261026.csv")) {
            long uid=Long.parseLong(r[1]),club=hex(r[2]),data=hex(r[4]),coefficient=hex(r[5]);var c=f.contexts.get(uid);
            f.rows.put(uid,r);pointers.putLong(Integer.parseInt(r[0])*8,club);f.put(club,HexFormat.of().parseHex(r[3]));
            f.put(club+0xC0,new byte[16]);f.put(club+U.selectorRel(),new byte[]{Byte.parseByte(c[4])});
            f.put(club+U.nationRel(),longs(hex(c[3])));f.put(club+U.clubDataRel(),longs(data));
            if(!c[3].equals("0"))f.put(hex(c[3])+U.nationContextRel()+Long.parseLong(c[4])*U.contextStride(),longs(hex(c[5])));
            f.put(data+U.coefficientRel(),longs(coefficient));if(coefficient!=0)f.put(coefficient,HexFormat.of().parseHex(r[6]));
        }
        f.put(BASE+U.ruleVersionRva(),ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1639).array());
        f.put(BASE+ClubIdentityLayout.current().registryRva(),longs(DESCRIPTOR));f.put(DESCRIPTOR+0x80,longs(HEADER));
        f.put(HEADER,longs(TABLE,TABLE+pointers.capacity(),TABLE+pointers.capacity()));f.put(TABLE,pointers.array());return f;
    }
    private static List<String[]> csv(String name)throws Exception {
        byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence",name))){b=in.readNBytes(8*1024*1024+1);}
        assertThat(b.length).isLessThanOrEqualTo(8*1024*1024);
        return new String(b,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(s->s.split(",",-1)).toList();
    }
    static long hex(String s){return Long.parseUnsignedLong(s,16);}
    private static byte[] longs(long...v){var b=ByteBuffer.allocate(v.length*8).order(ByteOrder.LITTLE_ENDIAN);for(long x:v)b.putLong(x);return b.array();}
    static class Blocks implements ProcessMemoryReader {
        final NavigableMap<Long,byte[]> blocks=new TreeMap<>();final Map<Long,String[]> rows=new HashMap<>(),contexts=new HashMap<>();
        void put(long a,byte[] b){blocks.put(a,b);}
        public int pid(){return -1;}public List<MemoryRegion> maps(){return List.of();}public void close(){}
        public byte[] readBytes(long a,int n)throws IOException {var e=blocks.floorEntry(a);if(e==null||a-e.getKey()+n>e.getValue().length)throw new IOException("Uncaptured fixture span");int o=Math.toIntExact(a-e.getKey());return Arrays.copyOfRange(e.getValue(),o,o+n);}
    }
}
