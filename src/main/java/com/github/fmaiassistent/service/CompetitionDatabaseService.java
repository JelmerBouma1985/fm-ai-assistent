package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.repository.CompetitionRepository;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.exporter.CompetitionExporter;
import org.springframework.data.domain.Sort;
import com.github.fmaiassistent.repository.CompetitionFilterCriteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
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
    public List<String> findNames() {
        return competitions.findDistinctNameByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<String> findGenders() {
        return competitions.findDistinctGenders();
    }

    @Transactional(readOnly = true)
    public long countCompetitions() {
        return competitions.count();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findCompetitions(String name, String nation, String gender, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return findCompetitionEntities(new CompetitionFilterCriteria(name, nation, null, null, gender))
                .stream()
                .limit(safeLimit)
                .map(CompetitionEntity::toApiMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllCompetitions() {
        return findAllCompetitionEntities()
                .stream()
                .map(CompetitionEntity::toApiMap)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompetitionEntity> findAllCompetitionEntities() {
        return competitions.findAll(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Transactional(readOnly = true)
    public List<CompetitionEntity> findCompetitionEntities(CompetitionFilterCriteria filter) {
        CompetitionFilterCriteria safeFilter = filter == null ? CompetitionFilterCriteria.empty() : filter;
        if (safeFilter.isEmpty()) {
            return findAllCompetitionEntities();
        }
        return findAllCompetitionEntities().stream()
                .filter(competition -> matchesCompetitionFilter(competition, safeFilter))
                .toList();
    }

    private static boolean matchesCompetitionFilter(CompetitionEntity competition, CompetitionFilterCriteria filter) {
        return equalsIgnoreCase(competition.getName(), filter.name())
                && equalsIgnoreCase(competition.getNation(), filter.nation())
                && inRange(competition.getReputation(), filter.reputationMin(), filter.reputationMax())
                && equalsIgnoreCase(competition.getGender(), filter.gender());
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

    public record LoadResult(int count) {
    }
}
