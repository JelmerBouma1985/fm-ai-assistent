package com.github.fmaiassistent.repository;

public record ClubFilterCriteria(
        String name,
        String competition,
        String nation,
        Integer reputationMin,
        Integer reputationMax,
        Long balanceMin,
        Long balanceMax,
        Long transferBudgetMin,
        Long transferBudgetMax,
        Long payrollBudgetMin,
        Long payrollBudgetMax) {
    public static ClubFilterCriteria empty() {
        return new ClubFilterCriteria(null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return isBlank(name)
                && isBlank(competition)
                && isBlank(nation)
                && reputationMin == null
                && reputationMax == null
                && balanceMin == null
                && balanceMax == null
                && transferBudgetMin == null
                && transferBudgetMax == null
                && payrollBudgetMin == null
                && payrollBudgetMax == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
