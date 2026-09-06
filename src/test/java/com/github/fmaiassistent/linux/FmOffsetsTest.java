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
