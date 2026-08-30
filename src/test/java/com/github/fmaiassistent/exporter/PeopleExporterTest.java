package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import com.github.fmaiassistent.staff.StaffAttributeDefinitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeopleExporterTest {
    private static final int SLOT_COUNT = PeopleExporter.CHUNK_SIZE * 2 + 16;
    private static final LocalDate GAME_DATE = LocalDate.of(2033, 6, 23);

    @ParameterizedTest
    @CsvSource({
            "1,1",
            "2,1",
            "4,2",
            "8,6",
            "16,12",
            "128,12"
    })
    void reservesTwoProcessorsAndCapsWorkers(int processors, int expectedWorkers) {
        assertThat(PeopleExporter.selectedWorkerCount(processors, 10_000)).isEqualTo(expectedWorkers);
    }

    @Test
    void smallTablesAlwaysUseOneWorker() {
        assertThat(PeopleExporter.selectedWorkerCount(128, 9_999)).isOne();
    }

    @Test
    void processesEveryIndexExactlyOnceAcrossChunkBoundaries() throws Exception {
        int slots = PeopleExporter.CHUNK_SIZE * 3 + 17;
        AtomicInteger next = new AtomicInteger();
        AtomicInteger[] visits = new AtomicInteger[slots];
        for (int index = 0; index < slots; index++) {
            visits[index] = new AtomicInteger();
        }
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 6; worker++) {
                futures.add(executor.submit(() -> {
                    try {
                        PeopleExporter.processChunks(slots, next, index -> visits[index].incrementAndGet());
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(visits).allSatisfy(counter -> assertThat(counter).hasValue(1));
    }

    @Test
    void oneAndMultipleWorkersProduceIdenticalCombinedRowsAndDiagnostics() throws Exception {
        MemoryImage image = new MemoryImage();
        byte[] pointers = new byte[SLOT_COUNT * Long.BYTES];

        long player = 0x10_0000;
        long hybrid = 0x20_0000;
        long staff = 0x30_0000;
        long manager = 0x40_0000;
        long duplicateStaff = 0x50_0000;
        long unknown = 0x60_0000;
        putPlayer(image, player, PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET, 101, "Zulu Player", 150, 170);
        putPlayer(image, hybrid, PersonMemoryClassifier.PLAYER_STAFF_DYNAMIC_OFFSET, 102, "Alpha Hybrid", 140, 160);
        putStaff(image, staff, PersonMemoryClassifier.STAFF_DYNAMIC_OFFSET, 201, "Zulu Coach", 145, 155);
        putStaff(image, manager, PersonMemoryClassifier.HUMAN_MANAGER_DYNAMIC_OFFSET, 202, "Beta Manager", 150, 160);
        putStaff(image, duplicateStaff, PersonMemoryClassifier.STAFF_DYNAMIC_OFFSET, 201, "Alpha Duplicate", 130, 140);
        putPlausibleFalsePlayerBlock(image, staff);
        putClassification(image, unknown, 0x777);

        putPointer(pointers, 4, player);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE - 1, hybrid);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE, staff);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE + 1, manager);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE * 2, duplicateStaff);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE * 2 + 1, unknown);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE * 2 + 2, ProcessMemoryReader.MAX_USER_ADDRESS + 1);
        putPointer(pointers, PeopleExporter.CHUNK_SIZE * 2 + 3, 0x70_0000);

        PeopleExporter exporter = new PeopleExporter(pid -> image.reader(), () -> 16);
        PeopleExporter.ScanResult single = exporter.scanPointerTable(
                1, pointers, PeopleExporter.ExportMode.COMBINED, 1);
        PeopleExporter.ScanResult parallel = exporter.scanPointerTable(
                1, pointers, PeopleExporter.ExportMode.COMBINED, 4);
        PeopleExporter.ScanResult playerOnly = exporter.scanPointerTable(
                1, pointers, PeopleExporter.ExportMode.PLAYERS, 4);
        PeopleExporter.ScanResult staffOnly = exporter.scanPointerTable(
                1, pointers, PeopleExporter.ExportMode.STAFF, 4);

        List<Map<String, Object>> singlePlayers = PeopleExporter.finishPlayers(single.playerRows(), GAME_DATE);
        List<Map<String, Object>> parallelPlayers = PeopleExporter.finishPlayers(parallel.playerRows(), GAME_DATE);
        List<Map<String, Object>> singleStaff = PeopleExporter.finishStaff(single.staffRows(), GAME_DATE);
        List<Map<String, Object>> parallelStaff = PeopleExporter.finishStaff(parallel.staffRows(), GAME_DATE);

        assertThat(parallelPlayers).isEqualTo(singlePlayers);
        assertThat(parallelStaff).isEqualTo(singleStaff);
        assertThat(PeopleExporter.finishPlayers(playerOnly.playerRows(), GAME_DATE)).isEqualTo(singlePlayers);
        assertThat(PeopleExporter.finishStaff(staffOnly.staffRows(), GAME_DATE)).isEqualTo(singleStaff);
        assertThat(parallel.diagnostics()).isEqualTo(single.diagnostics());
        assertThat(parallelPlayers).extracting(row -> row.get("unique_id")).containsExactly(102L, 101L);
        assertThat(parallelStaff).extracting(row -> row.get("unique_id")).containsExactly(202L, 201L);
        assertThat(parallelStaff).extracting(row -> row.get("name")).containsExactly("Beta Manager", "Zulu Coach");
        assertThat(parallel.diagnostics()).isEqualTo(new PeopleExporter.Diagnostics(1, 1, 2, 1, 1, 0, 1, 1));
        assertThat(parallelPlayers).allSatisfy(row -> {
            assertThat(row).containsEntry("age_as_of", "2033-06-23");
            assertThat(row).doesNotContainKey("_injury_status");
        });
        assertThat(parallelStaff).allSatisfy(row -> assertThat(row).containsEntry("age_as_of", "2033-06-23"));
    }

    @Test
    void readerCreationFailureAbortsTheScan() {
        AtomicInteger opens = new AtomicInteger();
        IOException failure = new IOException("reader unavailable");
        PeopleExporter exporter = new PeopleExporter(pid -> {
            if (opens.incrementAndGet() == 2) {
                throw failure;
            }
            return new MemoryImage().reader();
        }, () -> 4);

        assertThatThrownBy(() -> exporter.scanPointerTable(
                1, new byte[PeopleExporter.CHUNK_SIZE * 2 * Long.BYTES], PeopleExporter.ExportMode.COMBINED, 2))
                .isSameAs(failure);
    }

    @Test
    void interruptionStopsChunkProcessing() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> PeopleExporter.processChunks(1, new AtomicInteger(), index -> { }))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private static void putPlayer(
            MemoryImage image,
            long person,
            int dynamicOffset,
            long uniqueId,
            String name,
            int ca,
            int pa) {
        image.fill(person - 0x300, 0x700, 0);
        putClassification(image, person, dynamicOffset);
        int relativeShift = dynamicOffset == PersonMemoryClassifier.PLAYER_STAFF_DYNAMIC_OFFSET
                ? -PersonMemoryClassifier.PLAYER_STAFF_SHIFT : 0;
        image.putU32(person + 0x0c, uniqueId);
        image.putStringPointer(person + 0x40, person + 0x800, name);
        image.putDate(person + 0x88, 100, 2000);
        image.putU16(person + AttributeDefinitions.HOME_REPUTATION_REL + relativeShift, 4_000);
        image.putU16(person + AttributeDefinitions.CURRENT_REPUTATION_REL + relativeShift, 5_000);
        image.putU16(person + AttributeDefinitions.WORLD_REPUTATION_REL + relativeShift, 3_000);
        image.putI16(person + AttributeDefinitions.CURRENT_ABILITY_REL + relativeShift, ca);
        image.putI16(person + AttributeDefinitions.POTENTIAL_ABILITY_REL + relativeShift, pa);
        image.putU8(person - 0x5a + relativeShift, 182);
        long source = person + AttributeDefinitions.HISTORY_COPY_SOURCE_REL + relativeShift;
        for (FieldDef position : AttributeDefinitions.POSITION_FIELDS) {
            image.putU8(source + position.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 0);
        }
        FieldDef position = AttributeDefinitions.POSITION_FIELDS.get(0);
        image.putU8(source + position.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 20);
        for (FieldDef attribute : AttributeDefinitions.VISIBLE_FIELDS) {
            image.putU8(source + attribute.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 50);
        }
        for (FieldDef attribute : AttributeDefinitions.HIDDEN_DIRECT_FIELDS) {
            image.putU8(person + attribute.offset(), 10);
        }
    }

    private static void putStaff(
            MemoryImage image,
            long person,
            int dynamicOffset,
            long uniqueId,
            String name,
            int ca,
            int pa) {
        image.fill(person - 0x500, 0x800, 0);
        putClassification(image, person, dynamicOffset);
        long staffBase = person - dynamicOffset;
        image.fill(staffBase + StaffAttributeDefinitions.ATTRIBUTES_REL, 0x34, 50);
        image.putU32(person + 0x0c, uniqueId);
        image.putStringPointer(person + 0x40, person + 0x800, name);
        image.putDate(person + 0x88, 100, 1980);
        image.putU16(staffBase + 0xd4, 4_000);
        image.putU16(staffBase + 0xd6, 5_000);
        image.putU16(staffBase + 0xd8, 3_000);
        image.putU16(staffBase + 0xda, ca);
        image.putI16(staffBase + 0xdc, pa);
    }

    private static void putPlausibleFalsePlayerBlock(MemoryImage image, long person) {
        image.putI16(person + AttributeDefinitions.CURRENT_ABILITY_REL, 155);
        image.putI16(person + AttributeDefinitions.POTENTIAL_ABILITY_REL, 170);
        long source = person + AttributeDefinitions.HISTORY_COPY_SOURCE_REL;
        for (FieldDef position : AttributeDefinitions.POSITION_FIELDS) {
            image.putU8(source + position.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 0);
        }
        FieldDef position = AttributeDefinitions.POSITION_FIELDS.get(0);
        image.putU8(source + position.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 20);
        for (FieldDef attribute : AttributeDefinitions.VISIBLE_FIELDS) {
            image.putU8(source + attribute.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET, 50);
        }
    }

    private static void putClassification(MemoryImage image, long person, int dynamicOffset) {
        long vtable = person + 0x1_000;
        long metadata = person + 0x2_000;
        image.putLong(person, vtable);
        image.putLong(vtable - Long.BYTES, metadata);
        image.putI32(metadata + 4, dynamicOffset);
    }

    private static void putPointer(byte[] pointers, int index, long value) {
        ByteBuffer.wrap(pointers).order(ByteOrder.LITTLE_ENDIAN).putLong(index * Long.BYTES, value);
    }

    private static final class MemoryImage {
        private final Map<Long, Byte> bytes = new HashMap<>();

        void fill(long address, int length, int value) {
            for (int offset = 0; offset < length; offset++) {
                putU8(address + offset, value);
            }
        }

        void putLong(long address, long value) {
            put(address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        void putU32(long address, long value) {
            put(address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array());
        }

        void putI32(long address, int value) {
            put(address, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        }

        void putU16(long address, int value) {
            put(address, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
        }

        void putI16(long address, int value) {
            putU16(address, value);
        }

        void putU8(long address, int value) {
            bytes.put(address, (byte) value);
        }

        void putDate(long address, int day, int year) {
            putU16(address, day);
            putU16(address + Short.BYTES, year);
        }

        void putStringPointer(long pointerAddress, long stringAddress, String value) {
            putLong(pointerAddress, stringAddress);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            putU32(stringAddress, text.length);
            put(stringAddress + 4, text);
        }

        ProcessMemoryReader reader() {
            return new FakeMemoryReader(new ConcurrentHashMap<>(bytes));
        }

        private void put(long address, byte[] value) {
            for (int offset = 0; offset < value.length; offset++) {
                bytes.put(address + offset, value[offset]);
            }
        }
    }

    private record FakeMemoryReader(Map<Long, Byte> bytes) implements ProcessMemoryReader {
        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] out = new byte[size];
            for (int offset = 0; offset < size; offset++) {
                Byte value = bytes.get(address + offset);
                if (value == null) {
                    throw new IOException("unmapped 0x" + Long.toHexString(address + offset));
                }
                out[offset] = value;
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
