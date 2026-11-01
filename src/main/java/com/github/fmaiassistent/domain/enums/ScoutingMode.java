package com.github.fmaiassistent.domain.enums;

public enum ScoutingMode {
    BEST_CLUB_SCOUT("Best scout at club"),
    PERFECT_SCOUT("20/20 scout (restore attributes afterwards)");

    private final String label;

    ScoutingMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
