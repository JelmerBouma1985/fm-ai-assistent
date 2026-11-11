package com.github.fmaiassistent.managedclub;

public record ManagedClubContext(
        long version,
        State state,
        String managerName,
        Long managerUniqueId,
        String clubName,
        String competition,
        String nation,
        String gender,
        Integer reputation,
        Long balance,
        Long transferBudget,
        Long payrollBudget,
        Long clubAddress,
        String message,
        ManagedNationalTeam nationalTeam) {

    public ManagedClubContext(long version, State state, String managerName, Long managerUniqueId,
                              String clubName, String competition, String nation, String gender, Integer reputation,
                              Long balance, Long transferBudget, Long payrollBudget, Long clubAddress, String message) {
        this(version, state, managerName, managerUniqueId, clubName, competition, nation, gender, reputation,
                balance, transferBudget, payrollBudget, clubAddress, message, null);
    }

    public enum State {
        NOT_LOADED,
        AVAILABLE,
        NATIONAL_TEAM,
        UNEMPLOYED,
        UNAVAILABLE
    }

    public static ManagedClubContext notLoaded(long version) {
        return new ManagedClubContext(
                version, State.NOT_LOADED, "", null, "", "", "", "", null,
                null, null, null, null, "Load FM27 data from RAM to detect the managed club");
    }

    public static ManagedClubContext unavailable(long version, String message) {
        return new ManagedClubContext(
                version, State.UNAVAILABLE, "", null, "", "", "", "", null,
                null, null, null, null, message);
    }

    public static ManagedClubContext unemployed(long version, String managerName, Long managerUniqueId) {
        return new ManagedClubContext(
                version, State.UNEMPLOYED, managerName, managerUniqueId, "", "", "", "", null,
                null, null, null, null,
                "The human manager is unemployed and has no managed club. Managed-club squad, transfers, lineup and fixtures are unavailable.");
    }

    public boolean available() {
        return state == State.AVAILABLE;
    }

    public boolean nationalTeamAvailable() {
        return nationalTeam != null;
    }

    public boolean adviceAvailable() {
        return available() || nationalTeamAvailable();
    }

    public String careerKey() {
        if ((state != State.AVAILABLE && state != State.UNEMPLOYED && state != State.NATIONAL_TEAM)
                || managerUniqueId == null || managerUniqueId <= 0 || managerName == null || managerName.isBlank()) {
            return null;
        }
        String normalizedName = java.text.Normalizer.normalize(managerName, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return managerUniqueId + ":" + normalizedName;
    }
}
