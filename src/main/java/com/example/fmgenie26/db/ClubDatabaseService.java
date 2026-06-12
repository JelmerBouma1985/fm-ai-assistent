package com.example.fmgenie26.db;

import com.example.fmgenie26.club.ClubExporter;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ClubDatabaseService {
    private final ClubRepository clubs;
    private final CompetitionRepository competitions;
    private final CompetitionDatabaseService competitionDatabaseService;
    private final LoadMetadataRepository metadata;
    private final ClubExporter exporter = new ClubExporter();

    public ClubDatabaseService(
            ClubRepository clubs,
            CompetitionRepository competitions,
            CompetitionDatabaseService competitionDatabaseService,
            LoadMetadataRepository metadata) {
        this.clubs = clubs;
        this.competitions = competitions;
        this.competitionDatabaseService = competitionDatabaseService;
        this.metadata = metadata;
    }

    @Transactional
    public LoadResult loadAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        clubs.deleteAllInBatch();
        competitionDatabaseService.loadAllCompetitions(pid, build, gamePluginBase);
        Map<Long, CompetitionEntity> competitionsByAddress = competitionsByAddress();
        ClubExporter.ExportResult result = exporter.exportAllClubs(pid, build, gamePluginBase);
        clubs.saveAll(result.rows().stream()
                .map(row -> clubEntity(row, competitionsByAddress))
                .toList());
        metadata.save(new LoadMetadataEntity("clubs_loaded_at", OffsetDateTime.now().toString()));
        return new LoadResult(result.rows().size());
    }

    private static ClubEntity clubEntity(Map<String, Object> row, Map<Long, CompetitionEntity> competitionsByAddress) {
        ClubEntity entity = ClubEntity.fromExportRow(row);
        Object address = row.get("_competition_address");
        if (address instanceof Number number) {
            entity.setCompetitionEntity(competitionsByAddress.get(number.longValue()));
        }
        return entity;
    }

    private Map<Long, CompetitionEntity> competitionsByAddress() {
        Map<Long, CompetitionEntity> out = new HashMap<>();
        for (CompetitionEntity competition : competitions.findAll()) {
            if (competition.getSourceAddress() != null) {
                out.put(competition.getSourceAddress(), competition);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public long countClubs() {
        return clubs.count();
    }

    @Transactional(readOnly = true)
    public List<ClubEntity> findClubs(String name, String gender, String nation, String competition, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return clubs.findAll(
                        clubFilters(name, gender, nation, competition),
                        PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.ASC, "name")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClubEntity> findAllClubs() {
        return clubs.findAll();
    }

    @Transactional(readOnly = true)
    public List<ClubEntity> findClubEntities(ClubFilterCriteria filter) {
        ClubFilterCriteria safeFilter = filter == null ? ClubFilterCriteria.empty() : filter;
        if (safeFilter.isEmpty()) {
            return findAllClubs();
        }
        return clubs.findAll().stream()
                .filter(club -> matchesClubFilter(club, safeFilter))
                .toList();
    }

    private static Specification<ClubEntity> clubFilters(String name, String gender, String nation, String competition) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List.of((name == null ? "" : name).toLowerCase(Locale.ROOT).trim().split("\\s+")).stream()
                    .filter(term -> !term.isBlank())
                    .map(term -> cb.like(cb.lower(root.get("name")), "%" + term + "%"))
                    .forEach(predicates::add);
            if (gender != null && !gender.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("gender")), gender.toLowerCase(Locale.ROOT)));
            }
            if (nation != null && !nation.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("nation")), nation.toLowerCase(Locale.ROOT)));
            }
            if (competition != null && !competition.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("competition")), competition.toLowerCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean matchesClubFilter(ClubEntity club, ClubFilterCriteria filter) {
        return equalsIgnoreCase(club.getName(), filter.name())
                && equalsIgnoreCase(club.getCompetition(), filter.competition())
                && equalsIgnoreCase(club.getNation(), filter.nation())
                && inRange(club.getReputation(), filter.reputationMin(), filter.reputationMax())
                && inRange(club.getBalance(), filter.balanceMin(), filter.balanceMax())
                && inRange(club.getTransferBudget(), filter.transferBudgetMin(), filter.transferBudgetMax())
                && inRange(club.getPayrollBudget(), filter.payrollBudgetMin(), filter.payrollBudgetMax());
    }

    private static boolean equalsIgnoreCase(Object value, String term) {
        return term == null || term.isBlank()
                || String.valueOf(value == null ? "" : value).equalsIgnoreCase(term.trim());
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

    public record LoadResult(int count) {
    }
}
