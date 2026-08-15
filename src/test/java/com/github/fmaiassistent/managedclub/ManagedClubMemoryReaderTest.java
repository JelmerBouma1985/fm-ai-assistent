package com.github.fmaiassistent.managedclub;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedClubMemoryReaderTest {
    private static final long PLUGIN = 0x1000_0000L;
    private static final long MANAGER = 0x2000_0000L;
    private static final long EMPLOYMENT = 0x3000_0000L;
    private static final long TEAM = 0x4000_0000L;
    private static final long CLUB = 0x5000_0000L;

    @Test
    void resolvesCurrentHumanManagerAndManagedClubThroughRamPointers() throws Exception {
        FakeMemory memory = validMemory();

        ManagedClubIdentity identity = new ManagedClubMemoryReader()
                .read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN);

        assertThat(identity.managerAddress()).isEqualTo(MANAGER);
        assertThat(identity.managerName()).isEqualTo("Jelmer Bouma");
        assertThat(identity.teamAddress()).isEqualTo(TEAM);
        assertThat(identity.clubAddress()).isEqualTo(CLUB);
        assertThat(identity.clubName()).isEqualTo("sc Heerenveen");
    }

    @Test
    void rejectsUnknownBuildInsteadOfReadingAStalePointer() {
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(new FakeMemory(), 0x123456, PLUGIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not known for FM build 0x123456");
    }

    @Test
    void reportsMissingRelationshipPointerClearly() {
        FakeMemory memory = validMemory();
        memory.remove(EMPLOYMENT + 0x10, Long.BYTES);

        assertThatThrownBy(() -> new ManagedClubMemoryReader()
                .read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FM did not expose a valid manager team pointer");
    }

    private static FakeMemory validMemory() {
        long managerRva = FmOffsets.currentHumanManagerRva(FmOffsets.DEFAULT_BUILD).orElseThrow();
        FakeMemory memory = new FakeMemory();
        memory.putLong(PLUGIN + managerRva, MANAGER);
        memory.putFmStringReference(MANAGER + 0x40, 0x6000_0000L, "Jelmer Bouma");
        memory.putLong(MANAGER + 0xA8, EMPLOYMENT);
        memory.putLong(EMPLOYMENT + 0x10, TEAM);
        memory.putLong(TEAM + 0x30, CLUB);
        memory.putFmStringReference(CLUB + 0xC8, 0x7000_0000L, "sc Heerenveen");
        return memory;
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();

        void putLong(long address, long value) {
            put(address, ByteBuffer.allocate(Long.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(value)
                    .array());
        }

        void putFmStringReference(long referenceAddress, long stringAddress, String value) {
            putLong(referenceAddress, stringAddress);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            put(stringAddress, ByteBuffer.allocate(Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(text.length)
                    .array());
            put(stringAddress + Integer.BYTES, text);
        }

        void remove(long address, int size) {
            for (int index = 0; index < size; index++) {
                bytes.remove(address + index);
            }
        }

        private void put(long address, byte[] value) {
            for (int index = 0; index < value.length; index++) {
                bytes.put(address + index, value[index]);
            }
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] value = new byte[size];
            for (int index = 0; index < size; index++) {
                Byte next = bytes.get(address + index);
                if (next == null) {
                    throw new IOException("unmapped test address 0x" + Long.toHexString(address + index));
                }
                value[index] = next;
            }
            return value;
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
