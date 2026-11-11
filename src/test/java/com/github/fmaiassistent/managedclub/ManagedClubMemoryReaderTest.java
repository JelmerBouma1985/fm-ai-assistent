package com.github.fmaiassistent.managedclub;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.MemoryRegion;
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
        assertThat(identity.managerUniqueId()).isEqualTo(4242L);
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
    void rejectsLegacyAndShiftedManagerRootsForTheUpdatedCapture() {
        FakeMemory memory = validMemory();
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(memory, 2486695, PLUGIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("human manager pointer");
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(memory, 2494687, PLUGIN + 8))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("human manager pointer");
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

    @Test
    void readableNullEmploymentMeansUnemployed() throws Exception {
        FakeMemory memory = validMemory();
        memory.putLong(MANAGER + 0xA8, 0);

        ManagedClubIdentity identity = new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN);

        assertThat(identity.unemployed()).isTrue();
        assertThat(identity.managerUniqueId()).isEqualTo(4242L);
        assertThat(identity.managerName()).isEqualTo("Jelmer Bouma");
        assertThat(identity.clubAddress()).isZero();
        assertThat(identity.teamAddress()).isZero();
    }

    @Test
    void unreadableOrMalformedEmploymentIsNotUnemployment() {
        FakeMemory memory = validMemory();
        memory.remove(MANAGER + 0xA8, Long.BYTES);
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN))
                .isInstanceOf(IOException.class);
        memory.putLong(MANAGER + 0xA8, -1);
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("employment pointer");
    }

    @Test
    void discoversNetherlandsFromAdditionalEmploymentWithoutAClub() throws Exception {
        FakeMemory memory = nationalMemory(false);
        var identity = new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN,
                com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256);
        assertThat(identity.unemployed()).isFalse();
        assertThat(identity.clubAddress()).isZero();
        assertThat(identity.nationalTeam().name()).isEqualTo("Netherlands");
        assertThat(identity.nationalTeam().nationUniqueId()).isEqualTo(784);
    }

    @Test
    void preservesClubAndNationForDualRoleManager() throws Exception {
        var identity = new ManagedClubMemoryReader().read(nationalMemory(true), FmOffsets.DEFAULT_BUILD, PLUGIN,
                com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256);
        assertThat(identity.clubName()).isEqualTo("sc Heerenveen");
        assertThat(identity.nationalTeam().name()).isEqualTo("Netherlands");
    }

    @Test
    void recognizesNationalEmploymentInThePrimarySlotToo() throws Exception {
        FakeMemory memory = nationalMemory(false);
        memory.putLong(MANAGER + 0xA8, 0x3100_0000L);
        memory.putLong(MANAGER + 0xB0, 0);
        var identity = new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN,
                com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256);
        assertThat(identity.clubAddress()).isZero();
        assertThat(identity.nationalTeam().name()).isEqualTo("Netherlands");
    }

    @Test
    void rejectsShiftedNationLayoutOnTheSameFixture() throws Exception {
        FakeMemory memory = nationalMemory(false);
        var reader = new ManagedClubMemoryReader();
        var profile = com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256;
        var layout = com.github.fmaiassistent.memory.NationalTeamLayout.current();
        assertThat(reader.readNationalTeam(memory, PLUGIN, profile, MANAGER, 0, layout).name())
                .isEqualTo("Netherlands");
        var shifted = new com.github.fmaiassistent.memory.NationalTeamLayout(
                layout.nationRel() + 8, layout.nameRel(), layout.alternateNameRel(), layout.bytes() + 8);
        assertThatThrownBy(() -> reader.readNationalTeam(memory, PLUGIN, profile, MANAGER, 0, shifted))
                .isInstanceOf(IOException.class).hasMessageContaining("range anchor");
    }

    @Test
    void rejectsIncorrectNationalContractOwnerAndNationIdentity() {
        FakeMemory memory = nationalMemory(false);
        var profile = com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256;
        memory.putLong(0x3100_0000L + 8, MANAGER + 8);
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN, profile))
                .isInstanceOf(IOException.class).hasMessageContaining("owner/type anchor");
        memory.putLong(0x3100_0000L + 8, MANAGER);
        memory.putInt(0x5200_0000L + 12, 999);
        assertThatThrownBy(() -> new ManagedClubMemoryReader().read(memory, FmOffsets.DEFAULT_BUILD, PLUGIN, profile))
                .isInstanceOf(IOException.class).hasMessageContaining("nation identity anchor");
    }

    private static FakeMemory nationalMemory(boolean hasClub) {
        FakeMemory memory = validMemory();
        String profile = com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256;
        var contracts = com.github.fmaiassistent.memory.ContractRecordLayout.forModule(profile);
        if (!hasClub) memory.putLong(MANAGER + 0xA8, 0);
        long slots = 0x3200_0000L, contract = 0x3100_0000L, team = 0x4100_0000L;
        long container = 0x5100_0000L, nation = 0x5200_0000L;
        memory.putLong(MANAGER + 0xB0, slots);
        memory.put(slots, new byte[32]);
        // Live Netherlands contract occupies additional slot 2, not the club's primary slot.
        memory.putLong(slots + 16, contract);
        memory.putLong(contract, PLUGIN + contracts.full().vtable());
        memory.putLong(contract + 8, MANAGER);
        memory.putLong(contract + 0x10, team);
        memory.putLong(team, PLUGIN + com.github.fmaiassistent.memory.ClubIdentityLayout.forModule(profile).nationalTeamVtable());
        memory.put(team + 0x2F, new byte[] {1});
        memory.putLong(team + 0x30, container);
        memory.putLong(container, PLUGIN + contracts.teamOwner().nationalContainerVtable());
        memory.putInt(container + 12, 784);
        memory.putLong(container + 0x170, nation);
        memory.putInt(nation + 12, 784);
        memory.putFmStringReference(nation + 0x18, 0x7100_0000L, "Netherlands");
        return memory;
    }

    private static FakeMemory validMemory() {
        long managerRva = FmOffsets.currentHumanManagerRva(FmOffsets.DEFAULT_BUILD).orElseThrow();
        FakeMemory memory = new FakeMemory();
        memory.putLong(PLUGIN + managerRva, MANAGER);
        memory.putInt(MANAGER + 0x0C, 4242);
        memory.putFmStringReference(MANAGER + 0x40, 0x6000_0000L, "Jelmer Bouma");
        memory.putLong(MANAGER + 0xA8, EMPLOYMENT);
        memory.putLong(MANAGER + 0xB0, 0);
        memory.putLong(EMPLOYMENT + 0x10, TEAM);
        memory.put(TEAM + 0x2F, new byte[] {0});
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

        void putInt(long address, int value) {
            put(address, ByteBuffer.allocate(Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value)
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
