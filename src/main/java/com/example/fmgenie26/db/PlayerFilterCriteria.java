package com.example.fmgenie26.db;

import com.vaadin.flow.component.html.Pre;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Predicate;

public record PlayerFilterCriteria(
        String name,
        String gender,
        String playingNation,
        String playingCompetition,
        Integer ageMin,
        Integer ageMax,
        String nationality,
        Integer currentReputationMin,
        Integer currentReputationMax,
        Integer homeReputationMin,
        Integer homeReputationMax,
        Integer worldReputationMin,
        Integer worldReputationMax,
        Integer caMin,
        Integer caMax,
        Integer paMin,
        Integer paMax,
        LocalDate contractEndDateFrom,
        LocalDate contractEndDateTo,
        Long askingPriceMin,
        Long askingPriceMax,
        Map<String, Integer> positionMinimums,
        Map<String, Integer> attributeMinimums) {
    public static PlayerFilterCriteria empty() {
        return new PlayerFilterCriteria(
                "", "", "", "", null, null, "", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return isBlank(name)
                && isBlank(gender)
                && isBlank(playingNation)
                && isBlank(playingCompetition)
                && ageMin == null
                && ageMax == null
                && isBlank(nationality)
                && currentReputationMin == null
                && currentReputationMax == null
                && homeReputationMin == null
                && homeReputationMax == null
                && worldReputationMin == null
                && worldReputationMax == null
                && caMin == null
                && caMax == null
                && paMin == null
                && paMax == null
                && contractEndDateFrom == null
                && contractEndDateTo == null
                && askingPriceMin == null
                && askingPriceMax == null
                && positionMinimums.isEmpty()
                && attributeMinimums.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
