package com.github.fmaiassistent.staff;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class StaffAttributeDefinitions {
    public static final int ATTRIBUTES_REL = 0x10;

    public static final List<StaffAttribute> ALL = List.of(
            new StaffAttribute("authority", "Authority", 0x04, Storage.DIRECT),
            new StaffAttribute("attacking", "Attacking", 0x22),
            new StaffAttribute("defending", "Defending", 0x23),
            new StaffAttribute("fitness", "Fitness", 0x24),
            new StaffAttribute("goalkeeping", "Goalkeeping", 0x1B),
            new StaffAttribute("possession", "Possession", 0x25),
            new StaffAttribute("technical", "Technical", 0x26),
            new StaffAttribute("tactical", "Tactical", 0x27),
            new StaffAttribute("set_pieces", "Set Pieces", 0x33),
            new StaffAttribute("determination", "Determination", 0x0D),
            new StaffAttribute("people_management", "People Management", 0x1E),
            new StaffAttribute("motivating", "Motivating", 0x1F),
            new StaffAttribute("judging_player_ability", "Judging Player Ability", 0x1C),
            new StaffAttribute("judging_player_potential", "Judging Player Potential", 0x1D),
            new StaffAttribute("judging_staff_ability", "Judging Staff Ability", 0x32),
            new StaffAttribute("negotiating", "Negotiating", 0x31),
            new StaffAttribute("tactical_knowledge", "Tactical Knowledge", 0x21),
            new StaffAttribute("physiotherapy", "Physiotherapy", 0x20),
            new StaffAttribute("sports_science", "Sports Science", 0x2F),
            new StaffAttribute("data_analysis", "Data Analysis", 0x2C),
            new StaffAttribute("working_with_youngsters", "Working With Youngsters", 0x0C, Storage.DIRECT));

    public static final Map<String, StaffAttribute> BY_KEY = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(StaffAttribute::key, Function.identity()));

    private StaffAttributeDefinitions() {
    }

    public enum Storage { SCALED_BY_FIVE, DIRECT }

    public record StaffAttribute(String key, String label, int offset, Storage storage) {
        public StaffAttribute(String key, String label, int offset) {
            this(key, label, offset, Storage.SCALED_BY_FIVE);
        }
    }
}
