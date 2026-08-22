package com.github.fmaiassistent.service;

import com.github.fmaiassistent.config.JCacheConfiguration;
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

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class DatabaseLoadAllService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseLoadAllService.class);
    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final CompetitionDatabaseService competitions;
    private final DatabaseService databaseService;
    private final ManagedClubContextService managedClubContexts;
    private final LoadMetadataRepository metadata;

    public DatabaseLoadAllService(
            PlayerDatabaseService players,
            ClubDatabaseService clubs,
            CompetitionDatabaseService competitions,
            DatabaseService databaseService,
            ManagedClubContextService managedClubContexts,
            LoadMetadataRepository metadata) {
        this.players = players;
        this.clubs = clubs;
        this.competitions = competitions;
        this.databaseService = databaseService;
        this.managedClubContexts = managedClubContexts;
        this.metadata = metadata;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_CACHE, allEntries = true),
            @CacheEvict(cacheNames = JCacheConfiguration.PLAYERS_WITH_CLUBS_CACHE, allEntries = true),
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
            databaseService.clearAllTables();
            PlayerDatabaseService.LoadResult playerResult = players.loadAllPlayers(resolvedPid, build, gamePluginBase);
            try {
                managedClubContexts.refresh(resolvedPid, build, gamePluginBase);
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? "The managed club could not be detected from FM26 RAM"
                        : exception.getMessage();
                managedClubContexts.markUnavailable(message);
                log.warn("FM26 data loaded, but the current managed club could not be detected: {}", message);
            }
            String snapshotId = UUID.randomUUID().toString();
            long clubCount = clubs.countClubs();
            long competitionCount = competitions.countCompetitions();
            List<LoadMetadataEntity> snapshotMetadata = new java.util.ArrayList<>(List.of(
                    new LoadMetadataEntity("snapshot_id", snapshotId),
                    new LoadMetadataEntity("fm_pid", String.valueOf(resolvedPid)),
                    new LoadMetadataEntity("fm_build", String.valueOf(build)),
                    new LoadMetadataEntity("players_count", String.valueOf(playerResult.count())),
                    new LoadMetadataEntity("clubs_count", String.valueOf(clubCount)),
                    new LoadMetadataEntity("competitions_count", String.valueOf(competitionCount))));
            ManagedClubContext managedClub = managedClubContexts.current();
            if (managedClub.managerUniqueId() != null) {
                snapshotMetadata.add(new LoadMetadataEntity(
                        "manager_unique_id", String.valueOf(managedClub.managerUniqueId())));
            }
            if (managedClub.careerKey() != null) {
                snapshotMetadata.add(new LoadMetadataEntity("career_key", managedClub.careerKey()));
            }
            metadata.saveAll(snapshotMetadata);
            return new LoadAllResult(
                    resolvedPid,
                    playerResult.gameDate(),
                    playerResult.count(),
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

    public record LoadAllResult(
            int pid,
            String gameDate,
            long players,
            long clubs,
            long competitions,
            String snapshotId) {
        public static int defaultBuild() {
            return FmOffsets.DEFAULT_BUILD;
        }
    }
}
