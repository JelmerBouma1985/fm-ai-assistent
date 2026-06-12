package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.repository.PlayerColumnNames;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PositionTextFormatter {
    private PositionTextFormatter() {
    }

    public static String format(PlayerEntity player) {
        Map<PositionLine, List<PositionSide>> byLine = new EnumMap<>(PositionLine.class);
        for (FieldDef field : AttributeDefinitions.POSITION_FIELDS) {
            PlayerPosition position = playerPosition(field, player);
            if (position == null || position.score() < 15) {
                continue;
            }
            byLine.computeIfAbsent(position.line(), ignored -> new ArrayList<>());
            if (position.side() != null && !byLine.get(position.line()).contains(position.side())) {
                byLine.get(position.line()).add(position.side());
            }
        }

        List<PositionGroup> groups = new ArrayList<>();
        for (PositionLine line : PositionLine.values()) {
            if (!byLine.containsKey(line)) {
                continue;
            }
            List<PositionSide> sides = byLine.get(line).stream()
                    .sorted(Comparator.comparingInt(PositionSide::sortOrder))
                    .toList();
            groups.add(new PositionGroup(line, sides));
        }

        return mergeSlashGroups(groups).stream()
                .map(PositionTextFormatter::formatGroup)
                .collect(Collectors.joining(","));
    }

    public static String positionLevelText(Object score) {
        Integer value = asInteger(score);
        if (value == null || value <= 4) {
            return "Cannot play";
        }
        if (value <= 8) {
            return "Can play";
        }
        if (value <= 14) {
            return "Is competent at";
        }
        if (value <= 17) {
            return "Is accomplished at";
        }
        return "Is natural at";
    }

    private static PlayerPosition playerPosition(FieldDef field, PlayerEntity player) {
        String column = PlayerColumnNames.toColumnName(field.name()).toUpperCase();
        Integer score = asInteger(player.getColumnValue(column));
        PositionLine line = PositionLine.fromFieldName(field.name());
        if (line == null || score == null) {
            return null;
        }
        return new PlayerPosition(line, PositionSide.fromFieldName(field.name()), score);
    }

    private static List<DisplayGroup> mergeSlashGroups(List<PositionGroup> groups) {
        List<DisplayGroup> out = new ArrayList<>();
        int index = 0;
        while (index < groups.size()) {
            PositionGroup group = groups.get(index);
            if (!group.canSlash()) {
                out.add(DisplayGroup.single(group));
                index++;
                continue;
            }

            int end = index + 1;
            while (end < groups.size()
                    && groups.get(end).canSlash()
                    && groups.get(end).sides().equals(group.sides())
                    && groups.get(end).line().sortOrder() == groups.get(end - 1).line().sortOrder() + 10) {
                end++;
            }

            if (end - index > 1) {
                out.add(DisplayGroup.slash(groups.subList(index, end), group.sides()));
                index = end;
            } else {
                out.add(DisplayGroup.single(group));
                index++;
            }
        }
        return out;
    }

    private static String formatGroup(DisplayGroup group) {
        if (group.sides().isEmpty()) {
            return group.lines().stream()
                    .map(PositionLine::abbreviation)
                    .collect(Collectors.joining("/"));
        }
        String lines = group.lines().stream()
                .map(PositionLine::abbreviation)
                .collect(Collectors.joining("/"));
        if (group.lines().size() > 1) {
            String sides = group.sides().stream()
                    .map(PositionSide::abbreviation)
                    .collect(Collectors.joining());
            return group.sides().size() == 1 ? lines + "(" + sides + ")" : lines + " (" + sides + ")";
        }
        return lines + "(" + group.sides().stream()
                .map(PositionSide::abbreviation)
                .collect(Collectors.joining()) + ")";
    }

    private static Integer asInteger(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private record PlayerPosition(PositionLine line, PositionSide side, int score) {
    }

    private record PositionGroup(PositionLine line, List<PositionSide> sides) {
        private boolean canSlash() {
            return !sides.isEmpty() && Set.of(PositionLine.DEFENDER, PositionLine.WING_BACK,
                    PositionLine.MIDFIELDER, PositionLine.ATTACKING_MIDFIELDER).contains(line);
        }
    }

    private record DisplayGroup(List<PositionLine> lines, List<PositionSide> sides) {
        private static DisplayGroup single(PositionGroup group) {
            return new DisplayGroup(List.of(group.line()), group.sides());
        }

        private static DisplayGroup slash(List<PositionGroup> groups, List<PositionSide> sides) {
            return new DisplayGroup(groups.stream().map(PositionGroup::line).toList(), sides);
        }
    }

    private enum PositionLine {
        GOALKEEPER("GK", 0),
        DEFENDER("D", 10),
        WING_BACK("WB", 20),
        DEFENSIVE_MIDFIELDER("DM", 30),
        MIDFIELDER("M", 40),
        ATTACKING_MIDFIELDER("AM", 50),
        STRIKER("ST", 60);

        private static final Map<String, PositionLine> FIELD_NAMES = fieldNames();
        private final String abbreviation;
        private final int sortOrder;

        PositionLine(String abbreviation, int sortOrder) {
            this.abbreviation = abbreviation;
            this.sortOrder = sortOrder;
        }

        private static PositionLine fromFieldName(String fieldName) {
            return FIELD_NAMES.get(fieldName);
        }

        private static Map<String, PositionLine> fieldNames() {
            Map<String, PositionLine> out = new LinkedHashMap<>();
            out.put("Goalkeeper", GOALKEEPER);
            out.put("DefenderLeft", DEFENDER);
            out.put("DefenderCentral", DEFENDER);
            out.put("DefenderRight", DEFENDER);
            out.put("WingBackLeft", WING_BACK);
            out.put("WingBackRight", WING_BACK);
            out.put("DefensiveMidfielder", DEFENSIVE_MIDFIELDER);
            out.put("MidfielderLeft", MIDFIELDER);
            out.put("MidfielderCentral", MIDFIELDER);
            out.put("MidfielderRight", MIDFIELDER);
            out.put("AttackingMidfielderLeft", ATTACKING_MIDFIELDER);
            out.put("AttackingMidfielderCentral", ATTACKING_MIDFIELDER);
            out.put("AttackingMidfielderRight", ATTACKING_MIDFIELDER);
            out.put("Striker", STRIKER);
            return out;
        }

        private String abbreviation() {
            return abbreviation;
        }

        private int sortOrder() {
            return sortOrder;
        }
    }

    private enum PositionSide {
        RIGHT("R", 0),
        LEFT("L", 1),
        CENTRAL("C", 2);

        private static final Map<String, PositionSide> FIELD_NAMES = fieldNames();
        private final String abbreviation;
        private final int sortOrder;

        PositionSide(String abbreviation, int sortOrder) {
            this.abbreviation = abbreviation;
            this.sortOrder = sortOrder;
        }

        private static PositionSide fromFieldName(String fieldName) {
            return FIELD_NAMES.get(fieldName);
        }

        private static Map<String, PositionSide> fieldNames() {
            Map<String, PositionSide> out = new LinkedHashMap<>();
            out.put("DefenderLeft", LEFT);
            out.put("DefenderCentral", CENTRAL);
            out.put("DefenderRight", RIGHT);
            out.put("WingBackLeft", LEFT);
            out.put("WingBackRight", RIGHT);
            out.put("MidfielderLeft", LEFT);
            out.put("MidfielderCentral", CENTRAL);
            out.put("MidfielderRight", RIGHT);
            out.put("AttackingMidfielderLeft", RIGHT);
            out.put("AttackingMidfielderCentral", CENTRAL);
            out.put("AttackingMidfielderRight", LEFT);
            return out;
        }

        private String abbreviation() {
            return abbreviation;
        }

        private int sortOrder() {
            return sortOrder;
        }
    }
}
