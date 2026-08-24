package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.exporter.StaffExporter;
import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.StaffFilterCriteria;
import com.github.fmaiassistent.repository.StaffRepository;
import com.github.fmaiassistent.staff.StaffRoleRatingCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Map<Long, ClubEntity> clubsByAddress = clubsByAddress();
        StaffExporter.ExportResult result = exporter.exportAllStaff(pid, build, gamePluginBase);
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
        return staff.findAllWithClubs().stream().filter(value -> matches(value, filter)).toList();
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

    private static boolean matches(StaffEntity staff, StaffFilterCriteria filter) {
        if (!contains(staff.getName(), filter.name()) || !exact(staff.getGender(), filter.gender())
                || !exact(staff.getNationality(), filter.nationality()) || !exact(staff.getClub(), filter.club())
                || !exact(staff.getDivision(), filter.division()) || !exact(staff.getJob(), filter.job())
                || !range(staff.getAge(), filter.ageMin(), filter.ageMax())
                || !range(staff.getCa(), filter.caMin(), filter.caMax())
                || !range(staff.getPa(), filter.paMin(), filter.paMax())
                || !minimum(staff.getCurrentReputation(), filter.currentReputationMin())
                || !minimum(staff.getWorldReputation(), filter.worldReputationMin())
                || (filter.salaryWeeklyMax() != null && (staff.getSalaryWeeklyRaw() == null
                    || staff.getSalaryWeeklyRaw() > filter.salaryWeeklyMax()))
                || !dateRange(staff.getContractEndDate(), filter.contractEndDateFrom(), filter.contractEndDateTo())) {
            return false;
        }
        for (Map.Entry<String, Integer> required : filter.attributeMinimums().entrySet()) {
            Object value = staff.value(required.getKey().toLowerCase(Locale.ROOT));
            if (!(value instanceof Number number) || number.intValue() < required.getValue()) {
                return false;
            }
        }
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

    private static boolean contains(String actual, String wanted) {
        return wanted == null || wanted.isBlank() || actual != null && actual.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
    }
    private static boolean exact(String actual, String wanted) {
        return wanted == null || wanted.isBlank() || actual != null && actual.equalsIgnoreCase(wanted);
    }
    private static boolean range(Integer value, Integer min, Integer max) {
        return (min == null || value != null && value >= min) && (max == null || value != null && value <= max);
    }
    private static boolean minimum(Integer value, Integer min) { return min == null || value != null && value >= min; }
    private static boolean dateRange(String raw, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        if (raw == null || raw.isBlank()) return false;
        try {
            LocalDate value = LocalDate.parse(raw);
            return (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
        } catch (DateTimeException exception) {
            return false;
        }
    }

    public record LoadResult(String gameDate, long count) { }
}
