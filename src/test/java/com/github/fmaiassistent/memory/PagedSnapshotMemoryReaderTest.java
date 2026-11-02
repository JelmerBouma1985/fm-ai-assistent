package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PagedSnapshotMemoryReaderTest {
    @Test void reusesPagesAcrossPrimitiveAndCrossPageReads() throws Exception {
        var memory = new Memory();
        try (var snapshot = new PagedSnapshotMemoryReader(memory, 2)) {
            assertThat(snapshot.readU8(0x1001)).isEqualTo(1);
            assertThat(snapshot.readU16(0x1002)).isEqualTo(0x0302);
            assertThat(memory.calls).isEqualTo(1);
            assertThat(snapshot.readBytes(0x1FFE, 4)).containsExactly((byte)254,(byte)255,(byte)0,(byte)1);
            assertThat(memory.calls).isEqualTo(2);
        }
    }

    @Test void boundsCachedPagesAndDiscardsThemAtPhaseEndWithoutClosingDelegate() throws Exception {
        var memory = new Memory();
        var snapshot = new PagedSnapshotMemoryReader(memory, 2);
        snapshot.readU8(0x1001); snapshot.readU8(0x2001); snapshot.readU8(0x3001); snapshot.readU8(0x1001);
        assertThat(memory.calls).isEqualTo(4);
        snapshot.close();
        assertThat(memory.closed).isFalse();
        assertThatThrownBy(() -> snapshot.readU8(0x1001)).isInstanceOf(IOException.class).hasMessageContaining("closed");
        try (var fresh = new PagedSnapshotMemoryReader(memory, 2)) { fresh.readU8(0x1001); }
        assertThat(memory.calls).isEqualTo(5);
    }

    @Test void preservesValidNarrowReadsWhenSurroundingPageIsNotReadable() throws Exception {
        var memory = new Memory(); memory.sparse = true;
        try (var snapshot = new PagedSnapshotMemoryReader(memory, 2)) {
            assertThat(snapshot.readU16(0x1001)).isEqualTo(0x0201);
            assertThat(memory.calls).isEqualTo(2);
            assertThatThrownBy(() -> snapshot.readBytes(0,8*1024*1024+1)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> snapshot.readBytes(ProcessMemoryReader.MAX_USER_ADDRESS,2)).isInstanceOf(IOException.class);
            assertThat(memory.calls).isEqualTo(2);
        }
    }

    private static class Memory implements ProcessMemoryReader {
        int calls; boolean sparse,closed;
        public int pid(){return 1;}
        public byte[] readBytes(long address,int size)throws IOException {
            calls++;
            if(sparse && size==4096) throw new IOException("Unmapped surrounding page");
            byte[] result=new byte[size];
            for(int i=0;i<size;i++)result[i]=(byte)(address+i);
            return result;
        }
        public List<MemoryRegion> maps(){return List.of();}
        public void close(){closed=true;}
    }
}
