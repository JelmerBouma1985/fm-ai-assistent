package com.github.fmaiassistent.web.ui;

import java.util.List;
import java.util.stream.Stream;

final class PlayerAttributeCatalog {
    static final String TECHNICAL = "Technical";
    static final String GOALKEEPING = "Goalkeeping";

    private static final List<AttributeCategory> CATEGORIES = List.of(
            new AttributeCategory(TECHNICAL, 10, List.of(
                    attr("crossing", "Crossing"),
                    attr("dribbling", "Dribbling"),
                    attr("finishing", "Finishing"),
                    attr("first_touch", "First Touch"),
                    attr("heading", "Heading"),
                    attr("long_shots", "Long Shots"),
                    attr("marking", "Marking"),
                    attr("passing", "Passing"),
                    attr("tackling", "Tackling"),
                    attr("technique", "Technique"))),
            new AttributeCategory("Set Pieces", 20, List.of(
                    attr("corners", "Corners"),
                    attr("free_kicks", "Free Kick Taking"),
                    attr("long_throws", "Long Throws"),
                    attr("penalties", "Penalty Taking"))),
            new AttributeCategory("Mental", 30, List.of(
                    attr("aggression", "Aggression"),
                    attr("anticipation", "Anticipation"),
                    attr("bravery", "Bravery"),
                    attr("composure", "Composure"),
                    attr("concentration", "Concentration"),
                    attr("decisions", "Decisions"),
                    attr("determination", "Determination"),
                    attr("flair", "Flair"),
                    attr("leadership", "Leadership"),
                    attr("off_the_ball", "Off the Ball"),
                    attr("positioning", "Positioning"),
                    attr("teamwork", "Teamwork"),
                    attr("vision", "Vision"),
                    attr("work_rate", "Work Rate"))),
            new AttributeCategory("Physical", 40, List.of(
                    attr("acceleration", "Acceleration"),
                    attr("agility", "Agility"),
                    attr("balance", "Balance"),
                    attr("jumping_reach", "Jumping Reach"),
                    attr("natural_fitness", "Natural Fitness"),
                    attr("pace", "Pace"),
                    attr("stamina", "Stamina"),
                    attr("strength", "Strength"))),
            new AttributeCategory(GOALKEEPING, 50, List.of(
                    attr("aerial_ability", "Aerial Reach"),
                    attr("command_of_area", "Command of Area"),
                    attr("communication", "Communication"),
                    attr("eccentricity", "Eccentricity"),
                    attr("first_touch", "First Touch"),
                    attr("handling", "Handling"),
                    attr("kicking", "Kicking"),
                    attr("one_on_ones", "One on Ones"),
                    attr("passing", "Passing"),
                    attr("reflexes", "Reflexes"),
                    attr("rushing_out", "Rushing Out"),
                    attr("tendency_to_punch", "Tendency to Punch"),
                    attr("throwing", "Throwing"))),
            new AttributeCategory("Hidden Attributes", 60, List.of(
                    attr("adaptability", "Adaptability"),
                    attr("ambition", "Ambition"),
                    attr("consistency", "Consistency"),
                    attr("controversy", "Controversy"),
                    attr("dirtiness", "Dirtiness"),
                    attr("important_matches", "Important Matches"),
                    attr("injury_proneness", "Injury Proneness"),
                    attr("loyalty", "Loyalty"),
                    attr("pressure", "Pressure"),
                    attr("professionalism", "Professionalism"),
                    attr("sportsmanship", "Sportsmanship"),
                    attr("temperament", "Temperament"),
                    attr("versatility", "Versatility"))));

    private PlayerAttributeCatalog() {
    }

    static List<AttributeCategory> categories(boolean showGoalkeeping) {
        if (showGoalkeeping) {
            return Stream.concat(
                            CATEGORIES.stream().filter(category -> GOALKEEPING.equals(category.name())),
                            CATEGORIES.stream().filter(category -> !GOALKEEPING.equals(category.name()) && !TECHNICAL.equals(category.name())))
                    .toList();
        }
        return CATEGORIES.stream()
                .filter(category -> !GOALKEEPING.equals(category.name()))
                .toList();
    }

    static List<AttributeCategory> categoriesWithGoalkeepingLast() {
        return Stream.concat(
                        CATEGORIES.stream().filter(category -> !GOALKEEPING.equals(category.name())),
                        CATEGORIES.stream().filter(category -> GOALKEEPING.equals(category.name())))
                .toList();
    }

    static List<AttributeCategory> filterCategories() {
        return List.of(
                CATEGORIES.get(0),
                CATEGORIES.get(1),
                CATEGORIES.get(2),
                CATEGORIES.get(3),
                CATEGORIES.get(4),
                CATEGORIES.get(5));
    }

    private static AttributeDefinition attr(String columnName, String displayName) {
        return new AttributeDefinition(columnName == null ? null : columnName.toUpperCase(), displayName);
    }

    record AttributeCategory(String name, int order, List<AttributeDefinition> attributes) {
    }

    record AttributeDefinition(String columnName, String displayName) {
    }
}
