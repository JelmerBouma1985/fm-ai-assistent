package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PlayerFitnessExporterTest {
    @Test void ordinaryRefreshWithAutomaticModuleBaseEntersFitnessCapture() {
        var reader = new Memory();
        var row = Map.<String,Object>of("record","0x10000","index",1,"unique_id",2L);
        assertThatThrownBy(() -> PlayerFitnessExporter.capture(reader,null,List.of(row),null))
                .isInstanceOf(IOException.class).hasMessage("Reached fitness identity read");
        assertThat(reader.mapsRead).isTrue();
        assertThat(reader.identityRead).isTrue();
    }

    @Test void suppliedModuleDoesNotRequireAnotherMappingLookup() throws Exception {
        var reader = new Memory();
        PlayerFitnessExporter.capture(reader,0x10000000L,List.of(),null);
        assertThat(reader.mapsRead).isFalse();
        assertThat(reader.identityRead).isFalse();
    }

    private static class Memory implements ProcessMemoryReader {
        boolean mapsRead,identityRead;
        public Platform platform(){return Platform.WINDOWS;}
        public int pid(){return 1;}
        public List<MemoryRegion> maps(){
            mapsRead=true;
            return List.of(new MemoryRegion(0x10000000L,0x20000000L,"r--",0,"","","game_plugin.dll"));
        }
        public byte[] readBytes(long address,int size)throws IOException {
            identityRead=true;
            assertThat(address).isEqualTo(0x10000L);
            throw new IOException("Reached fitness identity read");
        }
        public void close(){}
    }
}
