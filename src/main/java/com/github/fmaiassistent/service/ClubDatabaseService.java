package com.github.fmaiassistent.service;

import com.github.fmaiassistent.repository.*;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.memory.ClubRecordLayout;
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
    private final SnapshotDatabaseWriter snapshotWriter;
    private final NativeClubRepository nativeClubs;
    private final CompetitionNameRepository competitionNames;
    private final ClubExporter exporter = new ClubExporter();

    public ClubDatabaseService(
            ClubRepository clubs,
            CompetitionRepository competitions,
            CompetitionDatabaseService competitionDatabaseService,
            LoadMetadataRepository metadata,SnapshotDatabaseWriter snapshotWriter,NativeClubRepository nativeClubs,
            CompetitionNameRepository competitionNames) {
        this.clubs = clubs;
        this.competitions = competitions;
        this.competitionDatabaseService = competitionDatabaseService;
        this.metadata = metadata;
        this.snapshotWriter=snapshotWriter;
        this.nativeClubs=nativeClubs;
        this.competitionNames=competitionNames;
    }

    @Transactional
    public LoadResult loadAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        competitionDatabaseService.loadAllCompetitions(pid, build, gamePluginBase);
        return saveAllClubs(exportAllClubs(pid, build, gamePluginBase));
    }

    public ClubExporter.ExportResult exportAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        return exporter.exportAllClubs(pid, build, gamePluginBase);
    }

    /**
     * Record layout for the next export. Set once per load; safe because only
     * one refresh runs at a time.
     */
    public void setRecordLayout(ClubRecordLayout recordLayout) {
        exporter.setRecordLayout(recordLayout);
    }

    @Transactional
    public LoadResult saveAllClubs(ClubExporter.ExportResult result) {
        snapshotWriter.saveNativeClubs(result);
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

    @Transactional(readOnly = true)
    public Page<CoefficientClub> findCoefficientClubs(Pageable pageable) {
        return findCoefficientClubs("",pageable);
    }

    @Transactional(readOnly = true)
    public Page<CoefficientClub> findCoefficientClubs(String name,Pageable pageable) {
        var hashes=List.of(com.github.fmaiassistent.memory.NativeModuleProfile.LEGACY_SHA256,
                com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256);
        var page=name==null||name.isBlank()?nativeClubs.findByUefaCoefficientHexIsNotNullAndModuleSha256In(hashes,pageable)
                :nativeClubs.findByUefaCoefficientHexIsNotNullAndModuleSha256InAndNameContainingIgnoreCase(hashes,name.trim(),pageable);
        return page.map(ClubDatabaseService::coefficientClub);
    }

    @Transactional(readOnly = true)
    public long countCoefficientClubs() {
        return countCoefficientClubs("");
    }

    @Transactional(readOnly = true)
    public long countCoefficientClubs(String name) {
        var hashes=List.of(com.github.fmaiassistent.memory.NativeModuleProfile.LEGACY_SHA256,
                com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256);
        return name==null||name.isBlank()?nativeClubs.countByUefaCoefficientHexIsNotNullAndModuleSha256In(hashes)
                :nativeClubs.countByUefaCoefficientHexIsNotNullAndModuleSha256InAndNameContainingIgnoreCase(hashes,name.trim());
    }

    @Transactional(readOnly = true)
    public java.util.Optional<CoefficientClub> findClubCoefficients(Long uniqueId) {
        if(uniqueId==null)return java.util.Optional.empty();
        return nativeClubs.findById(uniqueId).filter(c->com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.values(
                c.getUefaCoefficientHex(),c.getUefaRuleVersion(),c.getModuleSha256()).isPresent())
                .map(ClubDatabaseService::coefficientClub);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<com.github.fmaiassistent.memory.ClubFinancialLoanLayout.Profile> findClubFinancialLoans(Long uniqueId) {
        if(uniqueId==null)return java.util.Optional.empty();
        return nativeClubs.findById(uniqueId).flatMap(c->com.github.fmaiassistent.memory.ClubFinancialLoanLayout.values(
                c.getFinancialLoansHex(),c.getFinancialLoanClock(),c.getModuleSha256()));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.Profile> findClubFinancialLedger(Long uniqueId) {
        if(uniqueId==null)return java.util.Optional.empty();
        return nativeClubs.findById(uniqueId).flatMap(c->com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.values(
                c.getFinancialLedgerHex(),c.getFinancialLedgerClock(),c.getModuleSha256()));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.Profile> findClubFinancialCompetitions(Long uniqueId) {
        if(uniqueId==null)return java.util.Optional.empty();
        return nativeClubs.findById(uniqueId).flatMap(c->com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.values(
                c.getFinancialCompetitionsHex(),c.getFinancialLedgerClock(),c.getModuleSha256(),c.getFinancialLedgerHex()));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<com.github.fmaiassistent.memory.ClubFinancialHistoryLayout.Profile> findClubFinancialHistory(Long uniqueId) {
        if(uniqueId==null)return java.util.Optional.empty();
        return nativeClubs.findById(uniqueId).filter(c->com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.values(
                c.getFinancialLedgerHex(),c.getFinancialLedgerClock(),c.getModuleSha256()).isPresent())
                .flatMap(c->com.github.fmaiassistent.memory.ClubFinancialHistoryLayout.values(
                        c.getFinancialHistoryHex(),c.getFinancialLedgerClock(),c.getModuleSha256()));
    }

    /** Keep decoded amounts and UID labels in the same read transaction during snapshot replacement. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public java.util.Optional<CompetitionIncome> findClubCompetitionIncome(Long uniqueId) {
        if(uniqueId==null)return java.util.Optional.empty();
        return nativeClubs.findById(uniqueId).flatMap(club->com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.values(
                club.getFinancialCompetitionsHex(),club.getFinancialLedgerClock(),club.getModuleSha256(),club.getFinancialLedgerHex()).map(profile->{
            var ids=new java.util.HashSet<Long>();for(var period:profile.periods()){
                period.tvRevenue().forEach(m->ids.add(m.competitionUid()));period.prizeMoney().forEach(m->ids.add(m.competitionUid()));}
            var names=new HashMap<Long,String>();var layout=com.github.fmaiassistent.memory.CompetitionNameLayout.current();
            for(var row:competitionNames.findAllById(ids)) {
                if(!club.getModuleSha256().equals(row.getModuleSha256()))continue;
                try{
                    if(row.getPrefixHex()==null||row.getPrefixHex().length()!=32||row.getRecordIndex()==null)continue;
                    var prefix=java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(row.getPrefixHex())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    var identity=com.github.fmaiassistent.memory.NativeModuleProfile.fm27(club.getModuleSha256())
                            ?com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.fm27()
                            :com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.current();
                    if(prefix.getInt(identity.competitionIndexRel())!=row.getRecordIndex()
                            ||Integer.toUnsignedLong(prefix.getInt(identity.competitionUidRel()))!=row.getUniqueId())continue;
                    var decoded=layout.decode(row.getNameHex(),row.getShortNameHex());
                    if(java.util.Objects.equals(decoded.name(),row.getName())&&java.util.Objects.equals(decoded.shortName(),row.getShortName()))
                        decoded.display().ifPresent(name->names.put(row.getUniqueId(),name));
                }catch(IllegalArgumentException malformed){/* Optional corrupted labels must not hide monetary members. */}
            }
            return new CompetitionIncome(profile,names);
        }));
    }
    public record CompetitionIncome(com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.Profile profile,Map<Long,String> names) {
        public CompetitionIncome {names=Map.copyOf(names);}
    }

    private static CoefficientClub coefficientClub(com.github.fmaiassistent.domain.entity.NativeClubEntity club) {
        var values=com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.values(
                club.getUefaCoefficientHex(),club.getUefaRuleVersion(),club.getModuleSha256())
                .orElseThrow(()->new IllegalStateException("Stored club coefficient profile is invalid"));
        return new CoefficientClub(club.getUniqueId(),club.getName(),values);
    }

    public record CoefficientClub(long uniqueId,String name,
            com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.Values coefficients) { }
}
