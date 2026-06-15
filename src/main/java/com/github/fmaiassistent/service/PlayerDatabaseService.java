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
        players.deleteAllInBatch();
        clubDatabaseService.loadAllClubs(pid, build, gamePluginBase);
        Map<Long, ClubEntity> clubsByAddress = clubsByAddress();
        PlayerExporter.ExportResult result = exporter.exportAllPlayers(pid, build, gamePluginBase);
        players.saveAll(result.rows().stream()
                .map(row -> playerEntity(row, clubsByAddress))
                .toList());
        metadata.save(new LoadMetadataEntity("game_date", result.gameDate()));
        metadata.save(new LoadMetadataEntity("loaded_at", OffsetDateTime.now().toString()));
        return new LoadResult(result.gameDate(), result.rows().size());
    }

    private static PlayerEntity playerEntity(Map<String, Object> row, Map<Long, ClubEntity> clubsByAddress) {
        if (isBlank(row.get("club")) && !isBlank(row.get("playing_club"))) {
            row.put("club", row.get("playing_club"));
        }
        if (!row.containsKey("_club_address") && row.containsKey("_playing_club_address")) {
            row.put("_club_address", row.get("_playing_club_address"));
        }
        PlayerEntity entity = PlayerEntity.fromExportRow(row);
        Object clubAddress = row.get("_club_address");
        if (clubAddress instanceof Number number) {
            entity.setClubEntity(clubsByAddress.get(number.longValue()));
        }
        Object playingClubAddress = row.get("_playing_club_address");
        if (playingClubAddress instanceof Number number) {
            entity.setPlayingClubEntity(clubsByAddress.get(number.longValue()));
        }
        if (entity.getClubEntity() == null && entity.getPlayingClubEntity() != null) {
            entity.setClubEntity(entity.getPlayingClubEntity());
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
        List<PlayerEntity> out = players.findAllWithClubs();
        stopWatch.stop();
        LOGGER.info("Time to get player entities: {}", stopWatch.getTotalTime(TimeUnit.MILLISECONDS));
        return out;
    }

    @Transactional(readOnly = true)
    public List<PlayerEntity> findPlayerEntities(PlayerFilterCriteria filter) {
        PlayerFilterCriteria safeFilter = filter == null ? PlayerFilterCriteria.empty() : filter;
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        List<PlayerEntity> out = players.findAllWithClubs()
                .stream()
                .filter(player -> matchesPlayerFilter(player, safeFilter))
                .toList();
        stopWatch.stop();
        LOGGER.info("Time to get filtered player entities: {}", stopWatch.getTotalTime(TimeUnit.MILLISECONDS));
        return out;
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

    private static boolean matchesPlayerFilter(PlayerEntity player, PlayerFilterCriteria filter) {
        return contains(player.getName(), filter.name())
                && equalsIgnoreCase(player.getGender(), filter.gender())
                && equalsIgnoreCase(Optional.ofNullable(player.getPlayingClubEntity()).map(ClubEntity::getCompetitionEntity).map(CompetitionEntity::getNation).orElse(null), filter.playingNation())
                && equalsIgnoreCase(Optional.ofNullable(player.getPlayingClubEntity()).map(ClubEntity::getCompetitionEntity).map(CompetitionEntity::getName).orElse(null), filter.playingCompetition())
                && (equalsIgnoreCase(player.getClub(), filter.club())
                || equalsIgnoreCase(player.getPlayingClub(), filter.club())
                || equalsIgnoreCase(Optional.ofNullable(player.getClubEntity()).map(ClubEntity::getName).orElse(null), filter.club())
                || equalsIgnoreCase(Optional.ofNullable(player.getPlayingClubEntity()).map(ClubEntity::getName).orElse(null), filter.club()))
                && inRange(player.getSalaryWeeklyRaw().longValue(), 0L, filter.salaryMax())
                && equalsIgnoreCase(player.getNationality(), filter.nationality())
                && inRange(asInt(player.getAge()), filter.ageMin(), filter.ageMax())
                && inRange(player.getHeightCm(), filter.heightMin(), filter.heightMax())
                && inRange(player.getCurrentReputation(), filter.currentReputationMin(), filter.currentReputationMax())
                && inRange(player.getHomeReputation(), filter.homeReputationMin(), filter.homeReputationMax())
                && inRange(player.getWorldReputation(), filter.worldReputationMin(), filter.worldReputationMax())
                && inRange(player.getCa(), filter.caMin(), filter.caMax())
                && inRange(player.getPa(), filter.paMin(), filter.paMax())
                && inRange(player.getAskingPrice(), filter.askingPriceMin(), filter.askingPriceMax())
                && dateInRange(player.getContractEndDate(), filter.contractEndDateFrom(), filter.contractEndDateTo())
                && minimumsMatch(player, filter.positionMinimums())
                && minimumsMatch(player, filter.attributeMinimums());
    }

    private static boolean contains(Object value, String term) {
        return term == null || term.isBlank()
                || String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT)
                .contains(term.toLowerCase(Locale.ROOT).trim());
    }

    private static boolean equalsIgnoreCase(Object value, String term) {
        return term == null || term.isBlank()
                || String.valueOf(value == null ? "" : value).equalsIgnoreCase(term.trim());
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static boolean minimumsMatch(PlayerEntity player, Map<String, Integer> minimums) {
        for (Map.Entry<String, Integer> minimum : minimums.entrySet()) {
            Integer value = asInt(player.getColumnValue(minimum.getKey()));
            if (value == null || value < minimum.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean inRange(Integer value, Integer min, Integer max) {
        if (min == null && max == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return (min == null || value >= min) && (max == null || value <= max);
    }

    private static boolean inRange(Long value, Long min, Long max) {
        if (min == null && max == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return (min == null || value >= min) && (max == null || value <= max);
    }

    private static boolean dateInRange(Object value, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return false;
        }
        try {
            LocalDate date = LocalDate.parse(String.valueOf(value));
            return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Integer asInt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }

    public record LoadResult(String gameDate, int count) {
    }
}
