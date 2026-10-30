package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FmOffsetsTest {
    @Test
    void fallbackFindsCapturedFm27TableBeyondTheOldScanWindow() throws Exception {
        ProcessMemoryReader reader=mock(ProcessMemoryReader.class);
        long base=0x1000_0000L,table=base+0x57035e8L;
        when(reader.pid()).thenReturn(987655);
        var region=new com.github.fmaiassistent.memory.MemoryRegion(table,table+0x1b0,"rw-",0,"","","game_plugin.dll");
        var heap=new com.github.fmaiassistent.memory.MemoryRegion(0x2000_0000L,0x2100_0000L,"rw-",0,"","","");
        when(reader.maps()).thenReturn(java.util.List.of(region,heap));
        byte[] raw=new byte[0x1b0];
        var data=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long[][] slots={{0xf0,58475},{0xf8,23998},{0x100,8224},{0x108,7},{0x110,251},{0x118,173},
                {0x150,94815},{0x160,28},{0x168,27418},{0x180,50345},{0x1a0,6}};
        for(long[] slot:slots){
            long ptr=0x2000_0000L+slot[0]*0x1000,header=ptr+0x100;
            data.putLong((int)slot[0],ptr);
            when(reader.readU64(table+slot[0])).thenReturn(ptr);
            when(reader.readU64(ptr+0x80)).thenReturn(header);
            when(reader.readU64(header)).thenReturn(0x3000_0000L);
            when(reader.readU64(header+8)).thenReturn(0x3000_0000L+slot[1]*8);
        }
        when(reader.readBytes(table,raw.length)).thenReturn(raw);
        // A deliberately unregistered build forces the bounded fallback, independent of known RVA lookup.
        assertThat(FmOffsets.peopleBounds(reader,0x25f1a8,base).count()).isEqualTo(94815);
    }

    @Test
    void fm27RootsMatchTheCapturedNativeRegistryAndAccessors() {
        var profile=FmOffsets.requireProfile(2486695);
        assertThat(FmOffsets.DEFAULT_BUILD).isEqualTo(2486695);
        assertThat(profile.offsetTableRva()).isEqualTo(0x57035e8L);
        assertThat(profile.currentDateRva()).hasValue(0x56ad378L);
        assertThat(profile.currentHumanManagerRva()).hasValue(0x56f32c8L);
        assertThat(FmOffsets.requireProfile(0x238bdd).offsetTableRva()).isEqualTo(0x4e49490L);
    }

    @Test
    void acceptsLargeDatabaseAtKnownOffsetWithoutFallbackScan() throws Exception {
        ProcessMemoryReader reader = mock(ProcessMemoryReader.class);
        long base = 0x1000_0000L;
        long table = base + FmOffsets.tableRva(FmOffsets.DEFAULT_BUILD);
        when(reader.pid()).thenReturn(987654);
        long[][] slots = {
                {0x0F0, 250_000}, {0x0F8, 90_000}, {0x100, 40_000},
                {0x108, 7}, {0x110, 250}, {0x118, 150},
                {0x150, 400_000}, {0x160, 28}, {0x168, 120_000},
                {0x180, 180_000}, {0x1A0, 500}
        };
        for (long[] slot : slots) {
            long pointer = 0x2000_0000L + slot[0] * 0x1000;
            long bounds = pointer + 0x100;
            when(reader.readU64(table + slot[0])).thenReturn(pointer);
            when(reader.readU64(pointer + 0x80)).thenReturn(bounds);
            when(reader.readU64(bounds)).thenReturn(0x3000_0000L);
            when(reader.readU64(bounds + 8)).thenReturn(0x3000_0000L + slot[1] * Long.BYTES);
        }

        assertThat(FmOffsets.peopleBounds(reader, FmOffsets.DEFAULT_BUILD, base).count())
                .isEqualTo(400_000);
        verify(reader, never()).maps();
    }
}
