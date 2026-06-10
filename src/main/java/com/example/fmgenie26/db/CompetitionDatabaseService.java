package com.example.fmgenie26.db;

import com.example.fmgenie26.competition.CompetitionExporter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CompetitionDatabaseService {
    private final CompetitionRepository competitions;
    private final LoadMetadataRepository metadata;
    private final CompetitionExporter exporter = new CompetitionExporter();

    public CompetitionDatabaseService(CompetitionRepository competitions, LoadMetadataRepository metadata) {
        this.competitions = competitions;
        this.metadata = metadata;
    }

    @Transactional
    public LoadResult loadAllCompetitions(int pid, int build, Long gamePluginBase) throws IOException {
        CompetitionExporter.ExportResult result = exporter.exportAllCompetitions(pid, build, gamePluginBase);
        competitions.deleteAllInBatch();
        competitions.saveAll(result.rows().stream().map(CompetitionEntity::fromExportRow).toList());
        metadata.save(new LoadMetadataEntity("competitions_loaded_at", OffsetDateTime.now().toString()));
        return new LoadResult(result.rows().size());
    }

    @Transactional(readOnly = true)
    public List<String> findNations() {
        return competitions.findDistinctNations();
    }

    @Transactional(readOnly = true)
    public long countCompetitions() {
        return competitions.count();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findCompetitions(String name, String nation, String gender, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return competitions.findAll(
                        competitionFilters(name, nation, gender),
                        PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.ASC, "name")))
                .stream()
                .map(CompetitionEntity::toApiMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllCompetitions() {
        return competitions.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(CompetitionEntity::toApiMap)
                .toList();
    }

    private static Specification<CompetitionEntity> competitionFilters(String name, String nation, String gender) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List.of((name == null ? "" : name).toLowerCase(Locale.ROOT).trim().split("\\s+")).stream()
                    .filter(term -> !term.isBlank())
                    .map(term -> cb.like(cb.lower(root.get("name")), "%" + term + "%"))
                    .forEach(predicates::add);
            if (nation != null && !nation.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("nation")), nation.toLowerCase(Locale.ROOT)));
            }
            if (gender != null && !gender.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("gender")), gender.toLowerCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public record LoadResult(int count) {
    }
}
