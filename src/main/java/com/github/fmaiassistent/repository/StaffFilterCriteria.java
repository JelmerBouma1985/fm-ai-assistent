package com.github.fmaiassistent.repository;

import java.time.LocalDate;
import java.util.Map;

public record StaffFilterCriteria(
        String name, String gender, String nationality, String club, String division, String job,
        Integer ageMin, Integer ageMax, Integer caMin, Integer caMax, Integer paMin, Integer paMax,
        Integer currentReputationMin, Integer worldReputationMin, Long salaryWeeklyMax,
        LocalDate contractEndDateFrom, LocalDate contractEndDateTo,
        Map<String, Integer> attributeMinimums,
        String coachingRole, Double minimumCoachingStars,
        Map<String, Double> coachingRoleMinimumStars) {
    public StaffFilterCriteria {
        attributeMinimums = attributeMinimums == null ? Map.of() : Map.copyOf(attributeMinimums);
        coachingRoleMinimumStars = coachingRoleMinimumStars == null ? Map.of() : Map.copyOf(coachingRoleMinimumStars);
    }

    public static StaffFilterCriteria empty() {
        return new StaffFilterCriteria("", "", "", "", "", "", null, null, null, null, null, null,
                null, null, null, null, null, Map.of(), "", null, Map.of());
    }

    public boolean isEmpty() {
        return blank(name) && blank(gender) && blank(nationality) && blank(club) && blank(division) && blank(job)
                && ageMin == null && ageMax == null && caMin == null && caMax == null && paMin == null && paMax == null
                && currentReputationMin == null && worldReputationMin == null && salaryWeeklyMax == null
                && contractEndDateFrom == null && contractEndDateTo == null && attributeMinimums.isEmpty()
                && blank(coachingRole) && minimumCoachingStars == null && coachingRoleMinimumStars.isEmpty();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
