package com.github.fmaiassistent.decision;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.tactic.TacticDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RoleFitService {
    private final JdbcTemplate jdbc;
    private volatile List<RoleAttribute> cachedRoleAttributes;

    public RoleFitService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int positionScore(PlayerEntity player, String rawPosition) {
        String column = positionColumn(rawPosition);
        if (column == null) {
            return 0;
        }
        Object value = player.getColumnValue(column);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public Fit roleFit(PlayerEntity player, String roleName, String phase, String position) {
        if (blank(roleName) || normalize(roleName).equals("no role") || roleName.startsWith("Unknown role")) {
            return Fit.unavailable();
        }
        String group = positionGroup(position);
        List<RoleAttribute> all = roleAttributes();
        List<RoleAttribute> exact = all.stream()
                .filter(row -> blank(group) || equalsText(row.positionGroup(), group))
                .filter(row -> blank(phase) || equalsText(row.phase(), phase))
                .filter(row -> equalsText(row.roleName(), roleName))
                .toList();
        List<RoleAttribute> rows = exact.isEmpty() ? all.stream()
                .filter(row -> blank(group) || equalsText(row.positionGroup(), group))
                .filter(row -> blank(phase) || equalsText(row.phase(), phase))
                .filter(row -> normalize(row.roleName()).contains(normalize(roleName)))
                .toList() : exact;
        if (rows.isEmpty()) {
            return Fit.unavailable();
        }

        Map<String, Integer> weights = new LinkedHashMap<>();
        rows.forEach(row -> weights.merge(attributeKey(row.attributeName()),
                "primary".equals(row.priority()) ? 2 : 1, Math::max));
        List<AttributeScore> scores = weights.entrySet().stream()
                .map(entry -> new AttributeScore(entry.getKey(), attribute(player, entry.getKey()), entry.getValue()))
                .toList();
        int totalWeight = scores.stream().mapToInt(AttributeScore::weight).sum();
        double total = scores.stream().mapToDouble(value -> value.score() * value.weight()).sum();
        double score = totalWeight == 0 ? 0 : round1(total / totalWeight);
        List<String> strengths = scores.stream()
                .sorted(Comparator.comparingInt(AttributeScore::score).reversed().thenComparing(AttributeScore::name))
                .limit(3).map(AttributeScore::compact).toList();
        List<String> gaps = scores.stream()
                .sorted(Comparator.comparingInt(AttributeScore::score).thenComparing(AttributeScore::name))
                .limit(3).map(AttributeScore::compact).toList();
        return new Fit(score, strengths, gaps, true);
    }

    public SlotFit slotFit(PlayerEntity player, TacticDefinition.TacticSlot slot) {
        PhaseFit in = phaseFit(player, slot.inPossession(), "In Possession");
        PhaseFit out = phaseFit(player, slot.outOfPossession(), "Out of Possession");
        List<PhaseFit> phases = List.of(in, out).stream().filter(PhaseFit::present).toList();
        double position = phases.stream().mapToInt(PhaseFit::positionScore).average().orElse(0);
        List<PhaseFit> rolePhases = phases.stream().filter(value -> value.roleFit().available()).toList();
        Double role = rolePhases.isEmpty() ? null
                : round1(rolePhases.stream().mapToDouble(value -> value.roleFit().score()).average().orElse(0));
        int ca = value(player.getCa());
        double overall = round1(position / 20.0 * 35.0
                + (role == null ? position / 20.0 : role / 20.0) * 45.0
                + Math.min(1.0, ca / 200.0) * 20.0);
        boolean viable = !phases.isEmpty() && phases.stream().allMatch(value -> value.positionScore() >= 15);
        return new SlotFit(slot.index(), round1(position), role, overall, viable, in, out);
    }

    private PhaseFit phaseFit(PlayerEntity player, TacticDefinition.PhaseRole role, String phase) {
        if (role == null) {
            return PhaseFit.missing(phase);
        }
        return new PhaseFit(
                phase,
                role.position(),
                role.role(),
                positionScore(player, role.position()),
                roleFit(player, role.role(), phase, role.position()),
                true);
    }

    private List<RoleAttribute> roleAttributes() {
        List<RoleAttribute> local = cachedRoleAttributes;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedRoleAttributes == null) {
                cachedRoleAttributes = List.copyOf(jdbc.query("""
                                SELECT r.position_group, r.role_name, r.phase,
                                       ra.attribute_priority, a.attribute_name
                                FROM fm_role r
                                JOIN fm_role_attribute ra ON ra.role_id = r.id
                                JOIN fm_attribute a ON a.id = ra.attribute_id
                                """,
                        (rs, row) -> new RoleAttribute(
                                rs.getString("position_group"),
                                rs.getString("role_name"),
                                rs.getString("phase"),
                                rs.getString("attribute_priority"),
                                rs.getString("attribute_name"))));
            }
            return cachedRoleAttributes;
        }
    }

    public static String positionColumn(String raw) {
        if (blank(raw)) {
            return null;
        }
        String position = normalize(raw).replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
        if (position.startsWith("DML") || position.startsWith("DMR") || position.equals("DM")) position = "DMC";
        if (position.startsWith("MCL") || position.startsWith("MCR")) position = "MC";
        if (position.startsWith("DCL") || position.startsWith("DCR")) position = "DC";
        if (position.startsWith("AMCL") || position.startsWith("AMCR")) position = "AMC";
        if (position.startsWith("STL") || position.startsWith("STR")) position = "ST";
        return switch (position) {
            case "GK", "GOALKEEPER" -> "GOALKEEPER";
            case "DL", "LB" -> "DEFENDER_LEFT";
            case "DC", "CB", "SW" -> "DEFENDER_CENTRAL";
            case "DR", "RB" -> "DEFENDER_RIGHT";
            case "WBL", "LWB" -> "WING_BACK_LEFT";
            case "DMC" -> "DEFENSIVE_MIDFIELDER";
            case "WBR", "RWB" -> "WING_BACK_RIGHT";
            case "ML", "LM" -> "MIDFIELDER_LEFT";
            case "MC", "CM" -> "MIDFIELDER_CENTRAL";
            case "MR", "RM" -> "MIDFIELDER_RIGHT";
            case "AML", "LW" -> "ATTACKING_MIDFIELDER_LEFT";
            case "AMC", "AM" -> "ATTACKING_MIDFIELDER_CENTRAL";
            case "AMR", "RW" -> "ATTACKING_MIDFIELDER_RIGHT";
            case "ST", "CF" -> "STRIKER";
            default -> null;
        };
    }

    private static String positionGroup(String position) {
        String column = positionColumn(position);
        if (column == null) return null;
        return switch (column) {
            case "GOALKEEPER" -> "Goalkeeper";
            case "DEFENDER_CENTRAL" -> "Centre-Back";
            case "DEFENDER_LEFT", "DEFENDER_RIGHT", "WING_BACK_LEFT", "WING_BACK_RIGHT" -> "Full-Back / Wing-Back";
            case "DEFENSIVE_MIDFIELDER" -> "Defensive Midfielder";
            case "MIDFIELDER_CENTRAL" -> "Central Midfielder";
            case "MIDFIELDER_LEFT", "MIDFIELDER_RIGHT", "ATTACKING_MIDFIELDER_LEFT", "ATTACKING_MIDFIELDER_RIGHT" -> "Wide Midfielder / Winger";
            case "ATTACKING_MIDFIELDER_CENTRAL" -> "Attacking Midfielder";
            case "STRIKER" -> "Striker";
            default -> null;
        };
    }

    private static int attribute(PlayerEntity player, String name) {
        Object raw = player.getColumnValue(name.toUpperCase(Locale.ROOT));
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static String attributeKey(String name) {
        String normalized = normalize(name).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return switch (normalized) {
            case "aerial_reach" -> "aerial_ability";
            case "jumping" -> "jumping_reach";
            case "team_work" -> "teamwork";
            case "free_kick_taking" -> "free_kicks";
            case "penalty_taking" -> "penalties";
            default -> normalized;
        };
    }

    private static boolean equalsText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static int value(Integer value) { return value == null ? 0 : value; }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }

    private record RoleAttribute(String positionGroup, String roleName, String phase, String priority, String attributeName) {}
    private record AttributeScore(String name, int score, int weight) {
        String compact() { return name + ":" + score; }
    }

    public record Fit(double score, List<String> strengths, List<String> gaps, boolean available) {
        static Fit unavailable() { return new Fit(0, List.of(), List.of(), false); }
        public Map<String, Object> toMap() {
            if (!available) return Map.of("available", false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", true);
            out.put("score", score);
            out.put("strengths", strengths);
            out.put("gaps", gaps);
            return out;
        }
    }

    public record PhaseFit(String phase, String position, String role, int positionScore, Fit roleFit, boolean present) {
        static PhaseFit missing(String phase) { return new PhaseFit(phase, null, null, 0, Fit.unavailable(), false); }
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("phase", phase);
            out.put("position", position);
            out.put("role", role);
            out.put("position_score", positionScore);
            out.put("role_fit", roleFit.toMap());
            return out;
        }
    }

    public record SlotFit(
            int slot,
            double positionFit,
            Double roleFit,
            double overall,
            boolean viable,
            PhaseFit inPossession,
            PhaseFit outOfPossession) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("slot", slot);
            out.put("position_fit", positionFit);
            out.put("role_fit", roleFit);
            out.put("overall", overall);
            out.put("viable", viable);
            out.put("in_possession", inPossession.toMap());
            out.put("out_of_possession", outOfPossession.toMap());
            return out;
        }
    }
}
