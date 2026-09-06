package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerExporterTest {
    private static final long PERSON = 0x4000;

    @Test
    void rejectsStaffWhosePotentialAbilityOverlapsPlayerCurrentAbility() {
        FakeMemory memory = memoryWithType(PersonMemoryClassifier.STAFF_DYNAMIC_OFFSET);
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL, 199);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL, 4);

        assertThatThrownBy(() -> new PlayerExporter().decodeRow(memory, 1, PERSON, "", null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a valid player: STAFF");
    }

    @Test
    void rejectsUnknownPersonTypeEvenWhenAbilityValuesLookValid() throws Exception {
        FakeMemory memory = memoryWithType(0);
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL, 150);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL, 170);
        putPlausiblePlayerBlock(memory, 0, true);

        PersonMemoryClassifier.PersonType type = new PersonMemoryClassifier(memory).classify(PERSON).type();

        assertThat(type).isEqualTo(PersonMemoryClassifier.PersonType.UNKNOWN);
        assertThat(PlayerExporter.playerMemoryLayout(memory, PERSON, type)).isEmpty();
    }

    @Test
    void selectsPrimaryLayoutForPurePlayer() throws Exception {
        FakeMemory memory = memoryWithType(PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET);
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL, 173);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL, 180);
        putPlausiblePlayerBlock(memory, 0, true);

        PersonMemoryClassifier.PersonType type = new PersonMemoryClassifier(memory).classify(PERSON).type();
        PlayerExporter.PlayerMemoryLayout layout = PlayerExporter.playerMemoryLayout(memory, PERSON, type).orElseThrow();

        assertThat(type).isEqualTo(PersonMemoryClassifier.PersonType.PLAYER);
        assertThat(layout.recordRelShift()).isZero();
        assertThat(layout.historyCopySourceRel()).isEqualTo(AttributeDefinitions.HISTORY_COPY_SOURCE_REL);
        assertThat(layout.ca()).isEqualTo(173);
        assertThat(layout.pa()).isEqualTo(180);
    }

    @Test
    void selectsShiftedLayoutForPlayerStaff() throws Exception {
        FakeMemory memory = memoryWithType(PersonMemoryClassifier.PLAYER_STAFF_DYNAMIC_OFFSET);
        int relativeShift = -PersonMemoryClassifier.PLAYER_STAFF_SHIFT;
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL + relativeShift, 145);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL + relativeShift, 160);
        putPlausiblePlayerBlock(memory, relativeShift, true);

        PersonMemoryClassifier.PersonType type = new PersonMemoryClassifier(memory).classify(PERSON).type();
        PlayerExporter.PlayerMemoryLayout layout = PlayerExporter.playerMemoryLayout(memory, PERSON, type).orElseThrow();

        assertThat(type).isEqualTo(PersonMemoryClassifier.PersonType.PLAYER_STAFF);
        assertThat(layout.recordRelShift()).isEqualTo(-0xf8);
        assertThat(layout.historyCopySourceRel()).isEqualTo(AttributeDefinitions.HISTORY_COPY_SOURCE_REL - 0xf8);
        assertThat(layout.ca()).isEqualTo(145);
        assertThat(layout.pa()).isEqualTo(160);
    }

    @Test
    void rejectsPlayerBlockWithoutAnyPlayablePosition() throws Exception {
        FakeMemory memory = memoryWithType(PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET);
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL, 120);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL, 140);
        putPlausiblePlayerBlock(memory, 0, false);

        PersonMemoryClassifier.PersonType type = new PersonMemoryClassifier(memory).classify(PERSON).type();

        assertThat(PlayerExporter.playerMemoryLayout(memory, PERSON, type)).isEmpty();
    }

    private static FakeMemory memoryWithType(int dynamicOffset) {
        FakeMemory memory = new FakeMemory();
        long vtable = 0x6000;
        long metadata = 0x7000;
        memory.putLong(PERSON, vtable);
        memory.putLong(vtable - Long.BYTES, metadata);
        memory.putI32(metadata + 4, dynamicOffset);
        return memory;
    }

    private static void putPlausiblePlayerBlock(FakeMemory memory, int relativeShift, boolean playablePosition) {
        long source = PERSON + AttributeDefinitions.HISTORY_COPY_SOURCE_REL + relativeShift;
        int size = 0x6e - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET + 1;
        memory.fill(source, size, 0);
        if (playablePosition) {
            FieldDef position = AttributeDefinitions.POSITION_FIELDS.get(0);
            memory.putU8(source + position.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 20);
        }
        for (FieldDef attribute : AttributeDefinitions.VISIBLE_FIELDS) {
            memory.putU8(source + attribute.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 50);
        }
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();

        void fill(long address, int length, int value) {
            for (int i = 0; i < length; i++) {
                putU8(address + i, value);
            }
        }

        void putLong(long address, long value) {
            put(address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        void putI32(long address, int value) {
            put(address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        }

        void putI16(long address, int value) {
            put(address, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
        }

        void putU8(long address, int value) {
            bytes.put(address, (byte) value);
        }

        private void put(long address, byte[] value) {
            for (int i = 0; i < value.length; i++) {
                bytes.put(address + i, value[i]);
            }
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] out = new byte[size];
            for (int i = 0; i < size; i++) {
                Byte value = bytes.get(address + i);
                if (value == null) {
                    throw new IOException("unmapped 0x" + Long.toHexString(address + i));
                }
                out[i] = value;
            }
            return out;
        }

        @Override
        public List<MemoryRegion> maps() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
