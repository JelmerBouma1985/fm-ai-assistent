package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PointerTableTest {
    @Test
    void decodesLittleEndianPointersFromOneTableSnapshot() throws Exception {
        PointerTable table = PointerTable.wrap(new byte[] {
                1, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xfe, (byte) 0xdc, (byte) 0xba, (byte) 0x98, 0x76, 0x54, 0x32, 0x10
        });

        assertThat(table.size()).isEqualTo(2);
        assertThat(table.pointerAt(0)).isEqualTo(1L);
        assertThat(table.pointerAt(1)).isEqualTo(0x1032547698badcfeL);
    }

    @Test
    void rejectsTruncatedPointerTables() {
        assertThatThrownBy(() -> PointerTable.wrap(new byte[7]))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("aligned");
    }

    @Test
    void rejectsInvalidBoundsBeforeReadingMemory() {
        ProcessMemoryReader reader = mock(ProcessMemoryReader.class);
        for (FmOffsets.Bounds bounds : new FmOffsets.Bounds[] {
                new FmOffsets.Bounds(16, 15),
                new FmOffsets.Bounds(16, 25),
                new FmOffsets.Bounds(-8, 8)
        }) {
            assertThatThrownBy(() -> PointerTable.read(reader, bounds, "People"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("bounds");
        }
        verifyNoInteractions(reader);
    }
}
