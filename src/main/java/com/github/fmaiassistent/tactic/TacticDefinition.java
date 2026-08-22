package com.github.fmaiassistent.tactic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured tactic data for deterministic decision tools. */
public record TacticDefinition(
        String name,
        String tacticalStyle,
        String mentality,
        List<TacticSlot> slots) {

    public TacticDefinition {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    static TacticDefinition from(Fm26TacticDecoder.DecodedTactic tactic) {
        int size = Math.max(tactic.inPossession().size(), tactic.outOfPossession().size());
        List<TacticSlot> slots = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            slots.add(new TacticSlot(
                    index + 1,
                    phaseRole(tactic.inPossession(), index),
                    phaseRole(tactic.outOfPossession(), index)));
        }
        return new TacticDefinition(tactic.name(), tactic.tacticalStyle(), tactic.mentality(), slots);
    }

    private static PhaseRole phaseRole(List<Fm26TacticDecoder.RoleSelection> roles, int index) {
        if (index >= roles.size()) {
            return null;
        }
        Fm26TacticDecoder.RoleSelection role = roles.get(index);
        return new PhaseRole(role.position(), role.role(), role.duty());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("tactical_style", tacticalStyle);
        out.put("mentality", mentality);
        out.put("slots", slots.stream().map(TacticSlot::toMap).toList());
        return out;
    }

    public record TacticSlot(int index, PhaseRole inPossession, PhaseRole outOfPossession) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("slot", index);
            out.put("in_possession", inPossession == null ? null : inPossession.toMap());
            out.put("out_of_possession", outOfPossession == null ? null : outOfPossession.toMap());
            return out;
        }
    }

    public record PhaseRole(String position, String role, String duty) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("position", position);
            out.put("role", role);
            out.put("duty", duty);
            return out;
        }
    }
}
