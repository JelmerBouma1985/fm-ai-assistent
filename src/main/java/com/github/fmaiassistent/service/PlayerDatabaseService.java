package com.github.fmaiassistent.service;

import com.github.fmaiassistent.repository.*;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.exporter.PlayerExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class PlayerDatabaseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerDatabaseService.class);

    private final PlayerRepository players;
    private final ClubRepository clubRepository;
    private final CompetitionRepository competitionRepository;
    private final ClubDatabaseService clubDatabaseService;
    private final LoadMetadataRepository metadata;
    private final PlayerExporter exporter = new PlayerExporter();

    public PlayerDatabaseService(
            PlayerRepository players,
            ClubRepository clubRepository,
            CompetitionRepository competitionRepository,
            ClubDatabaseService clubDatabaseService,
            LoadMetadataRepository metadata) {
        this.players = players;
        this.clubRepository = clubRepository;
        this.competitionRepository = competitionRepository;
        this.clubDatabaseService = clubDatabaseService;
        this.metadata = metadata;
    }

    @Transactional
    public LoadResult loadAllPlayers(int pid, int build, Long gamePluginBase) throws IOException {
        clubDatabaseService.loadAllClubs(pid, build, gamePluginBase);
        return saveAllPlayers(exportAllPlayers(pid, build, gamePluginBase));
    }

    public PlayerExporter.ExportResult exportAllPlayers(int pid, int build, Long gamePluginBase) throws IOException {
        return exporter.exportAllPlayers(pid, build, gamePluginBase);
    }

    @Transactional
    public LoadResult saveAllPlayers(PlayerExporter.ExportResult result) {
        Map<Long, ClubEntity> clubsByAddress = clubsByAddress();
        players.saveAll(result.rows().stream()
                .map(row -> playerEntity(row, clubsByAddress))
                .toList());
        metadata.save(new LoadMetadataEntity("game_date", result.gameDate()));
        metadata.save(new LoadMetadataEntity("loaded_at", OffsetDateTime.now().toString()));
        return new LoadResult(result.gameDate(), result.rows().size());
    }

    private static PlayerEntity playerEntity(Map<String, Object> row, Map<Long, ClubEntity> clubsByAddress) {
        PlayerEntity entity = PlayerEntity.fromExportRow(row);
        Object clubAddress = row.get("_club_address");
        if (clubAddress instanceof Number number) {
            entity.setClubEntity(clubsByAddress.get(number.longValue()));
        }
        Object playingClubAddress = row.get("_playing_club_address");
        if (playingClubAddress instanceof Number number) {
            entity.setPlayingClubEntity(clubsByAddress.get(number.longValue()));
        }
        return entity;
    }

    private Map<Long, ClubEntity> clubsByAddress() {
        Map<Long, ClubEntity> out = new HashMap<>();
        for (ClubEntity club : clubRepository.findAll()) {
            if (club.getSourceAddress() == null) {
                continue;
            }
            out.merge(club.getSourceAddress(), club, PlayerDatabaseService::higherReputation);
        }
        return out;
    }

    private static ClubEntity higherReputation(ClubEntity left, ClubEntity right) {
        int leftReputation = left.getReputation() == null ? 0 : left.getReputation();
        int rightReputation = right.getReputation() == null ? 0 : right.getReputation();
        return rightReputation > leftReputation ? right : left;
    }

    @Transactional(readOnly = true)
    public long countPlayers() {
        return players.count();
    }

    @Transactional(readOnly = true)
    public List<PlayerEntity> findAllPlayerEntities() {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        List<PlayerEntity> out = players.findAll(CatalogSpecifications.players(PlayerFilterCriteria.empty()));
        stopWatch.stop();
        LOGGER.debug("Loaded all player entities in {} ms", stopWatch.getTotalTime(TimeUnit.MILLISECONDS));
        return out;
    }

    @Transactional(readOnly = true)
    public List<PlayerEntity> findPlayerEntities(PlayerFilterCriteria filter) {
        PlayerFilterCriteria safeFilter = filter == null ? PlayerFilterCriteria.empty() : filter;
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        List<PlayerEntity> out = players.findAll(CatalogSpecifications.players(safeFilter));
        stopWatch.stop();
        LOGGER.debug("Loaded filtered player entities in {} ms", stopWatch.getTotalTime(TimeUnit.MILLISECONDS));
        return out;
    }

    @Transactional(readOnly = true)
    public Page<PlayerEntity> findPlayerPage(PlayerFilterCriteria filter, Pageable pageable) {
        return players.findAll(CatalogSpecifications.players(filter), pageable);
    }

    @Transactional(readOnly = true)
    public long countPlayers(PlayerFilterCriteria filter) {
        return players.count(CatalogSpecifications.players(filter));
    }

    @Transactional(readOnly = true)
    public List<String> findPlayingNations() {
        return competitionRepository.findDistinctNations();
    }

    @Transactional(readOnly = true)
    public List<String> findPlayingCompetitions() {
        return competitionRepository.findDistinctNameByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<String> findClubs() {
        return clubRepository.findDistinctNameByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> metadata() {
        Map<String, Object> out = new LinkedHashMap<>();
        metadata.findAll(Sort.by("key")).forEach(row -> out.put(row.getKey(), row.getValue()));
        out.put("count", countPlayers());
        return out;
    }

    public record LoadResult(String gameDate, int count) {
    }
}
