package com.example.fmgenie26.db;

import com.example.fmgenie26.player.PlayerExporter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

    public record LoadResult(String gameDate, int count) {
    }
}
