package com.example.fmgenie26.db;

import com.example.fmgenie26.player.PlayerExporter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PlayerDatabaseService {
    private final PlayerRepository players;
    private final ClubRepository clubs;
    private final ClubDatabaseService clubDatabaseService;
    private final LoadMetadataRepository metadata;
    private final PlayerExporter exporter = new PlayerExporter();

    public PlayerDatabaseService(
            PlayerRepository players,
            ClubRepository clubs,
            ClubDatabaseService clubDatabaseService,
            LoadMetadataRepository metadata) {
        this.players = players;
        this.clubs = clubs;
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
        for (ClubEntity club : clubs.findAll()) {
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
    public List<Map<String, Object>> findPlayers(String name, String gender, String nationality, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return players.findAll(
                        playerFilters(name, gender, nationality),
                        PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.ASC, "name")))
                .stream()
                .map(PlayerEntity::toApiMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllPlayers() {
        return players.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(PlayerEntity::toApiMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findPlayers(PlayerFilterCriteria filter) {
        PlayerFilterCriteria safeFilter = filter == null ? PlayerFilterCriteria.empty() : filter;
        return players.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(PlayerEntity::toApiMap)
                .filter(row -> matchesPlayerFilter(row, safeFilter))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> findPlayingNations() {
        return players.findAll()
                .stream()
                .map(PlayerEntity::toApiMap)
                .map(row -> row.get("PLAYING_NATION"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> findPlayingCompetitions() {
        return players.findAll()
                .stream()
                .map(PlayerEntity::toApiMap)
                .map(row -> row.get("PLAYING_COMPETITION"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> metadata() {
        Map<String, Object> out = new LinkedHashMap<>();
        metadata.findAll(Sort.by("key")).forEach(row -> out.put(row.getKey(), row.getValue()));
        out.put("count", countPlayers());
        return out;
    }

    private static Specification<PlayerEntity> playerFilters(String name, String gender, String nationality) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List.of((name == null ? "" : name).toLowerCase(Locale.ROOT).trim().split("\\s+")).stream()
                    .filter(term -> !term.isBlank())
                    .map(term -> cb.like(cb.lower(root.get("name")), "%" + term + "%"))
                    .forEach(predicates::add);
            if (gender != null && !gender.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("gender")), gender.toLowerCase(Locale.ROOT)));
            }
            if (nationality != null && !nationality.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("nationality")), nationality.toLowerCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean matchesPlayerFilter(Map<String, Object> row, PlayerFilterCriteria filter) {
        return contains(row.get("NAME"), filter.name())
                && equalsIgnoreCase(row.get("GENDER"), filter.gender())
                && equalsIgnoreCase(row.get("PLAYING_NATION"), filter.playingNation())
                && equalsIgnoreCase(row.get("PLAYING_COMPETITION"), filter.playingCompetition())
                && contains(row.get("NATIONALITY"), filter.nationality())
                && inRange(asInt(row.get("AGE")), filter.ageMin(), filter.ageMax())
                && inRange(asInt(row.get("CURRENT_REPUTATION")), filter.currentReputationMin(), filter.currentReputationMax())
                && inRange(asInt(row.get("HOME_REPUTATION")), filter.homeReputationMin(), filter.homeReputationMax())
                && inRange(asInt(row.get("WORLD_REPUTATION")), filter.worldReputationMin(), filter.worldReputationMax())
                && inRange(asInt(row.get("CA")), filter.caMin(), filter.caMax())
                && inRange(asInt(row.get("PA")), filter.paMin(), filter.paMax())
                && inRange(asLong(row.get("ASKING_PRICE")), filter.askingPriceMin(), filter.askingPriceMax())
                && dateInRange(row.get("CONTRACT_END_DATE"), filter.contractEndDateFrom(), filter.contractEndDateTo())
                && minimumsMatch(row, filter.positionMinimums())
                && minimumsMatch(row, filter.attributeMinimums());
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

    private static boolean minimumsMatch(Map<String, Object> row, Map<String, Integer> minimums) {
        for (Map.Entry<String, Integer> minimum : minimums.entrySet()) {
            Integer value = asInt(row.get(minimum.getKey()));
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

    private static Long asLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.valueOf(String.valueOf(value));
    }

    public record LoadResult(String gameDate, int count) {
    }
}
