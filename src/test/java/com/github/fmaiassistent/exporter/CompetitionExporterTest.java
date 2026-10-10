package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.CompetitionRecordLayout;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompetitionExporterTest {
    private static final long COMPETITION = 0x9000;
    private static final long NATION = 0x9100;

    @Test
    void decodesCompetitionRow() throws Exception {
        FakeMemory memory = validCompetitionMemory();

        Map<String, Object> row = new CompetitionExporter().decodeCompetition(memory, COMPETITION);

        assertThat(row).containsEntry("name", "Premier Division")
                .containsEntry("nation", "England")
                .containsEntry("reputation", 150)
                .containsEntry("gender", "male");
        assertThatNoException().isThrownBy(
                () -> PlayerSnapshotValidator.validateCompetitions(List.of(row), 10));
    }

    @Test
    void shiftedNationOffsetDropsRowAndFailsValidation() throws Exception {
        FakeMemory memory = validCompetitionMemory();
        CompetitionRecordLayout base = CompetitionRecordLayout.current();
        CompetitionRecordLayout drifted = new CompetitionRecordLayout(
                base.nameRel(),
                base.nationRel() + 8,
                base.nationNameRelA(),
                base.nationNameRelB(),
                base.genderFlagRel(),
                base.reputationRel());

        Map<String, Object> row = new CompetitionExporter(drifted).decodeCompetition(memory, COMPETITION);

        // The drifted decode is dropped by the same emptiness gate exportAllCompetitions applies,
        // leaving an empty competition table alongside decoded players.
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!row.isEmpty()) {
            rows.add(row);
        }
        assertThat(rows).isEmpty();
        assertThatThrownBy(() -> PlayerSnapshotValidator.validateCompetitions(rows, 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no competitions decoded while 10 players decoded");
    }

    private static FakeMemory validCompetitionMemory() {
        FakeMemory memory = new FakeMemory();
        memory.putStringPointer(COMPETITION + 0x40, 0x8000, "Premier Division");
        memory.putU16(COMPETITION + 0x188, 150);
        memory.putLong(COMPETITION + 0x60, NATION);
        memory.putStringPointer(NATION + 0x18, 0x8100, "England");
        memory.putU8(COMPETITION + 0xF9, 0);
        return memory;
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();

        void putLong(long address, long value) {
            put(address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        void putU32(long address, long value) {
            put(address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array());
        }

        void putU16(long address, int value) {
            put(address, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
        }

        void putU8(long address, int value) {
            bytes.put(address, (byte) value);
        }

        void putStringPointer(long pointerAddress, long stringAddress, String value) {
            putLong(pointerAddress, stringAddress);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            putU32(stringAddress, text.length);
            put(stringAddress + 4, text);
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
