package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.GameDateFinder;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Reads FM's shared people pointer table once and decodes its player and staff
 * records in bounded, worker-local batches.
 */
@Component
public class PeopleExporter {
    static final int CHUNK_SIZE = 4_096;
    static final int SMALL_TABLE_SLOT_LIMIT = 10_000;
    static final int MAX_WORKERS = 12;
    static final long SCAN_TIMEOUT_SECONDS = 90;

    private static final Logger log = LoggerFactory.getLogger(PeopleExporter.class);
    private static final Comparator<Map<String, Object>> PLAYER_NAME_ORDER =
            Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase());
    private static final Comparator<Map<String, Object>> STAFF_NAME_ORDER =
            Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER);

    private final ReaderFactory readers;
    private final IntSupplier availableProcessors;

    public PeopleExporter() {
        this(ProcessReaders::open, () -> Runtime.getRuntime().availableProcessors());
    }

    PeopleExporter(ReaderFactory readers, IntSupplier availableProcessors) {
        this.readers = readers;
        this.availableProcessors = availableProcessors;
    }

    public ExportResult exportAllPeople(int pid, int build, Long gamePluginBase) throws IOException {
        return export(pid, build, gamePluginBase, ExportMode.COMBINED, null);
    }

    public PlayerExporter.ExportResult exportAllPlayers(int pid, int build, Long gamePluginBase) throws IOException {
        return export(pid, build, gamePluginBase, ExportMode.PLAYERS, null).players();
    }

    public StaffExporter.ExportResult exportAllStaff(int pid, int build, Long gamePluginBase) throws IOException {
        return export(pid, build, gamePluginBase, ExportMode.STAFF, null).staff();
    }

    ExportResult exportAllPeople(int pid, int build, Long gamePluginBase, int workers) throws IOException {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive");
        }
        return export(pid, build, gamePluginBase, ExportMode.COMBINED, workers);
    }

    private ExportResult export(
            int pid,
            int build,
            Long gamePluginBase,
            ExportMode mode,
            Integer forcedWorkers) throws IOException {
        long started = System.nanoTime();
        int processors = Math.max(1, availableProcessors.getAsInt());
        try (ProcessMemoryReader coordinator = readers.open(pid)) {
            FmOffsets.Bounds bounds = FmOffsets.peopleBounds(coordinator, build, gamePluginBase);
            PointerTable table = PointerTable.read(coordinator, bounds, "People");
            int slotCount = table.size();
            byte[] pointerTable = table.bytes();
            int workers = forcedWorkers == null
                    ? selectedWorkerCount(processors, slotCount)
                    : Math.min(forcedWorkers, Math.max(1, slotCount));
            ScanResult scan = scanPointerTable(pid, pointerTable, mode, workers);
            LocalDate gameDate = new GameDateFinder()
                    .find(coordinator, scan.playerRows().size(), build, gamePluginBase)
                    .orElse(null);
            List<Map<String, Object>> playerRows = finishPlayers(scan.playerRows(), gameDate);
            List<Map<String, Object>> staffRows = finishStaff(scan.staffRows(), gameDate);
            String gameDateValue = gameDate == null ? "" : gameDate.toString();
            long elapsedMillis = elapsedMillis(started);
            Diagnostics diagnostics = scan.diagnostics();
            log.info(
                    "FM26 people RAM extraction: processors={}, workers={}, slots={}, elapsedMs={}, "
                            + "players={}, staff={}, playerSubtype={}, playerStaffSubtype={}, staffSubtype={}, "
                            + "humanManagerSubtype={}, unknownSubtype={}, invalidBlocks={}, malformedPointers={}, "
                            + "unreadable={}, slowestWorkerMs={}",
                    processors,
                    workers,
                    slotCount,
                    elapsedMillis,
                    playerRows.size(),
                    staffRows.size(),
                    diagnostics.players(),
                    diagnostics.playerStaff(),
                    diagnostics.staff(),
                    diagnostics.humanManagers(),
                    diagnostics.unknown(),
                    diagnostics.invalidBlocks(),
                    diagnostics.malformedPointers(),
                    diagnostics.unreadable(),
                    scan.slowestWorkerMillis());
            return new ExportResult(
                    new PlayerExporter.ExportResult(gameDateValue, playerRows),
                    new StaffExporter.ExportResult(gameDateValue, staffRows),
                    diagnostics,
                    processors,
                    workers,
                    slotCount,
                    elapsedMillis,
                    scan.slowestWorkerMillis());
        }
    }

    ScanResult scanPointerTable(int pid, byte[] pointerTable, ExportMode mode, int workers) throws IOException {
        if (pointerTable.length % Long.BYTES != 0) {
            throw new IOException("People pointer table size is not aligned to 64-bit slots");
        }
        int slotCount = pointerTable.length / Long.BYTES;
        int actualWorkers = Math.min(Math.max(1, workers), Math.max(1, slotCount));
        AtomicInteger nextIndex = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                actualWorkers, Thread.ofPlatform().name("fm-people-reader-", 0).factory());
        List<Future<WorkerResult>> futures = new ArrayList<>(actualWorkers);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SCAN_TIMEOUT_SECONDS);
        try {
            for (int worker = 0; worker < actualWorkers; worker++) {
                futures.add(executor.submit(() -> scanWorker(pid, pointerTable, mode, nextIndex)));
            }
            List<WorkerResult> results = new ArrayList<>(actualWorkers);
            for (Future<WorkerResult> future : futures) {
                results.add(await(future, deadline));
            }
            return merge(results);
        } catch (IOException | RuntimeException exception) {
            futures.forEach(future -> future.cancel(true));
            throw exception;
        } finally {
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    private WorkerResult scanWorker(
            int pid,
            byte[] pointerTable,
            ExportMode mode,
            AtomicInteger nextIndex) throws Exception {
        long started = System.nanoTime();
        List<Map<String, Object>> players = new ArrayList<>();
        List<Map<String, Object>> staff = new ArrayList<>();
        MutableDiagnostics diagnostics = new MutableDiagnostics();
        try (ProcessMemoryReader reader = readers.open(pid)) {
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            StaffExporter staffDecoder = new StaffExporter();
            processChunks(pointerTable.length / Long.BYTES, nextIndex, index -> {
                long person = pointerAt(pointerTable, index);
                if (person == 0) {
                    return;
                }
                if (person < 0 || person > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    diagnostics.malformedPointers++;
                    return;
                }
                try {
                    PersonMemoryClassifier.Classification classification = classifier.classify(person);
                    diagnostics.classified(classification.type());
                    if (mode.includesPlayers() && classification.type().hasPlayerData()) {
                        var row = PlayerExporter.decodeClassifiedRow(reader, index, person, classification.type());
                        if (row.isPresent()) {
                            players.add(row.get());
                        } else {
                            diagnostics.invalidBlocks++;
                        }
                    }
                    if (mode.includesStaff() && classification.type().hasStandaloneStaffData()) {
                        Map<String, Object> row = staffDecoder.decodeRow(
                                reader, index, person, classification.dynamicOffset());
                        if (validStaffRow(row)) {
                            staff.add(row);
                        } else {
                            diagnostics.invalidBlocks++;
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    diagnostics.unreadable++;
                }
            });
        }
        return new WorkerResult(players, staff, diagnostics.freeze(), elapsedMillis(started));
    }

    static void processChunks(int slotCount, AtomicInteger nextIndex, IndexProcessor processor) throws Exception {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("People RAM extraction worker interrupted");
            }
            int start = nextIndex.getAndAdd(CHUNK_SIZE);
            if (start >= slotCount) {
                return;
            }
            int end = Math.min(slotCount, start + CHUNK_SIZE);
            for (int index = start; index < end; index++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("People RAM extraction worker interrupted");
                }
                processor.process(index);
            }
        }
    }

    static int selectedWorkerCount(int availableProcessors, long slotCount) {
        if (slotCount < SMALL_TABLE_SLOT_LIMIT) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_WORKERS, availableProcessors - 2));
    }

    private static ScanResult merge(List<WorkerResult> workers) {
        List<Map<String, Object>> players = new ArrayList<>();
        List<Map<String, Object>> staff = new ArrayList<>();
        Diagnostics diagnostics = Diagnostics.EMPTY;
        long slowestWorkerMillis = 0;
        for (WorkerResult worker : workers) {
            players.addAll(worker.playerRows());
            staff.addAll(worker.staffRows());
            diagnostics = diagnostics.plus(worker.diagnostics());
            slowestWorkerMillis = Math.max(slowestWorkerMillis, worker.elapsedMillis());
        }
        return new ScanResult(players, staff, diagnostics, slowestWorkerMillis);
    }

    static List<Map<String, Object>> finishPlayers(
            List<Map<String, Object>> rawRows,
            LocalDate gameDate) {
        List<Map<String, Object>> rows = new ArrayList<>(rawRows);
        rows.sort(Comparator.comparingInt(row -> ((Number) row.get("index")).intValue()));
        PlayerExporter.applyGameDate(rows, gameDate);
        rows.sort(PLAYER_NAME_ORDER);
        return rows;
    }

    static List<Map<String, Object>> finishStaff(
            List<Map<String, Object>> rawRows,
            LocalDate gameDate) {
        Map<Long, Map<String, Object>> byUniqueId = new HashMap<>();
        for (Map<String, Object> row : rawRows) {
            long uniqueId = ((Number) row.get("unique_id")).longValue();
            byUniqueId.merge(uniqueId, row, PeopleExporter::lowerStaffIndex);
        }
        List<Map<String, Object>> rows = new ArrayList<>(byUniqueId.values());
        rows.sort(Comparator.comparingInt(row -> ((Number) row.get("staff_index")).intValue()));
        StaffExporter.applyAges(rows, gameDate);
        rows.sort(STAFF_NAME_ORDER);
        return rows;
    }

    private static Map<String, Object> lowerStaffIndex(
            Map<String, Object> left,
            Map<String, Object> right) {
        int leftIndex = ((Number) left.get("staff_index")).intValue();
        int rightIndex = ((Number) right.get("staff_index")).intValue();
        return leftIndex <= rightIndex ? left : right;
    }

    private static boolean validStaffRow(Map<String, Object> row) {
        long uniqueId = ((Number) row.get("unique_id")).longValue();
        int ca = ((Number) row.get("ca")).intValue();
        int pa = ((Number) row.get("pa")).intValue();
        String name = String.valueOf(row.get("name"));
        return uniqueId > 0 && !name.isBlank()
                && StaffExporter.validAbility(ca) && StaffExporter.validAbility(pa);
    }

    private static long pointerAt(byte[] pointerTable, int index) {
        int offset = index * Long.BYTES;
        return (pointerTable[offset] & 0xffL)
                | (pointerTable[offset + 1] & 0xffL) << 8
                | (pointerTable[offset + 2] & 0xffL) << 16
                | (pointerTable[offset + 3] & 0xffL) << 24
                | (pointerTable[offset + 4] & 0xffL) << 32
                | (pointerTable[offset + 5] & 0xffL) << 40
                | (pointerTable[offset + 6] & 0xffL) << 48
                | (pointerTable[offset + 7] & 0xffL) << 56;
    }

    private static WorkerResult await(Future<WorkerResult> future, long deadline) throws IOException {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("People extraction deadline exceeded");
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading FM26 people data", exception);
        } catch (CancellationException exception) {
            throw new IOException("FM26 people extraction was cancelled", exception);
        } catch (TimeoutException exception) {
            throw new IOException("FM26 people extraction exceeded " + SCAN_TIMEOUT_SECONDS + " seconds", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof InterruptedException interrupted) {
                throw new IOException("FM26 people extraction worker was interrupted", interrupted);
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Could not read FM26 people data", cause);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("FM26 people extraction workers did not terminate within 5 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    enum ExportMode {
        PLAYERS,
        STAFF,
        COMBINED;

        boolean includesPlayers() {
            return this != STAFF;
        }

        boolean includesStaff() {
            return this != PLAYERS;
        }
    }

    @FunctionalInterface
    interface ReaderFactory {
        ProcessMemoryReader open(int pid) throws IOException;
    }

    @FunctionalInterface
    interface IndexProcessor {
        void process(int index) throws Exception;
    }

    public record ExportResult(
            PlayerExporter.ExportResult players,
            StaffExporter.ExportResult staff,
            Diagnostics diagnostics,
            int detectedProcessors,
            int selectedWorkers,
            int slotCount,
            long elapsedMillis,
            long slowestWorkerMillis) {
    }

    record ScanResult(
            List<Map<String, Object>> playerRows,
            List<Map<String, Object>> staffRows,
            Diagnostics diagnostics,
            long slowestWorkerMillis) {
    }

    private record WorkerResult(
            List<Map<String, Object>> playerRows,
            List<Map<String, Object>> staffRows,
            Diagnostics diagnostics,
            long elapsedMillis) {
    }

    public record Diagnostics(
            long players,
            long playerStaff,
            long staff,
            long humanManagers,
            long unknown,
            long invalidBlocks,
            long malformedPointers,
            long unreadable) {
        private static final Diagnostics EMPTY = new Diagnostics(0, 0, 0, 0, 0, 0, 0, 0);

        private Diagnostics plus(Diagnostics other) {
            return new Diagnostics(
                    players + other.players,
                    playerStaff + other.playerStaff,
                    staff + other.staff,
                    humanManagers + other.humanManagers,
                    unknown + other.unknown,
                    invalidBlocks + other.invalidBlocks,
                    malformedPointers + other.malformedPointers,
                    unreadable + other.unreadable);
        }
    }

    private static final class MutableDiagnostics {
        private long players;
        private long playerStaff;
        private long staff;
        private long humanManagers;
        private long unknown;
        private long invalidBlocks;
        private long malformedPointers;
        private long unreadable;

        private void classified(PersonMemoryClassifier.PersonType type) {
            switch (type) {
                case PLAYER -> players++;
                case PLAYER_STAFF -> playerStaff++;
                case STAFF -> staff++;
                case HUMAN_MANAGER -> humanManagers++;
                case UNKNOWN -> unknown++;
            }
        }

        private Diagnostics freeze() {
            return new Diagnostics(
                    players,
                    playerStaff,
                    staff,
                    humanManagers,
                    unknown,
                    invalidBlocks,
                    malformedPointers,
                    unreadable);
        }
    }
}
