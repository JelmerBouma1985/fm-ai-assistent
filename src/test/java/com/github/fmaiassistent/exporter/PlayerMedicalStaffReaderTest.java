package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerMedicalStaffLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerMedicalStaffReaderTest {
    private static final LocalDate DATE = LocalDate.of(2026,8,3);

    @Test
    void resolvesCapturedClubSportsScientistAndDeterministicAssessmentSeed() throws Exception {
        var memory = captured();
        var result = new PlayerMedicalStaffReader().defaultStaff(memory,memory.player,memory.module,DATE);
        assertThat(result.uniqueId()).isEqualTo(37088493);
        assertThat(result.sportsScience()).isEqualTo(17);
        assertThat(result.seed()).isEqualTo(203);
        assertThat(result.availabilityFlags()).isNull();
    }

    @Test
    void firstPhysioTakesPrecedenceOverSportsScientistScore() throws Exception {
        var memory = captured();
        var layout = PlayerMedicalStaffLayout.current();
        long team = memory.readU64(memory.player + layout.currentTeam());
        long club = memory.readU64(team + layout.teamClub());
        long staff = memory.readU64(memory.readU64(club + layout.clubStaff()));
        long person = staff + memory.readI32(memory.readU64(staff) + layout.personDisplacement());
        long contract = memory.readU64(person + layout.contract());
        // The captured execution only requested the selected scientist's UID.
        memory.put(person + layout.uniqueId(),123456,4);
        memory.put(contract + layout.primaryRole(),41,1);
        memory.put(staff + layout.sportsScience(),5,1);
        var result = new PlayerMedicalStaffReader().defaultStaff(memory,memory.player,memory.module,DATE);
        assertThat(result.uniqueId()).isEqualTo(123456);
        assertThat(result.sportsScience()).isEqualTo(1);
    }

    @Test
    void missingTeamAndNationalTeamHaveNoClubDefaultPhysician() throws Exception {
        var memory = captured();
        long team = memory.readU64(memory.player + 0x130);
        memory.put(team + 0x2F,1,1);
        assertThat(new PlayerMedicalStaffReader().defaultStaff(memory,memory.player,memory.module,DATE)).isNull();
        memory.put(memory.player + 0x130,0,8);
        assertThat(new PlayerMedicalStaffReader().defaultStaff(memory,memory.player,memory.module,DATE)).isNull();
    }

    @Test
    void checksAllFourSecondaryContractSlotsForClubPhysio() throws Exception {
        var memory = captured();
        var layout = PlayerMedicalStaffLayout.current();
        long team = memory.readU64(memory.player + layout.currentTeam());
        long club = memory.readU64(team + layout.teamClub());
        long staff = memory.readU64(memory.readU64(club + layout.clubStaff()));
        long person = staff + memory.readI32(memory.readU64(staff) + layout.personDisplacement());
        long main = memory.readU64(person + layout.contract());
        long contractTeam = memory.readU64(main + layout.contractTeam());
        memory.put(main + layout.contractTeam(),0x700000,8);
        memory.put(0x700000 + layout.contractClub(),0,8);
        memory.put(person + layout.secondaryContracts(),0x710000,8);
        for(int i=0;i<4;i++) memory.put(0x710000 + i*8L,i==3 ? 0x720000 : 0,8);
        memory.put(0x720000 + layout.contractTeam(),contractTeam,8);
        memory.put(0x720000 + layout.primaryRole(),40,1);
        memory.put(person + layout.uniqueId(),123456,4);
        memory.put(staff + layout.sportsScience(),5,1);
        assertThat(new PlayerMedicalStaffReader().defaultStaff(memory,memory.player,memory.module,DATE).uniqueId())
                .isEqualTo(123456);
    }

    @Test
    void rejectsEveryShiftedLayoutMemberBeforeReplayingTheSameCapture() throws Exception {
        var memory = captured();
        var layout = PlayerMedicalStaffLayout.current();
        for (RecordComponent member : layout.getClass().getRecordComponents()) {
            Object value = member.getAccessor().invoke(layout);
            Object shifted;
            if (value instanceof Integer number) shifted = Integer.valueOf(number + 1);
            else shifted = Long.valueOf(((Long)value) + 1);
            var altered = replace(layout,member.getName(),shifted);
            assertThatThrownBy(() -> new PlayerMedicalStaffReader(altered)
                    .defaultStaff(memory,memory.player,memory.module,DATE))
                    .as(member.getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
        }
    }

    private static PlayerMedicalStaffLayout replace(PlayerMedicalStaffLayout record,String name,Object value) throws Exception {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i=0;i<components.length;i++) {
            types[i] = components[i].getType();
            args[i] = components[i].getName().equals(name) ? value : components[i].getAccessor().invoke(record);
        }
        return record.getClass().getDeclaredConstructor(types).newInstance(args);
    }

    private static CapturedMemory captured() throws IOException {
        byte[] bytes;
        try (var source=PlayerMedicalStaffReaderTest.class.getResourceAsStream("/fm27/player-medical-staff-captured.json")) {
            bytes = source.readNBytes(1024*1024+1);
        }
        if (bytes.length>1024*1024) throw new IOException("Staff fixture too large");
        var root = JsonMapper.builder().build().readTree(bytes);
        var memory = new CapturedMemory(root.path("player").asLong(),root.path("module").asLong());
        root.path("memory").properties().forEach(entry -> {
            long address = Long.parseLong(entry.getKey().split(":")[0]);
            byte[] raw = HexFormat.of().parseHex(entry.getValue().asString());
            for (int i=0;i<raw.length;i++) memory.bytes.put(address+i,raw[i]);
        });
        return memory;
    }
    private static final class CapturedMemory implements ProcessMemoryReader {
        private final Map<Long,Byte> bytes = new HashMap<>();
        private final long player,module;
        CapturedMemory(long player,long module) { this.player=player;this.module=module; }
        void put(long address,long value,int count) { for(int i=0;i<count;i++) bytes.put(address+i,(byte)(value >>> (8*i))); }
        public int pid() { return 22232; }
        public Platform platform() { return Platform.WINDOWS; }
        public List<MemoryRegion> maps() { return List.of(); }
        public void close() {}
        public byte[] readBytes(long address,int count) throws IOException {
            byte[] result = new byte[count];
            for(int i=0;i<count;i++) {
                Byte value = bytes.get(address+i);
                if(value==null) throw new IOException("Uncaptured staff memory at " + Long.toHexString(address+i));
                result[i]=value;
            }
            return result;
        }
    }
}
