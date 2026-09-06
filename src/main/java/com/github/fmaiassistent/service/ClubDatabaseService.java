package com.github.fmaiassistent.service;

import com.github.fmaiassistent.repository.*;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
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
        competitionDatabaseService.loadAllCompetitions(pid, build, gamePluginBase);
        return saveAllClubs(exportAllClubs(pid, build, gamePluginBase));
    }

    public ClubExporter.ExportResult exportAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        return exporter.exportAllClubs(pid, build, gamePluginBase);
    }

    @Transactional
    public LoadResult saveAllClubs(ClubExporter.ExportResult result) {
        Map<Long, CompetitionEntity> competitionsByAddress = competitionsByAddress();
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
    public List<ClubEntity> findAllClubs() {
        return clubs.findAll();
    }

    @Transactional(readOnly = true)
    public List<ClubEntity> findClubEntities(ClubFilterCriteria filter) {
        ClubFilterCriteria safeFilter = filter == null ? ClubFilterCriteria.empty() : filter;
        return clubs.findAll(CatalogSpecifications.clubs(safeFilter));
    }

    @Transactional(readOnly = true)
    public Page<ClubEntity> findClubPage(ClubFilterCriteria filter, Pageable pageable) {
        return clubs.findAll(CatalogSpecifications.clubs(filter), pageable);
    }

    @Transactional(readOnly = true)
    public long countClubs(ClubFilterCriteria filter) {
        return clubs.count(CatalogSpecifications.clubs(filter));
    }

    public record LoadResult(int count) {
    }
}
