package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.MemoryRegion;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FmOffsetsTest {
    @Test
    void identifiedModuleImageChoosesItsOwnRootsOverTheBuildGuess() {
        String gamePass = com.github.fmaiassistent.memory.NativeModuleProfile.GAME_PASS_SHA256;
        String updated = com.github.fmaiassistent.memory.NativeModuleProfile.UPDATED_SHA256;
        assertThat(FmOffsets.currentDateRva(gamePass, FmOffsets.DEFAULT_BUILD)).isEqualTo(0x56257c8L);
        assertThat(FmOffsets.currentHumanManagerRva(gamePass, FmOffsets.DEFAULT_BUILD)).hasValue(0x5672618L);
        assertThat(FmOffsets.knownTableRva(gamePass, FmOffsets.DEFAULT_BUILD)).isEqualTo(0x5683bd8L);
        assertThat(FmOffsets.currentDateRva(updated, FmOffsets.DEFAULT_BUILD)).isEqualTo(0x56af378L);
        // An unidentified image keeps the build-keyed roots.
        assertThat(FmOffsets.currentDateRva(null, FmOffsets.DEFAULT_BUILD)).isEqualTo(0x57ca3b8L);
        assertThat(FmOffsets.currentHumanManagerRva(null, FmOffsets.DEFAULT_BUILD)).hasValue(0x5810308L);
        assertThat(FmOffsets.knownTableRva(null, 0x25f1a7)).isEqualTo(0x57035e8L);
        assertThat(FmOffsets.currentDateRva(null, FmOffsets.DEFAULT_BUILD + 1)).isNull();
    }

    @Test
    void scansOversizedWritableMappingsInBoundedChunksBeforeExecutableMappings() throws Exception {
        long base = 0x1000_0000L;
        var code = region(base, base + 0x1000, "r-x");
        var data = region(base + 0x1000, base + 90_000_000, "rw-");
        ProcessMemoryReader reader = scanningReader(987656, base + 0x2000, List.of(code, data));

        assertThat(FmOffsets.peopleBounds(reader, 0x123456, base).count()).isEqualTo(94815);
        verify(reader, never()).readBytes(org.mockito.ArgumentMatchers.eq(base), anyInt());
    }

    @Test
    void findsTablesAcrossChunkAndReadableMappingBoundariesAndAtTheLastPossibleOffset() throws Exception {
        long base = 0x1000_0000L;
        int chunk = 1024 * 1024, tableBytes = 0x1a8;
        int step = chunk - tableBytes;
        long[] tables = {base + step - Long.BYTES, base + 0x1000 - 0x110, base + 0x1000 - tableBytes, base + step};
        List<List<MemoryRegion>> mappings = List.of(
                List.of(region(base, base + chunk * 2L, "rw-")),
                List.of(region(base, base + 0x1000, "rw-"), region(base + 0x1000, base + 0x2000, "r--")),
                List.of(region(base, base + 0x1000, "rw-")),
                List.of(region(base + 1, base + chunk * 2L, "rw-")));

        for (int i = 0; i < tables.length; i++) {
            ProcessMemoryReader reader = scanningReader(987657 + i, tables[i], mappings.get(i));
            assertThat(FmOffsets.peopleBounds(reader, 0x123456, base).count()).as("boundary case %s", i).isEqualTo(94815);
        }
    }

    private static MemoryRegion region(long start, long end, String permissions) {
        return new MemoryRegion(start, end, permissions, 0, "", "", "game_plugin.dll");
    }

    private static ProcessMemoryReader scanningReader(int pid, long table, List<MemoryRegion> regions) throws Exception {
        ProcessMemoryReader reader = mock(ProcessMemoryReader.class);
        when(reader.pid()).thenReturn(pid);
        var mappings = new java.util.ArrayList<>(regions);
        mappings.add(new MemoryRegion(0x2000_0000L, 0x2100_0000L, "rw-", 0, "", "", ""));
        when(reader.maps()).thenReturn(mappings);
        byte[] raw = new byte[0x1a8];
        var data = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Map<Long, Long> words = new HashMap<>();
        long[][] slots = {{0xf0,58475},{0xf8,23998},{0x100,8224},{0x108,7},{0x110,251},{0x118,173},
                {0x150,94815},{0x160,28},{0x168,27418},{0x180,50345},{0x1a0,6}};
        for (long[] slot : slots) {
            long pointer = 0x2000_0000L + slot[0] * 0x1000, header = pointer + 0x100;
            data.putLong((int)slot[0], pointer);
            words.put(table + slot[0], pointer);
            words.put(pointer + 0x80, header);
            words.put(header, 0x3000_0000L);
            words.put(header + 8, 0x3000_0000L + slot[1] * Long.BYTES);
        }
        when(reader.readU64(anyLong())).thenAnswer(call -> words.getOrDefault(call.<Long>getArgument(0), 0L));
        when(reader.readBytes(anyLong(), anyInt())).thenAnswer(call -> {
            long start = call.getArgument(0);
            int length = call.getArgument(1);
            assertThat(length).isBetween(1, 1024 * 1024);
            byte[] chunk = new byte[length];
            long from = Math.max(start, table), to = Math.min(start + length, table + raw.length);
            if (to > from) System.arraycopy(raw, (int)(from - table), chunk, (int)(from - start), (int)(to - from));
            return chunk;
        });
        return reader;
    }

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
        assertThat(FmOffsets.DEFAULT_BUILD).isEqualTo(2494687);
        assertThat(profile.offsetTableRva()).isEqualTo(0x57035e8L);
        assertThat(profile.currentDateRva()).hasValue(0x56ad378L);
        assertThat(profile.currentHumanManagerRva()).hasValue(0x56f32c8L);
        var updated = FmOffsets.requireProfile(2493426);
        assertThat(updated.offsetTableRva()).isEqualTo(0x57055e8L);
        assertThat(updated.currentDateRva()).hasValue(0x56af378L);
        assertThat(updated.currentHumanManagerRva()).hasValue(0x56f52c8L);
        var latest = FmOffsets.requireProfile(FmOffsets.DEFAULT_BUILD);
        assertThat(latest.offsetTableRva()).isEqualTo(0x5820628L);
        assertThat(latest.currentDateRva()).hasValue(0x57ca3b8L);
        assertThat(latest.currentHumanManagerRva()).hasValue(0x5810308L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FmOffsets.requireProfile(0x123456)).isInstanceOf(IllegalArgumentException.class);
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
