package com.github.fmaiassistent.staff;

import com.github.fmaiassistent.domain.entity.StaffEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** FM26 single-assignment coaching ratings, derived from the live 1-20 staff attributes. */
public final class StaffRoleRatingCalculator {
    private static final Map<String, Integer> COMMON = weights(
            "authority", 2, "determination", 2, "motivating", 2);

    public static final List<RoleDefinition> ROLES = List.of(
            role("goalkeeping", "Goalkeeping", "Goalkeeping", weights("goalkeeping", 9)),
            role("defending_tactical", "Defending", "Tactical", weights("defending", 6, "tactical", 3)),
            role("defending_technical", "Defending", "Technical", weights("defending", 6, "technical", 3)),
            role("attacking_tactical", "Attacking", "Tactical", weights("attacking", 6, "tactical", 3)),
            role("attacking_technical", "Attacking", "Technical", weights("attacking", 6, "technical", 3)),
            role("possession_tactical", "Possession", "Tactical", weights("possession", 6, "tactical", 3)),
            role("possession_technical", "Possession", "Technical", weights("technical", 9)),
            role("fitness", "Fitness", "Fitness", weights("fitness", 9)),
            role("set_pieces", "Set Pieces", "Set Pieces", weights("set_pieces", 6, "tactical_knowledge", 3)));

    public static final Map<String, RoleDefinition> BY_KEY;

    static {
        Map<String, RoleDefinition> byKey = new LinkedHashMap<>();
        ROLES.forEach(role -> byKey.put(role.key(), role));
        BY_KEY = Collections.unmodifiableMap(byKey);
    }

    private StaffRoleRatingCalculator() {
    }

    public static List<RoleRating> ratings(StaffEntity staff) {
        return ROLES.stream().map(role -> calculate(staff, role)).flatMap(Optional::stream).toList();
    }

    public static Optional<RoleRating> rating(StaffEntity staff, String roleKey) {
        RoleDefinition role = BY_KEY.get(normalizeRoleKey(roleKey));
        return role == null ? Optional.empty() : calculate(staff, role);
    }

    public static Optional<RoleRating> bestRating(StaffEntity staff) {
        return ratings(staff).stream().max(Comparator.comparingDouble(RoleRating::score20)
                .thenComparing(RoleRating::key, Comparator.reverseOrder()));
    }

    public static String normalizeRoleKey(String roleKey) {
        return roleKey == null ? "" : roleKey.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    public static String quality(double score20) {
        if (score20 >= 20) return "Elite";
        if (score20 >= 18) return "Outstanding";
        if (score20 >= 15) return "Very Good";
        if (score20 >= 12) return "Good";
        if (score20 >= 10) return "Average";
        if (score20 >= 7) return "Competent";
        if (score20 >= 4) return "Reasonable";
        return "Unsuited";
    }

    private static Optional<RoleRating> calculate(StaffEntity staff, RoleDefinition role) {
        int weightedTotal = 0;
        List<RoleAttribute> attributes = new ArrayList<>();
        for (Map.Entry<String, Integer> weighted : role.weights().entrySet()) {
            Object raw = staff.value(weighted.getKey());
            if (!(raw instanceof Number number)) return Optional.empty();
            int value = number.intValue();
            String label = StaffAttributeDefinitions.BY_KEY.get(weighted.getKey()).label();
            attributes.add(new RoleAttribute(weighted.getKey(), label, value, quality(value), weighted.getValue()));
            weightedTotal += value * weighted.getValue();
        }
        double rawScore20 = weightedTotal / 15.0;
        double score20 = Math.round(rawScore20 * 10.0) / 10.0;
        double rawStars = weightedTotal / 60.0;
        double stars = Math.min(5.0, Math.max(0.5, Math.floor(rawStars * 2.0 + 1.0 + 1e-9) / 2.0));
        return Optional.of(new RoleRating(role.key(), role.label(), role.group(), role.specialism(),
                score20, stars, quality(rawScore20), List.copyOf(attributes)));
    }

    private static RoleDefinition role(String key, String group, String specialism, Map<String, Integer> specialist) {
        Map<String, Integer> all = new LinkedHashMap<>(specialist);
        all.putAll(COMMON);
        String label = group.equals(specialism) ? group : group + " - " + specialism;
        return new RoleDefinition(key, label, group, specialism, Collections.unmodifiableMap(all));
    }

    private static Map<String, Integer> weights(Object... values) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            out.put((String) values[index], (Integer) values[index + 1]);
        }
        return out;
    }

    public record RoleDefinition(String key, String label, String group, String specialism,
                                 Map<String, Integer> weights) {
    }

    public record RoleAttribute(String key, String label, int value, String quality, int weight) {
        public Map<String, Object> toApiMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key); out.put("label", label); out.put("value", value);
            out.put("quality", quality); out.put("weight", weight);
            return out;
        }
    }

    public record RoleRating(String key, String label, String group, String specialism, double score20,
                             double stars, String quality, List<RoleAttribute> attributes) {
        public Map<String, Object> toApiMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", key); out.put("label", label); out.put("score_20", score20);
            out.put("stars", stars); out.put("quality", quality);
            out.put("attributes", attributes.stream().map(RoleAttribute::toApiMap).toList());
            return out;
        }
    }
}
