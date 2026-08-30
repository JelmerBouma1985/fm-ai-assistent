package com.github.fmaiassistent.service;

import com.github.fmaiassistent.config.JCacheConfiguration;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.CompetitionExporter;
import com.github.fmaiassistent.exporter.PeopleExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.exporter.StaffExporter;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.ProcessInfo;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.repository.DatabaseService;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class DatabaseLoadAllService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseLoadAllService.class);
    private final ClubDatabaseService clubs;
    private final CompetitionDatabaseService competitions;
    private final PeopleExporter peopleExporter;
    private final DatabaseService databaseService;
    private final SnapshotDatabaseWriter snapshotWriter;
    private final ManagedClubContextService managedClubContexts;
    private final LoadMetadataRepository metadata;

    public DatabaseLoadAllService(
            ClubDatabaseService clubs,
            CompetitionDatabaseService competitions,
            PeopleExporter peopleExporter,
            DatabaseService databaseService,
            SnapshotDatabaseWriter snapshotWriter,
            ManagedClubContextService managedClubContexts,
            LoadMetadataRepository metadata) {
        this.clubs = clubs;
        this.competitions = competitions;
        this.peopleExporter = peopleExporter;
        this.databaseService = databaseService;
        this.snapshotWriter = snapshotWriter;
        this.managedClubContexts = managedClubContexts;
        this.metadata = metadata;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_WITH_CLUBS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.STAFF_WITH_CLUBS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.NATIONS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.COMPETITIONS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.CLUB_NAMES_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.CLUB_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYER_MAPPING_CACHE, allEntries = true)
    })
    @Transactional(rollbackFor = Exception.class)
    public LoadAllResult loadAll(Integer pid, int build, Long gamePluginBase) throws IOException {
        ManagedClubContext previousContext = managedClubContexts.current();
        try {
            managedClubContexts.markUnavailable("Managed club detection is waiting for the current RAM load");
            int resolvedPid = pid == null ? detectFmPid() : pid;
            RamSnapshot ram = readRamInParallel(resolvedPid, build, gamePluginBase);
            long persistenceStarted = System.nanoTime();
            logAfterCommit(persistenceStarted);
            long stepStarted = System.nanoTime();
            databaseService.clearAllTables();
            log.info("FM26 database clear completed in {} ms", elapsedMillis(stepStarted));
            stepStarted = System.nanoTime();
            Map<Long, Long> competitionIds = snapshotWriter.saveCompetitions(ram.competitions());
            log.info("FM26 competition persistence completed in {} ms", elapsedMillis(stepStarted));
            stepStarted = System.nanoTime();
            Map<Long, Long> clubIds = snapshotWriter.saveClubs(ram.clubs(), competitionIds);
            log.info("FM26 club persistence completed in {} ms", elapsedMillis(stepStarted));
            stepStarted = System.nanoTime();
            snapshotWriter.savePlayers(ram.players(), clubIds);
            log.info("FM26 player persistence completed in {} ms", elapsedMillis(stepStarted));
            stepStarted = System.nanoTime();
            snapshotWriter.saveStaff(ram.staff(), clubIds);
            log.info("FM26 staff persistence completed in {} ms", elapsedMillis(stepStarted));
            stepStarted = System.nanoTime();
            try {
                managedClubContexts.refresh(resolvedPid, build, gamePluginBase);
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? "The managed club could not be detected from FM26 RAM"
                        : exception.getMessage();
                managedClubContexts.markUnavailable(message);
                log.warn("FM26 data loaded, but the current managed club could not be detected: {}", message);
            }
            log.info("FM26 managed-club finalization completed in {} ms", elapsedMillis(stepStarted));
            stepStarted = System.nanoTime();
            String snapshotId = UUID.randomUUID().toString();
            long playerCount = ram.players().rows().size();
            long staffCount = ram.staff().rows().size();
            long clubCount = ram.clubs().rows().size();
            long competitionCount = ram.competitions().rows().size();
            String loadedAt = OffsetDateTime.now().toString();
            List<LoadMetadataEntity> snapshotMetadata = new java.util.ArrayList<>(List.of(
                    new LoadMetadataEntity("snapshot_id", snapshotId),
                    new LoadMetadataEntity("fm_pid", String.valueOf(resolvedPid)),
                    new LoadMetadataEntity("fm_build", String.valueOf(build)),
                    new LoadMetadataEntity("players_count", String.valueOf(playerCount)),
                    new LoadMetadataEntity("staff_count", String.valueOf(staffCount)),
                    new LoadMetadataEntity("clubs_count", String.valueOf(clubCount)),
                    new LoadMetadataEntity("competitions_count", String.valueOf(competitionCount)),
                    new LoadMetadataEntity("game_date", ram.players().gameDate()),
                    new LoadMetadataEntity("loaded_at", loadedAt),
                    new LoadMetadataEntity("clubs_loaded_at", loadedAt),
                    new LoadMetadataEntity("competitions_loaded_at", loadedAt)));
            ManagedClubContext managedClub = managedClubContexts.current();
            if (managedClub.managerUniqueId() != null) {
                snapshotMetadata.add(new LoadMetadataEntity(
                        "manager_unique_id", String.valueOf(managedClub.managerUniqueId())));
            }
            if (managedClub.careerKey() != null) {
                snapshotMetadata.add(new LoadMetadataEntity("career_key", managedClub.careerKey()));
            }
            metadata.saveAll(snapshotMetadata);
            log.info("FM26 snapshot metadata persistence completed in {} ms", elapsedMillis(stepStarted));
            log.info("FM26 database replacement and finalization prepared for commit in {} ms",
                    elapsedMillis(persistenceStarted));
            return new LoadAllResult(
                    resolvedPid,
                    ram.players().gameDate(),
                    playerCount,
                    staffCount,
                    clubCount,
                    competitionCount,
                    snapshotId);
        } catch (IOException | RuntimeException exception) {
            managedClubContexts.restore(previousContext);
            throw exception;
        }
    }

    public int detectFmPid() throws IOException {
        return ProcessReaders.findProcesses("fm.exe").stream()
                .max(Comparator.comparingInt(DatabaseLoadAllService::processScore))
                .filter(process -> processScore(process) > 0)
                .map(ProcessInfo::pid)
                .orElseThrow(() -> new IllegalStateException("fm.exe process not found"));
    }

    private RamSnapshot readRamInParallel(int pid, int build, Long gamePluginBase) throws IOException {
        long started = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(
                3, Thread.ofPlatform().name("fm-ram-loader-", 0).factory());
        Future<PeopleExporter.ExportResult> peopleFuture = executor.submit(
                () -> timedRamRead("people", () -> peopleExporter.exportAllPeople(pid, build, gamePluginBase)));
        Future<ClubExporter.ExportResult> clubFuture = executor.submit(
                () -> timedRamRead("clubs", () -> clubs.exportAllClubs(pid, build, gamePluginBase)));
        Future<CompetitionExporter.ExportResult> competitionFuture = executor.submit(
                () -> timedRamRead("competitions", () -> competitions.exportAllCompetitions(pid, build, gamePluginBase)));
        List<Future<?>> futures = List.of(peopleFuture, clubFuture, competitionFuture);
        try {
            PeopleExporter.ExportResult people = await(peopleFuture, "people");
            RamSnapshot snapshot = new RamSnapshot(
                    people.players(),
                    people.staff(),
                    await(clubFuture, "clubs"),
                    await(competitionFuture, "competitions"));
            log.info("Parallel FM26 RAM extraction completed in {} ms", elapsedMillis(started));
            return snapshot;
        } catch (IOException | RuntimeException exception) {
            futures.forEach(future -> future.cancel(true));
            throw exception;
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T timedRamRead(String dataType, IoSupplier<T> reader) throws IOException {
        long started = System.nanoTime();
        try {
            T result = reader.get();
            log.info("FM26 {} RAM extraction completed in {} ms", dataType, elapsedMillis(started));
            return result;
        } catch (IOException | RuntimeException exception) {
            log.warn("FM26 {} RAM extraction failed after {} ms: {}",
                    dataType, elapsedMillis(started), exception.getMessage());
            throw exception;
        }
    }

    private static <T> T await(Future<T> future, String dataType) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading FM26 " + dataType + " data", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Could not read FM26 " + dataType + " data", cause);
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void logAfterCommit(long started) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("FM26 database replacement and finalization committed in {} ms", elapsedMillis(started));
            }
        });
    }

    private static int processScore(ProcessInfo process) {
        String name = process.name().toLowerCase();
        String cmdline = process.cmdline().toLowerCase();
        int score = 0;
        if ("fm.exe".equals(name)) {
            score += 100;
        }
        if (cmdline.contains("football manager 26")) {
            score += 50;
        }
        if (cmdline.endsWith("fm.exe") || cmdline.endsWith("fm.exe\"")) {
            score += 25;
        }
        if (cmdline.contains("proton") || cmdline.contains("steamlaunch") || cmdline.contains("reaper")
                || cmdline.contains("bwrap") || cmdline.contains("steam.exe")) {
            score -= 100;
        }
        return score;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private record RamSnapshot(
            PlayerExporter.ExportResult players,
            StaffExporter.ExportResult staff,
            ClubExporter.ExportResult clubs,
            CompetitionExporter.ExportResult competitions) {
    }

    public record LoadAllResult(
            int pid,
            String gameDate,
            long players,
            long staff,
            long clubs,
            long competitions,
            String snapshotId) {
        public static int defaultBuild() {
            return FmOffsets.DEFAULT_BUILD;
        }
    }
}
