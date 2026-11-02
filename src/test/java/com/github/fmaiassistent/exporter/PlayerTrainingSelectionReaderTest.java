package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerTrainingSelectionLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlayerTrainingSelectionReaderTest {
    @Test void replaysCapturedNativeRoleClassifiersAndPlayerAbility() throws Exception {
        var memory = captured();
        var result = new PlayerTrainingSelectionReader().read(memory, memory.player, memory.module, 128);
        assertThat(result.roleBit()).isEqualTo(65536);
        assertThat(result.nativeAttributeIDs()).hasSize(20).contains(16,17,21);
        var ids = result.nativeAttributeIDs(); ids[0] = -1;
        assertThat(result.nativeAttributeIDs()[0]).isNotNegative();
    }

    @Test void alteredNativeCodeAndOutOfRangeAttributesFailClosed() throws Exception {
        var memory = captured();
        memory.bytes.put(memory.module + 0x46F1A80, (byte) 0);
        var corruptCode = memory;
        assertThatThrownBy(() -> new PlayerTrainingSelectionReader().read(corruptCode, corruptCode.player, corruptCode.module, 128))
                .isInstanceOf(IOException.class).hasMessageContaining("native code anchor");
        memory = captured();
        memory.bytes.put(memory.player + 0x15F, (byte) 101);
        var bad = memory;
        assertThatThrownBy(() -> new PlayerTrainingSelectionReader().read(bad, bad.player, bad.module, 128))
                .isInstanceOf(IOException.class).hasMessageContaining("attribute range anchor");
    }

    @Test void eachShiftedMemberRejectsTheSameCapturedFixture() throws Exception {
        var memory = captured();
        var layout = PlayerTrainingSelectionLayout.current();
        var components = layout.getClass().getRecordComponents();
        for (int changed = 0; changed < components.length - 1; changed++) {
            var types = new Class<?>[components.length];
            var values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                Object value = components[i].getAccessor().invoke(layout);
                if (i == changed) {
                    if (value instanceof Long number) value = Long.valueOf(number + 1);
                    else value = Integer.valueOf((Integer) value + 1);
                }
                values[i] = value;
            }
            var shifted = layout.getClass().getDeclaredConstructor(types).newInstance(values);
            assertThatThrownBy(() -> new PlayerTrainingSelectionReader(shifted).read(memory, memory.player, memory.module, 128))
                    .isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
        }
        for (int i = 0; i < layout.codeAnchors().size(); i++) {
            var anchors = new ArrayList<>(layout.codeAnchors());
            var old = anchors.get(i);
            anchors.set(i, new PlayerTrainingSelectionLayout.CodeAnchor(old.rva()+1,old.bytes(),old.sha256()));
            var shifted = new PlayerTrainingSelectionLayout(layout.positionRolesRva(),layout.attributesBeginRva(),
                    layout.attributesEndRva(),layout.positions(),layout.attributeBase(),layout.firstAttribute(),
                    layout.currentAbility(),layout.cachedAbility(),anchors);
            assertThatThrownBy(() -> new PlayerTrainingSelectionReader(shifted).read(memory,memory.player,memory.module,128))
                    .isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
        }
    }

    @Test void nativeWeightingAndRoleMinimumAbilityArePreserved() {
        byte[] attrs = new byte[54]; Arrays.fill(attrs,(byte)50);
        byte[] positions = new byte[15]; Arrays.fill(positions,(byte)20);
        byte[] importance = new byte[54]; Arrays.fill(importance,(byte)-1);
        importance[0]=0; importance[1]=1;
        assertThat(PlayerTrainingSelectionReader.ability(attrs,0,positions,110,100,1,1,0,importance)).isEqualTo(110);
        assertThat(PlayerTrainingSelectionReader.ability(attrs,0,positions,110,100,1L<<20,1,0,importance)).isEqualTo(55);
        importance[0]=2; importance[1]=-1;
        assertThat(PlayerTrainingSelectionReader.ability(attrs,0,positions,120,100,1,1,0,importance)).isEqualTo(120);
    }

    @Test void explicitPersonalRoleBypassesDefaultRoleScoringIncludingExcludedRoles() throws Exception {
        var memory = captured();
        var reader = new PlayerTrainingSelectionReader();
        var explicit = reader.read(memory, memory.player, memory.module, 128, 1L << 20);
        assertThat(explicit.roleBit()).isEqualTo(1L << 20);
        assertThat(explicit.nativeAttributeIDs()).isNotEmpty().doesNotContain(15);
        assertThat(reader.read(memory, memory.player, memory.module, 128).roleBit()).isEqualTo(65536);
        assertThatThrownBy(() -> reader.read(memory, memory.player, memory.module, 128, 3L))
                .isInstanceOf(IOException.class).hasMessageContaining("role range anchor");
    }

    private static Memory captured() throws Exception {
        try(var stream=PlayerTrainingSelectionReaderTest.class.getResourceAsStream("/fm27/player-training-selection-captured.json")) {
            assertThat(stream).isNotNull();
            byte[] bytes=stream.readNBytes(262145); assertThat(bytes.length).isLessThanOrEqualTo(262144);
            var json=JsonMapper.builder().build().readTree(bytes);
            var memory=new Memory(json.path("player").asLong(),json.path("module").asLong());
            json.path("memory").properties().forEach(entry->{
                long address=Long.parseLong(entry.getKey().split(":")[0]);
                byte[] data=HexFormat.of().parseHex(entry.getValue().asString());
                for(int i=0;i<data.length;i++) memory.bytes.put(address+i,data[i]);
            });
            return memory;
        }
    }
    private static class Memory implements ProcessMemoryReader {
        final long player,module; final Map<Long,Byte> bytes=new HashMap<>();
        Memory(long player,long module){this.player=player;this.module=module;}
        public int pid(){return 22232;}
        public byte[] readBytes(long at,int size)throws IOException {
            byte[] result=new byte[size];
            for(int i=0;i<size;i++) {
                Byte value=bytes.get(at+i);
                if(value==null)throw new IOException("Uncaptured selection byte "+Long.toHexString(at+i));
                result[i]=value;
            }
            return result;
        }
        public List<MemoryRegion> maps(){return List.of();}
        public void close(){}
    }
}
