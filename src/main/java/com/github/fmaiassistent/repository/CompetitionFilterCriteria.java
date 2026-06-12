package com.github.fmaiassistent.repository;

public record CompetitionFilterCriteria(
        String name,
        String nation,
        Integer reputationMin,
        Integer reputationMax,
        String gender) {
    public static CompetitionFilterCriteria empty() {
        return new CompetitionFilterCriteria(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return isBlank(name)
                && isBlank(nation)
                && reputationMin == null
                && reputationMax == null
                && isBlank(gender);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
