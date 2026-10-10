package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.PlayerRecordLayout;
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
import static org.assertj.core.api.Assertions.assertThatNoException;
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
        assertThat(new PlayerExporter().playerMemoryLayout(memory, PERSON, type)).isEmpty();
    }

    @Test
    void selectsPrimaryLayoutForPurePlayer() throws Exception {
        FakeMemory memory = memoryWithType(PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET);
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL, 173);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL, 180);
        putPlausiblePlayerBlock(memory, 0, true);

        PersonMemoryClassifier.PersonType type = new PersonMemoryClassifier(memory).classify(PERSON).type();
        PlayerExporter.PlayerMemoryLayout layout = new PlayerExporter().playerMemoryLayout(memory, PERSON, type).orElseThrow();

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
        PlayerExporter.PlayerMemoryLayout layout = new PlayerExporter().playerMemoryLayout(memory, PERSON, type).orElseThrow();

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

        assertThat(new PlayerExporter().playerMemoryLayout(memory, PERSON, type)).isEmpty();
    }

    @Test
    void reportsNoDutyWithoutInternationalContainer() throws Exception {
        FakeMemory memory = minimalPlayerRow();
        memory.putLong(PERSON - 0x168, 0);

        Map<String, Object> row = new PlayerExporter().decodeRow(
                memory, 1, PERSON, "", java.time.LocalDate.of(2026, 1, 17));

        assertThat(row.get("injured")).isEqualTo(false);
        assertThat(row.get("on_duty")).isEqualTo(false);
        assertThat(row.get("duty_start_date")).isEqualTo("");
        assertThat(row.get("duty_end_date")).isEqualTo("");
    }

    @Test
    void detectsInjuryRegardlessOfPointerRegion() throws Exception {
        // Regression test: the flag slot overlaps the high bytes of the
        // 64-bit reference, so a reference in the 0x2... heap region reads
        // flag 2 (and 0x1... reads 1). Both must count as injured.
        for (long reference : new long[]{0x19000, 0x29000}) {
            FakeMemory memory = minimalPlayerRow();
            putInjury(memory, reference, 43, 2026, 3, 2);

            Map<String, Object> row = new PlayerExporter().decodeRow(
                    memory, 1, PERSON, "", java.time.LocalDate.of(2026, 2, 13));
            PlayerExporter.applyGameDate(
                    new java.util.ArrayList<>(List.of(row)), java.time.LocalDate.of(2026, 2, 13));

            assertThat(row.get("injured")).isEqualTo(true);
            assertThat(row.get("injury")).isEqualTo("Sprained ankle");
            assertThat(row.get("injury_start_date")).isEqualTo("2026-02-12");
            assertThat(row.get("injury_light_training_days_remaining")).isEqualTo(1);
            assertThat(row.get("injury_full_training_days_remaining")).isEqualTo(2);
            assertThat(row.get("injury_expected_return")).isEqualTo("2 days");
        }
    }

    private static void putInjury(
            FakeMemory memory, long reference, int day, int year, int fullDays, int lightDays) {
        long vector = 0x9100;
        long item = 0x9200;
        long type = 0x9300;
        long text = 0x9400;
        memory.putLong(PERSON - 0x190, reference);
        memory.putLong(reference, vector);
        memory.putLong(vector, item);
        memory.putLong(item + 0x08, type);
        memory.putLong(type + 0x20, text);
        String description = "sprained ankle";
        memory.putI32(text, description.length());
        for (int index = 0; index < description.length(); index++) {
            memory.putU8(text + 4 + index, description.charAt(index));
        }
        memory.putI16(item + 0x20, day);
        memory.putI16(item + 0x22, year);
        memory.putI16(item + 0x28, fullDays);
        memory.putI16(item + 0x2A, lightDays);
    }

    @Test
    void reportsDutyForCurrentCallupWindow() throws Exception {
        FakeMemory memory = minimalPlayerRow();
        putDutyContainer(memory, 31, 2026, new DutyRecord(13, 2026, 17, 2026));

        Map<String, Object> row = new PlayerExporter().decodeRow(
                memory, 1, PERSON, "", java.time.LocalDate.of(2026, 1, 17));

        assertThat(row.get("on_duty")).isEqualTo(true);
        assertThat(row.get("duty_start_date")).isEqualTo("2026-01-13");
        assertThat(row.get("duty_end_date")).isEqualTo("2026-01-31");
    }

    @Test
    void remainsOnDutyUntilInternationalTeamReturnDate() throws Exception {
        FakeMemory memory = minimalPlayerRow();
        putDutyContainer(memory, 31, 2026, new DutyRecord(13, 2026, 14, 2026));

        Map<String, Object> row = new PlayerExporter().decodeRow(
                memory, 1, PERSON, "", java.time.LocalDate.of(2026, 1, 25));

        assertThat(row.get("on_duty")).isEqualTo(true);
        assertThat(row.get("duty_end_date")).isEqualTo("2026-01-31");
    }

    @Test
    void ignoresExpiredFutureAndInvalidDutyRecords() throws Exception {
        FakeMemory memory = minimalPlayerRow();
        // Expired window, future window and a record with an invalid year.
        putDutyContainer(memory, 10, 2026,
                new DutyRecord(17, 2025, 21, 2025),
                new DutyRecord(20, 2026, 25, 2026),
                new DutyRecord(13, 0, 14, 0));

        Map<String, Object> row = new PlayerExporter().decodeRow(
                memory, 1, PERSON, "", java.time.LocalDate.of(2026, 1, 17));

        assertThat(row.get("on_duty")).isEqualTo(false);
        assertThat(row.get("duty_start_date")).isEqualTo("");
        assertThat(row.get("duty_end_date")).isEqualTo("");
    }

    private record DutyRecord(int startDay, int startYear, int endDay, int endYear) {
    }

    @Test
    void shiftedRecordOffsetFailsValidationWhileCurrentPasses() throws Exception {
        FakeMemory memory = minimalPlayerRow();
        memory.putI32(PERSON + 0x0C, 12345);
        memory.putU8(PERSON - 0x5A, 185);
        java.time.LocalDate gameDate = java.time.LocalDate.of(2026, 2, 13);

        Map<String, Object> row = new PlayerExporter().decodeRow(memory, 1, PERSON, "", gameDate);
        assertThatNoException().isThrownBy(
                () -> PlayerSnapshotValidator.validate(List.of(row), 1, "2026-02-13"));

        PlayerRecordLayout base = PlayerRecordLayout.current();
        PlayerRecordLayout.Direct direct = base.direct();
        PlayerRecordLayout drifted = new PlayerRecordLayout(
                new PlayerRecordLayout.Direct(
                        direct.uniqueIdRel(),
                        direct.dateOfBirthRel(),
                        direct.genderRel(),
                        direct.heightCmRel() + 8,
                        direct.joinedClubDateRel(),
                        direct.registrationRefRel(),
                        direct.playingClubRefRel(),
                        direct.playingClubBodyRel(),
                        direct.injuryReferenceRel(),
                        direct.dutyReferenceRel(),
                        direct.historyCopySourceRel(),
                        direct.homeReputationRel(),
                        direct.currentReputationRel(),
                        direct.worldReputationRel(),
                        direct.currentAbilityRel(),
                        direct.potentialAbilityRel(),
                        direct.displayValueRel()),
                base.registration(),
                base.duty(),
                base.injury());
        Map<String, Object> driftedRow = new PlayerExporter(drifted).decodeRow(memory, 1, PERSON, "", gameDate);
        assertThatThrownBy(() -> PlayerSnapshotValidator.validate(List.of(driftedRow), 1, "2026-02-13"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("height_cm=0");
    }

    private static FakeMemory minimalPlayerRow() {
        FakeMemory memory = memoryWithType(PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET);
        memory.fill(PERSON - 0x1A0, 0x1A0, 0);
        memory.fill(PERSON + Long.BYTES, 0x90 - Long.BYTES, 0);
        memory.putI16(PERSON + AttributeDefinitions.CURRENT_ABILITY_REL, 120);
        memory.putI16(PERSON + AttributeDefinitions.POTENTIAL_ABILITY_REL, 140);
        putPlausiblePlayerBlock(memory, 0, true);
        return memory;
    }

    private static void putDutyContainer(
            FakeMemory memory, int returnDay, int returnYear, DutyRecord... records) {
        long container = 0x8000;
        long vector = 0x8100;
        long team = 0x9000;
        memory.putLong(PERSON - 0x168, container);
        memory.putLong(PERSON - 0x160, team);
        memory.putLong(container + 0x50, vector);
        memory.putLong(container + 0x58, vector + (long) records.length * Long.BYTES);
        memory.putI16(team + 0xBC, returnDay);
        memory.putI16(team + 0xBE, returnYear);
        for (int index = 0; index < records.length; index++) {
            long item = 0x8200 + (long) index * 0x20;
            memory.putLong(vector + (long) index * Long.BYTES, item);
            memory.fill(item, 0x20, 0);
            DutyRecord record = records[index];
            // Live FM26 records start with a pointer to the international team,
            // not an ASCII type marker.
            memory.putLong(item, team);
            memory.putI16(item + 0x10, record.startDay());
            memory.putI16(item + 0x12, record.startYear());
            memory.putI16(item + 0x14, record.endDay());
            memory.putI16(item + 0x16, record.endYear());
            memory.putU8(item + 0x18, 1);
            memory.putU8(item + 0x1D, 3);
        }
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
