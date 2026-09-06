package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.exporter.StaffExporter;
import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.CatalogSpecifications;
import com.github.fmaiassistent.repository.StaffFilterCriteria;
import com.github.fmaiassistent.repository.StaffRepository;
import com.github.fmaiassistent.staff.StaffRoleRatingCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class StaffDatabaseService {
    private final StaffRepository staff;
    private final ClubRepository clubs;
    private final StaffExporter exporter = new StaffExporter();

    public StaffDatabaseService(StaffRepository staff, ClubRepository clubs) {
        this.staff = staff;
        this.clubs = clubs;
    }

    @Transactional
    public LoadResult loadAllStaff(int pid, int build, Long gamePluginBase) throws IOException {
        return saveAllStaff(exportAllStaff(pid, build, gamePluginBase));
    }

    public StaffExporter.ExportResult exportAllStaff(int pid, int build, Long gamePluginBase) throws IOException {
        return exporter.exportAllStaff(pid, build, gamePluginBase);
    }

    @Transactional
    public LoadResult saveAllStaff(StaffExporter.ExportResult result) {
        Map<Long, ClubEntity> clubsByAddress = clubsByAddress();
        staff.saveAll(result.rows().stream().map(row -> entity(row, clubsByAddress)).toList());
        return new LoadResult(result.gameDate(), result.rows().size());
    }

    @Transactional(readOnly = true)
    public long countStaff() { return staff.count(); }

    @Transactional(readOnly = true)
    public List<StaffEntity> findAllStaff() { return staff.findAllWithClubs(); }

    @Transactional(readOnly = true)
    public Optional<StaffEntity> findByUniqueId(Long uniqueId) { return staff.findFirstByUniqueId(uniqueId); }

    @Transactional(readOnly = true)
    public List<StaffEntity> findStaff(StaffFilterCriteria criteria) {
        StaffFilterCriteria filter = criteria == null ? StaffFilterCriteria.empty() : criteria;
        return staff.findAll(CatalogSpecifications.staff(filter)).stream()
                .filter(value -> matchesDerivedRatings(value, filter)).toList();
    }

    @Transactional(readOnly = true)
    public Page<StaffEntity> findStaffPage(StaffFilterCriteria criteria, Pageable pageable) {
        StaffFilterCriteria filter = criteria == null ? StaffFilterCriteria.empty() : criteria;
        if (!hasDerivedRatingFilter(filter)) {
            return staff.findAll(CatalogSpecifications.staff(filter), pageable);
        }
        List<StaffEntity> matches = staff.findAll(CatalogSpecifications.staff(filter), pageable.getSort()).stream()
                .filter(value -> matchesDerivedRatings(value, filter)).toList();
        int start = Math.min(Math.toIntExact(pageable.getOffset()), matches.size());
        int end = Math.min(start + pageable.getPageSize(), matches.size());
        return new PageImpl<>(matches.subList(start, end), pageable, matches.size());
    }

    @Transactional(readOnly = true)
    public long countStaff(StaffFilterCriteria criteria) {
        StaffFilterCriteria filter = criteria == null ? StaffFilterCriteria.empty() : criteria;
        return hasDerivedRatingFilter(filter)
                ? findStaff(filter).size()
                : staff.count(CatalogSpecifications.staff(filter));
    }

    private Map<Long, ClubEntity> clubsByAddress() {
        Map<Long, ClubEntity> out = new HashMap<>();
        for (ClubEntity club : clubs.findAll()) {
            if (club.getSourceAddress() != null) {
                out.merge(club.getSourceAddress(), club, StaffDatabaseService::higherReputation);
            }
        }
        return out;
    }

    private static ClubEntity higherReputation(ClubEntity left, ClubEntity right) {
        int leftRep = left.getReputation() == null ? 0 : left.getReputation();
        int rightRep = right.getReputation() == null ? 0 : right.getReputation();
        return rightRep > leftRep ? right : left;
    }

    private static StaffEntity entity(Map<String, Object> row, Map<Long, ClubEntity> clubsByAddress) {
        StaffEntity entity = StaffEntity.fromExportRow(row);
        if (row.get("_club_address") instanceof Number address) {
            entity.setClubEntity(clubsByAddress.get(address.longValue()));
        }
        return entity;
    }

    private static boolean matchesDerivedRatings(StaffEntity staff, StaffFilterCriteria filter) {
        if (filter.coachingRole() != null && !filter.coachingRole().isBlank()) {
            var rating = StaffRoleRatingCalculator.rating(staff, filter.coachingRole());
            if (rating.isEmpty() || filter.minimumCoachingStars() != null
                    && rating.get().stars() < filter.minimumCoachingStars()) return false;
        } else if (filter.minimumCoachingStars() != null) {
            var best = StaffRoleRatingCalculator.bestRating(staff);
            if (best.isEmpty() || best.get().stars() < filter.minimumCoachingStars()) return false;
        }
        for (Map.Entry<String, Double> required : filter.coachingRoleMinimumStars().entrySet()) {
            var rating = StaffRoleRatingCalculator.rating(staff, required.getKey());
            if (required.getValue() == null || rating.isEmpty() || rating.get().stars() < required.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDerivedRatingFilter(StaffFilterCriteria filter) {
        return filter.coachingRole() != null && !filter.coachingRole().isBlank()
                || filter.minimumCoachingStars() != null
                || !filter.coachingRoleMinimumStars().isEmpty();
    }

    public record LoadResult(String gameDate, long count) { }
}
