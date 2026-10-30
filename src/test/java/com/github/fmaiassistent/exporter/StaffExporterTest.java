package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.StaffRecordLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffExporterTest {
    @Test void fm27CapturedSeniorStaffRemainLoadableWhileImpossibleAgesStillFail() throws Exception {
        tools.jackson.databind.JsonNode capture;
        try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/test/resources/fm27/staff-ages-captured.json"))) {
            byte[] bytes=in.readNBytes(65537);assertThat(bytes.length).isLessThanOrEqualTo(65536);
            capture=tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);
        }
        assertThat(capture.get("extremes").size()).isEqualTo(2);
        for(var sample:capture.get("extremes")) {
            long person=Long.decode(sample.get("person").asString());var memory=new FakeMemory();
            memory.put(person+sample.get("windowStartRelative").asInt(),java.util.HexFormat.of().parseHex(sample.get("windowHex").asString()));
            var row=new StaffExporter().decodeRow(memory,sample.get("index").asInt(),person,0x100);
            StaffExporter.applyAges(List.of(row),LocalDate.parse(capture.get("gameDate").asString()));
            assertThat(row).containsEntry("unique_id",sample.get("uid").asLong())
                    .containsEntry("date_of_birth",sample.get("dob").asString()).containsEntry("age",sample.get("age").asInt());
            assertThatNoException().isThrownBy(()->PlayerSnapshotValidator.validateStaff(List.of(row),capture.get("gameDate").asString(),1));
            var changedAge=new HashMap<>(row);changedAge.put("age",121);
            assertThatThrownBy(()->PlayerSnapshotValidator.validateStaff(List.of(changedAge),capture.get("gameDate").asString(),1))
                    .isInstanceOf(IOException.class).hasMessageContaining("age=121");
            var changedDate=new HashMap<>(row);changedDate.put("date_of_birth","1901-01-01");changedDate.put("age","");
            assertThatThrownBy(()->PlayerSnapshotValidator.validateStaff(List.of(changedDate),capture.get("gameDate").asString(),1))
                    .isInstanceOf(IOException.class).hasMessageContaining("date_of_birth");
        }
    }
    @Test void fm27CapturedAccountHumanKeepsItsIdentityAndTreatsTheDefaultBirthDateAsUnknown() throws Exception {
        tools.jackson.databind.JsonNode capture;
        try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/test/resources/fm27/people-captured.json"))) {
            byte[] bytes=in.readNBytes(1_048_577);assertThat(bytes.length).isLessThanOrEqualTo(1_048_576);
            capture=tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);
        }
        var sample=capture.get("samples").get(".?AVHUMAN_NON_PLAYER@db@@").get(0);
        long person=Long.decode(sample.get("person").asString());
        byte[] raw=java.util.HexFormat.of().parseHex(sample.get("windowHex").asString());
        var memory=new FakeMemory();memory.put(person+sample.get("windowStartRelative").asInt(),raw);
        assertThat(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                .getInt(-sample.get("windowStartRelative").asInt()+0x88)).isEqualTo(0x076C0001);
        var row=new StaffExporter().decodeRow(memory,sample.get("index").asInt(),person,
                com.github.fmaiassistent.memory.PersonIdentityLayout.currentSubtypes().humanManager());
        StaffExporter.applyAges(List.of(row),LocalDate.of(2026,8,3));
        assertThat(row).containsEntry("unique_id",sample.get("uid").asLong()).containsEntry("ca",100)
                .containsEntry("pa",200).containsEntry("date_of_birth","").containsEntry("age","");
        assertThatNoException().isThrownBy(()->PlayerSnapshotValidator.validateStaff(List.of(row),"2026-08-03",1));
    }
    private static final long PERSON = 0x2000;
    private static final long BASE = PERSON - 0x100;
    private static final long CONTRACT = 0x3000;
    private static final long TEAM = 0x4000;
    private static final long CLUB = 0x5000;
    private static final long COMPETITION = 0x6000;
    private static final long NATION = 0x7000;

@Test void positiveScaledByteBoundariesUseNativeMinimumWithoutChangingDirectOrMissingValues()throws Exception {
        for(int raw:new int[]{0,1,2,3,99,100}) {
            var memory=validStaffMemory();
            for(var attribute:com.github.fmaiassistent.staff.StaffAttributeDefinitions.ALL)memory.putU8(BASE+0x10+attribute.offset(),raw);
            var row=new StaffExporter().decodeRow(memory,7,PERSON,0x100);
            for(var attribute:com.github.fmaiassistent.staff.StaffAttributeDefinitions.ALL) {
                int expected=attribute.storage()==com.github.fmaiassistent.staff.StaffAttributeDefinitions.Storage.DIRECT
                        ? Math.min(20,raw) : raw==0?0:Math.max(1,Math.min(20,Math.floorDiv(raw+2,5)));
                assertThat(row.get(attribute.key())).as(attribute.key()+" raw="+raw).isEqualTo(expected);
            }
        }
    }

    @Test
    void fm27BasicContractDoesNotReadAnEndDateBeyondItsCapturedAllocation() throws Exception {
        tools.jackson.databind.JsonNode captures;
        try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/test/resources/fm27/contracts-captured.json"))){
            byte[] raw=in.readNBytes(262145);assertThat(raw.length).isLessThanOrEqualTo(262144);
            captures=tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
        }
        var sample=captures.get(1);byte[] core=java.util.HexFormat.of().parseHex(sample.get("contracts").get(0).get("coreHex").asString());
        assertThat(core).hasSize(0x28);
        var memory=validStaffMemory();memory.put(CONTRACT,core);memory.putLong(CONTRACT+0x10,TEAM);
        var row=new StaffExporter(StaffRecordLayout.current(),sample.get("base").asLong()).decodeRow(memory,7,PERSON,0x100);
        assertThat(row.get("contract_end_date")).isEqualTo("");
    }

@Test
    void decodesEnglishStaffFieldsAndFivePointAttributes() throws Exception {
        FakeMemory memory = validStaffMemory();

        Map<String, Object> row = new StaffExporter().decodeRow(memory, 7, PERSON, 0x100);

        assertThat(row).containsEntry("unique_id", 123456L)
                .containsEntry("name", "Jane Coach")
                .containsEntry("nationality", "England")
                .containsEntry("club", "Test FC")
                .containsEntry("division", "Premier Division")
                .containsEntry("job", "Coach")
                .containsEntry("salary_weekly_raw", 12500L)
                .containsEntry("contract_end_date", "2034-07-19")
                .containsEntry("ca", 145)
                .containsEntry("pa", 160)
                .containsEntry("authority", 16)
                .containsEntry("attacking", 17)
                .containsEntry("goalkeeping", 15)
                .containsEntry("working_with_youngsters", 19)
                .containsEntry("judging_player_ability", 18);
    }

    @Test
    void exposesEnglishJobNamesAndSafeFallback() {
        assertThat(StaffExporter.jobName(64)).isEqualTo("Head of Youth Development");
        assertThat(StaffExporter.jobName(999)).isEqualTo("Staff Member");
    }

    @Test
    void shiftedAbilityOffsetFailsValidationWhileCurrentPasses() throws Exception {
        FakeMemory memory = validStaffMemory();

        Map<String, Object> row = new StaffExporter().decodeRow(memory, 7, PERSON, 0x100);
        assertThat(row.get("ca")).isEqualTo(145);
        assertThatNoException().isThrownBy(
                () -> PlayerSnapshotValidator.validateStaff(List.of(row), "2026-09-01", 1));

        StaffRecordLayout base = StaffRecordLayout.current();
        StaffExporter driftedDecoder = new StaffExporter(new StaffRecordLayout(
                base.uniqueIdRel(),
                base.genderRel(),
                base.dateOfBirthRel(),
                base.contractRefRel(),
                base.contractTeamRel(),
                base.contractClubRel(),
                base.contractJobIdRel(),
                base.contractSalaryWeeklyRel(),
                base.contractDateRel(),
                base.homeReputationRel(),
                base.currentReputationRel(),
                base.worldReputationRel(),
                base.caRel() - 2,
                base.paRel(),
                base.attributesRel(),
                base.divisionScanRelA(),
                base.divisionScanRelB()));
        Map<String, Object> driftedRow = driftedDecoder.decodeRow(memory, 7, PERSON, 0x100);
        assertThatThrownBy(() -> PlayerSnapshotValidator.validateStaff(List.of(driftedRow), "2026-09-01", 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ca=3500");
    }

    private static FakeMemory validStaffMemory() {
        FakeMemory memory = new FakeMemory();
        memory.fill(BASE + 0x10, 0x34, 0);
        memory.putU32(PERSON + 0x0c, 123456);
        memory.putU8(PERSON + 0x19, 0);
        memory.putStringPointer(PERSON + 0x40, 0x8000, "Jane Coach");
        memory.putLong(PERSON + 0x68, NATION);
        memory.putStringPointer(NATION + 0x18, 0x8100, "England");
        memory.putLong(PERSON + 0xa8, CONTRACT);
        memory.putLong(CONTRACT + 0x10, TEAM);
        memory.putLong(TEAM + 0x30, CLUB);
        memory.putStringPointer(CLUB + 0xc8, 0x8200, "Test FC");
        memory.putLong(TEAM + 0x50, COMPETITION);
        memory.putStringPointer(COMPETITION + 0x48, 0x8300, "Premier Division");
        memory.putU8(CONTRACT + 0x26, 2);
        memory.putU32(CONTRACT + 0x20, 12500);
        memory.putDate(PERSON + 0x88, 100, 1990);
        memory.putDate(CONTRACT + 0x48, 200, 2034);
        memory.putU16(BASE + 0xd4, 4000);
        memory.putU16(BASE + 0xd6, 5000);
        memory.putU16(BASE + 0xd8, 3500);
        memory.putU16(BASE + 0xda, 145);
        memory.putU16(BASE + 0xdc, 160);
        memory.putU8(BASE + 0x10 + 0x22, 85);
        memory.putU8(BASE + 0x10 + 0x1c, 90);
        memory.putU8(BASE + 0x10 + 0x04, 16);
        memory.putU8(BASE + 0x10 + 0x0c, 19);
        memory.putU8(BASE + 0x10 + 0x1b, 75);
        return memory;
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();
        private final java.util.List<Long> readAddresses=new java.util.ArrayList<>();
        void fill(long address, int length, int value) { for (int i = 0; i < length; i++) putU8(address + i, value); }
        void putLong(long address, long value) { put(address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        void putU32(long address, long value) { put(address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array()); }
        void putU16(long address, int value) { put(address, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array()); }
        void putU8(long address, int value) { bytes.put(address, (byte) value); }
        void putDate(long address, int day, int year) { putU16(address, day); putU16(address + 2, year); }
        void putStringPointer(long pointerAddress, long stringAddress, String value) {
            putLong(pointerAddress, stringAddress);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            putU32(stringAddress, text.length);
            put(stringAddress + 4, text);
        }
        private void put(long address, byte[] value) { for (int i = 0; i < value.length; i++) bytes.put(address + i, value[i]); }
        @Override public int pid() { return 1; }
        @Override public byte[] readBytes(long address, int size) throws IOException {
            readAddresses.add(address);
            byte[] out = new byte[size];
            for (int i = 0; i < size; i++) {
                Byte value = bytes.get(address + i);
                if (value == null) throw new IOException("unmapped 0x" + Long.toHexString(address + i));
                out[i] = value;
            }
            return out;
        }
        @Override public List<MemoryRegion> maps() { return List.of(); }
        @Override public void close() { }
    }
}
