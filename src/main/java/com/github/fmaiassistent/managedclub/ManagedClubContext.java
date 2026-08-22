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
        String message) {

    public enum State {
        NOT_LOADED,
        AVAILABLE,
        UNAVAILABLE
    }

    public static ManagedClubContext notLoaded(long version) {
        return new ManagedClubContext(
                version, State.NOT_LOADED, "", null, "", "", "", "", null,
                null, null, null, null, "Load FM26 data from RAM to detect the managed club");
    }

    public static ManagedClubContext unavailable(long version, String message) {
        return new ManagedClubContext(
                version, State.UNAVAILABLE, "", null, "", "", "", "", null,
                null, null, null, null, message);
    }

    public boolean available() {
        return state == State.AVAILABLE;
    }

    public String careerKey() {
        if (!available() || managerUniqueId == null || managerUniqueId <= 0 || managerName == null || managerName.isBlank()) {
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
