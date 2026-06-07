package com.example.fmgenie26.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class PlayerRoleAttributeCatalog {
    static final String IN_POSSESSION = "In Possession";
    static final String OUT_OF_POSSESSION = "Out of Possession";

    private static final List<RoleDefinition> ROLES = loadRoles();

    private PlayerRoleAttributeCatalog() {
    }

    static List<String> roles(String phase) {
        return ROLES.stream()
                .filter(role -> role.phase().equals(phase))
                .sorted(Comparator.comparing(RoleDefinition::positionGroup).thenComparing(RoleDefinition::name))
                .map(RoleDefinition::name)
                .distinct()
                .toList();
    }

    static Map<String, String> priorities(String phase, String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Map.of();
        }
        return ROLES.stream()
                .filter(role -> role.phase().equals(phase) && role.name().equals(roleName))
                .findFirst()
                .map(RoleDefinition::priorities)
                .orElseGet(Map::of);
    }

    private static List<RoleDefinition> loadRoles() {
        try (InputStream input = PlayerRoleAttributeCatalog.class.getResourceAsStream("/db/data/fm26_positional_roles_attributes_flat.csv")) {
            if (input == null) {
                return List.of();
            }
            return readRoles(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load FM role attribute catalog", ex);
        }
    }

    private static List<RoleDefinition> readRoles(InputStream input) throws IOException {
        Map<RoleKey, Map<String, String>> prioritiesByRole = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                return List.of();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> columns = parseCsvLine(line);
                if (columns.size() < 7) {
                    continue;
                }
                RoleKey key = new RoleKey(columns.get(1), columns.get(2), columns.get(3));
                prioritiesByRole
                        .computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .put(normalizeAttribute(columns.get(5)), columns.get(4).toLowerCase(Locale.ROOT));
            }
        }
        return prioritiesByRole.entrySet().stream()
                .map(entry -> new RoleDefinition(
                        entry.getKey().positionGroup(),
                        entry.getKey().roleName(),
                        entry.getKey().phase(),
                        entry.getValue()))
                .toList();
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static String normalizeAttribute(String attributeName) {
        String normalized = attributeName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (Set.of("aerial_reach").contains(normalized)) {
            return "aerial_ability";
        }
        if (Set.of("free_kick_taking").contains(normalized)) {
            return "free_kicks";
        }
        if (Set.of("penalty_taking").contains(normalized)) {
            return "penalties";
        }
        return normalized;
    }

    private record RoleKey(String positionGroup, String roleName, String phase) {
    }

    private record RoleDefinition(String positionGroup, String name, String phase, Map<String, String> priorities) {
    }
}
