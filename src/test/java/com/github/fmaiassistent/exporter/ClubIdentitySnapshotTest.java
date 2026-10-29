package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ClubIdentitySnapshotTest {
    private static final long BASE=0x7FF988310000L, DESCRIPTOR=0x10000000, HEADER=0x10001000, TABLE=0x10010000;
    private static final ClubIdentityLayout L=ClubIdentityLayout.current();

    @Test void productionCaptureDecodesEveryCapturedIdentityIncludingLeagueBodies() throws Exception {
        var memory=fixture();
        var capture=new ClubIdentitySnapshot(memory,BASE,L);
        assertThat(capture.records()).hasSize(26839);
        assertThat(capture.records().stream().filter(r->r.kind().equals("LEAGUE_BODY_CLUB"))).hasSize(462);
        assertThat(capture.records()).extracting(r->r.uniqueId()).doesNotHaveDuplicates();
        assertThat(capture.records()).allSatisfy(r->{
            assertThat(capture.club(r.sourceAddress())).isEqualTo(r);
            assertThat(r.name()).isNull(); // Name pointers are synthetic zeroes; names are not captured in this fixture.
        });
        capture.verify();
    }

    @Test void shiftedCapturedLayoutFailsInsteadOfTreatingRegistryIndexAsUid() throws Exception {
        var memory=fixture();
        var shifted=new ClubIdentityLayout(L.registryRva(),L.registryHeader(),L.index(),8,L.prefixBytes(),
                L.clubVtable(),L.leagueBodyVtable(),L.teamOwnerFlag(),L.teamVtable(),L.nationalTeamVtable());
        assertThatThrownBy(()->new ClubIdentitySnapshot(memory,BASE,shifted)).isInstanceOf(IOException.class).hasMessageContaining("anchor");
        var shiftedIndex=new ClubIdentityLayout(L.registryRva(),L.registryHeader(),12,L.uniqueId(),L.prefixBytes(),
                L.clubVtable(),L.leagueBodyVtable(),L.teamOwnerFlag(),L.teamVtable(),L.nationalTeamVtable());
        assertThatThrownBy(()->new ClubIdentitySnapshot(memory,BASE,shiftedIndex)).isInstanceOf(IOException.class);
    }

    @Test void publicationRejectsChangedRootSlotAndReusedObject() throws Exception {
        for(int mutation=0;mutation<3;mutation++) {
            var memory=fixture();var capture=new ClubIdentitySnapshot(memory,BASE,L);var first=capture.records().getFirst();
            if(mutation==0) memory.put(BASE+L.registryRva(),longs(DESCRIPTOR+8));
            if(mutation==1) memory.blocks.get(TABLE)[first.recordIndex()*8]^=8;
            if(mutation==2) memory.blocks.get(first.sourceAddress())[12]^=1;
            assertThatThrownBy(capture::verify).isInstanceOf(IOException.class).hasMessageContaining("changed");
        }
    }

    private static Blocks fixture() throws Exception {
        var path=Path.of("docs/reverse-engineering/fm26/evidence/club_registry_prefixes_live_20261025.csv");
        assertThat(Files.size(path)).isLessThan(4L*1024*1024);
        var rows=Files.readAllLines(path).stream().skip(1).map(s->s.split(",",-1)).toList();
        int count=rows.stream().mapToInt(r->Integer.parseInt(r[0])).max().orElseThrow()+1;
        var pointers=ByteBuffer.allocate(count*8).order(ByteOrder.LITTLE_ENDIAN);var memory=new Blocks();
        for(var row:rows) {
            int index=Integer.parseInt(row[0]);long address=Long.parseUnsignedLong(row[2],16);
            pointers.putLong(index*8,address);memory.put(address,HexFormat.of().parseHex(row[4]));
            memory.put(address+0xC0,new byte[16]); // Synthetic missing display names, not native evidence.
        }
        memory.put(BASE+L.registryRva(),longs(DESCRIPTOR));memory.put(DESCRIPTOR+L.registryHeader(),longs(HEADER));
        memory.put(HEADER,longs(TABLE,TABLE+pointers.capacity(),TABLE+pointers.capacity()));memory.put(TABLE,pointers.array());
        return memory;
    }
    private static byte[] longs(long... values) {
        var b=ByteBuffer.allocate(values.length*8).order(ByteOrder.LITTLE_ENDIAN);for(long value:values)b.putLong(value);return b.array();
    }
    private static final class Blocks implements ProcessMemoryReader {
        final NavigableMap<Long,byte[]> blocks=new TreeMap<>();
        void put(long address,byte[] raw){blocks.put(address,raw);}
        public int pid(){return -1;}
        public List<MemoryRegion> maps(){return List.of();}
        public byte[] readBytes(long address,int size)throws IOException {
            var block=blocks.floorEntry(address);
            if(block==null||address-block.getKey()+size>block.getValue().length)throw new IOException("Uncaptured fixture span");
            int offset=Math.toIntExact(address-block.getKey());return Arrays.copyOfRange(block.getValue(),offset,offset+size);
        }
        public void close(){}
    }
}
