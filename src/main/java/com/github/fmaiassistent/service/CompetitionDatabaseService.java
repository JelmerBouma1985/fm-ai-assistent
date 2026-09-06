package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.repository.CompetitionRepository;
import com.github.fmaiassistent.repository.CatalogSpecifications;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.exporter.CompetitionExporter;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
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
        return saveAllCompetitions(exportAllCompetitions(pid, build, gamePluginBase));
    }

    public CompetitionExporter.ExportResult exportAllCompetitions(int pid, int build, Long gamePluginBase) throws IOException {
        return exporter.exportAllCompetitions(pid, build, gamePluginBase);
    }

    @Transactional
    public LoadResult saveAllCompetitions(CompetitionExporter.ExportResult result) {
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
        return competitions.findAll(
                        CatalogSpecifications.competitions(
                                new CompetitionFilterCriteria(name, nation, null, null, gender)),
                        PageRequest.of(0, safeLimit, Sort.by("name")))
                .getContent().stream()
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
        return competitions.findAll(CatalogSpecifications.competitions(safeFilter), Sort.by("name"));
    }

    @Transactional(readOnly = true)
    public Page<CompetitionEntity> findCompetitionPage(CompetitionFilterCriteria filter, Pageable pageable) {
        return competitions.findAll(CatalogSpecifications.competitions(filter), pageable);
    }

    @Transactional(readOnly = true)
    public long countCompetitions(CompetitionFilterCriteria filter) {
        return competitions.count(CatalogSpecifications.competitions(filter));
    }

    public record LoadResult(int count) {
    }
}
