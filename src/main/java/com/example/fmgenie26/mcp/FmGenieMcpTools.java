package com.example.fmgenie26.mcp;

import com.example.fmgenie26.club.ClubExporter;
import com.example.fmgenie26.db.ClubDatabaseService;
import com.example.fmgenie26.db.ClubEntity;
import com.example.fmgenie26.db.PlayerDatabaseService;
import com.example.fmgenie26.db.PlayerEntity;
import com.example.fmgenie26.player.AttributeDefinitions;
import com.example.fmgenie26.player.FieldDef;
import com.example.fmgenie26.ui.PositionTextFormatter;
import com.example.fmgenie26.web.mapper.PlayerMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

@Service
public class FmGenieMcpTools {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 250;
    private static final int DEFAULT_REPUTATION_MARGIN = 750;

    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final PlayerMapper playerMapper;

    public FmGenieMcpTools(PlayerDatabaseService players, ClubDatabaseService clubs, PlayerMapper playerMapper) {
        this.players = players;
        this.clubs = clubs;
        this.playerMapper = playerMapper;
    }

    @Tool(name = "fm26_find_clubs", description = "Find FM26 clubs by name, nation, competition, reputation and finances. Money values are raw pounds.")
    @Transactional(readOnly = true)
    public Map<String, Object> findClubs(
            @ToolParam(required = false, description = "Club name contains filter, for example Feyenoord") String name,
            @ToolParam(required = false, description = "Nation exact filter, for example Netherlands") String nation,
            @ToolParam(required = false, description = "Competition exact filter, for example Eredivisie") String competition,
            @ToolParam(required = false, description = "Minimum club reputation") Integer reputationMin,
            @ToolParam(required = false, description = "Maximum number of clubs to return") Integer limit) {
        int safeLimit = safeLimit(limit);
        List<Map<String, Object>> rows = allClubs().stream()
                .filter(club -> contains(club.getName(), name))
                .filter(club -> blank(nation) || equalsIgnoreCase(club.getNation(), nation))
                .filter(club -> blank(competition) || equalsIgnoreCase(club.getCompetition(), competition))
                .filter(club -> reputationMin == null || value(club.getReputation()) >= reputationMin)
                .sorted(Comparator
                        .comparing((ClubEntity club) -> value(club.getReputation())).reversed()
                        .thenComparing(ClubEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(safeLimit)
                .map(this::clubMap)
                .toList();
        return result("clubs", rows, safeLimit);
    }

    @Tool(name = "fm26_get_club_context", description = "Get a club profile, finances and squad snapshot for transfer advice. Money values are raw pounds.")
    @Transactional(readOnly = true)
    public Map<String, Object> getClubContext(
            @ToolParam(description = "Club name, for example Feyenoord") String clubName,
            @ToolParam(required = false, description = "Maximum squad players to return") Integer squadLimit) {
        ClubEntity club = requireClub(clubName);
        List<PlayerEntity> squad = squadPlayers(club.getName()).stream()
                .sorted(Comparator
                        .comparing((PlayerEntity player) -> value(player.getCa())).reversed()
                        .thenComparing(PlayerEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("club", clubMap(club));
        out.put("squad_summary", squadSummary(squad));
        out.put("squad", squad.stream()
                .limit(safeLimit(squadLimit))
                .map(this::playerSummaryMap)
                .toList());
        return out;
    }

    @Tool(name = "fm26_find_players", description = "Search FM26 players using the same data available in the UI. Money values are raw pounds.")
    @Transactional(readOnly = true)
    public Map<String, Object> findPlayers(
            @ToolParam(required = false, description = "Player name contains filter") String name,
            @ToolParam(required = false, description = "Gender exact filter: male or female") String gender,
            @ToolParam(required = false, description = "Nationality exact filter") String nationality,
            @ToolParam(required = false, description = "Playing nation exact filter") String playingNation,
            @ToolParam(required = false, description = "Playing competition exact filter") String playingCompetition,
            @ToolParam(required = false, description = "Club or playing club exact filter") String club,
            @ToolParam(required = false, description = "Minimum age") Integer ageMin,
            @ToolParam(required = false, description = "Maximum age") Integer ageMax,
            @ToolParam(required = false, description = "Minimum current ability") Integer caMin,
            @ToolParam(required = false, description = "Maximum current ability") Integer caMax,
            @ToolParam(required = false, description = "Minimum potential ability") Integer paMin,
            @ToolParam(required = false, description = "Maximum potential ability") Integer paMax,
            @ToolParam(required = false, description = "Maximum asking price in pounds") Long askingPriceMax,
            @ToolParam(required = false, description = "Maximum weekly salary in pounds") Integer salaryWeeklyMax,
            @ToolParam(required = false, description = "Minimum world reputation") Integer worldReputationMin,
            @ToolParam(required = false, description = "Maximum world reputation") Integer worldReputationMax,
            @ToolParam(required = false, description = "Maximum players to return") Integer limit) {
        int safeLimit = safeLimit(limit);
        Predicate<PlayerEntity> filter = player ->
                contains(player.getName(), name)
                        && (blank(gender) || equalsIgnoreCase(player.getGender(), gender))
                        && (blank(nationality) || equalsIgnoreCase(player.getNationality(), nationality))
                        && (blank(playingNation) || equalsIgnoreCase(playingNation(player), playingNation))
                        && (blank(playingCompetition) || equalsIgnoreCase(playingCompetition(player), playingCompetition))
                        && (blank(club) || equalsIgnoreCase(player.getClub(), club) || equalsIgnoreCase(player.getPlayingClub(), club))
                        && inRange(asInteger(player.getAge()), ageMin, ageMax)
                        && inRange(player.getCa(), caMin, caMax)
                        && inRange(player.getPa(), paMin, paMax)
                        && (askingPriceMax == null || value(player.getAskingPrice()) <= askingPriceMax)
                        && (salaryWeeklyMax == null || value(player.getSalaryWeeklyRaw()) <= salaryWeeklyMax)
                        && inRange(player.getWorldReputation(), worldReputationMin, worldReputationMax);
        List<Map<String, Object>> rows = allPlayers().stream()
                .filter(filter)
                .sorted(Comparator
                        .comparing((PlayerEntity player) -> value(player.getPa())).reversed()
                        .thenComparing((PlayerEntity player) -> value(player.getCa())).reversed()
                        .thenComparing(PlayerEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(safeLimit)
                .map(this::playerFullMap)
                .toList();
        return result("players", rows, safeLimit);
    }

    @Tool(name = "fm26_get_player_details", description = "Get full player details including attributes, positions, CA/PA, reputation, contract and club data.")
    @Transactional(readOnly = true)
    public Map<String, Object> getPlayerDetails(
            @ToolParam(description = "Player name. Exact match is preferred; contains match is used as fallback.") String name,
            @ToolParam(required = false, description = "Maximum matching players to return") Integer limit) {
        int safeLimit = safeLimit(limit);
        String normalized = normalize(name);
        List<Map<String, Object>> exact = allPlayers().stream()
                .filter(player -> normalize(player.getName()).equals(normalized))
                .sorted(Comparator.comparing(PlayerEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(safeLimit)
                .map(this::playerFullMap)
                .toList();
        List<Map<String, Object>> rows = exact.isEmpty()
                ? allPlayers().stream()
                        .filter(player -> contains(player.getName(), name))
                        .sorted(Comparator.comparing(PlayerEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                        .limit(safeLimit)
                        .map(this::playerFullMap)
                        .toList()
                : exact;
        return result("players", rows, safeLimit);
    }

    @Tool(name = "fm26_transfer_shortlist", description = "Build a transfer shortlist for a managing club using squad CA/PA, transfer budget, asking price, age, potential and a reputation-based willingness heuristic.")
    @Transactional(readOnly = true)
    public Map<String, Object> transferShortlist(
            @ToolParam(description = "Managing club name, for example Feyenoord") String managingClub,
            @ToolParam(required = false, description = "Maximum player age. Defaults to 23.") Integer maxAge,
            @ToolParam(required = false, description = "Minimum potential ability. If omitted, uses the squad average PA or 150, whichever is higher.") Integer minPotentialAbility,
            @ToolParam(required = false, description = "Maximum asking price in pounds. If omitted, uses the club transfer budget.") Long maxAskingPrice,
            @ToolParam(required = false, description = "Extra reputation above the club reputation still considered plausible. Defaults to 750.") Integer reputationMargin,
            @ToolParam(required = false, description = "Maximum candidates to return") Integer limit) {
        ClubEntity club = requireClub(managingClub);
        List<PlayerEntity> squad = squadPlayers(club.getName());
        long budget = Math.max(0L, value(club.getTransferBudget()));
        int safeMaxAge = maxAge == null ? 23 : maxAge;
        int safeReputationMargin = reputationMargin == null ? DEFAULT_REPUTATION_MARGIN : reputationMargin;
        int minPa = minPotentialAbility == null ? Math.max(150, averageInt(squad, PlayerEntity::getPa)) : minPotentialAbility;
        long priceCap = maxAskingPrice == null ? budget : Math.min(maxAskingPrice, budget);
        int maxAllowedReputation = value(club.getReputation()) + safeReputationMargin;

        List<Map<String, Object>> candidates = allPlayers().stream()
                .filter(player -> !equalsIgnoreCase(player.getClub(), club.getName()))
                .filter(player -> !equalsIgnoreCase(player.getPlayingClub(), club.getName()))
                .filter(player -> inRange(asInteger(player.getAge()), null, safeMaxAge))
                .filter(player -> value(player.getPa()) >= minPa)
                .filter(player -> value(player.getAskingPrice()) <= priceCap)
                .filter(player -> likelyWilling(player, maxAllowedReputation))
                .sorted(Comparator
                        .comparing((PlayerEntity player) -> value(player.getPa())).reversed()
                        .thenComparing(player -> value(player.getAskingPrice()))
                        .thenComparing((PlayerEntity player) -> value(player.getCa())).reversed()
                        .thenComparing(PlayerEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(safeLimit(limit))
                .map(player -> candidateMap(player, club, priceCap, maxAllowedReputation))
                .toList();

        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("max_age", safeMaxAge);
        criteria.put("min_potential_ability", minPa);
        criteria.put("max_asking_price", priceCap);
        criteria.put("club_transfer_budget", budget);
        criteria.put("max_allowed_player_reputation", maxAllowedReputation);
        criteria.put("willingness_heuristic", "player world/current/home reputation must be <= club reputation + reputation margin");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("managing_club", clubMap(club));
        out.put("criteria", criteria);
        out.put("squad_summary", squadSummary(squad));
        out.put("current_squad", squad.stream()
                .sorted(Comparator.comparing((PlayerEntity player) -> value(player.getCa())).reversed())
                .map(this::playerSummaryMap)
                .toList());
        out.put("candidates", candidates);
        out.put("candidate_count", candidates.size());
        return out;
    }

    private List<PlayerEntity> allPlayers() {
        return players.findAllPlayerEntities();
    }

    private List<ClubEntity> allClubs() {
        return clubs.findAllClubs();
    }

    private ClubEntity requireClub(String clubName) {
        return allClubs().stream()
                .filter(club -> equalsIgnoreCase(club.getName(), clubName))
                .max(Comparator.comparingInt(club -> value(club.getReputation())))
                .or(() -> allClubs().stream()
                        .filter(club -> contains(club.getName(), clubName))
                        .max(Comparator.comparingInt(club -> value(club.getReputation()))))
                .orElseThrow(() -> new IllegalArgumentException("club not found: " + clubName));
    }

    private List<PlayerEntity> squadPlayers(String clubName) {
        return allPlayers().stream()
                .filter(player -> equalsIgnoreCase(player.getClub(), clubName) || equalsIgnoreCase(player.getPlayingClub(), clubName))
                .toList();
    }

    private Map<String, Object> candidateMap(PlayerEntity player, ClubEntity managingClub, long priceCap, int maxAllowedReputation) {
        Map<String, Object> out = playerSummaryMap(player);
        out.put("potential_profit_signal", value(player.getPa()) - value(player.getCa()));
        out.put("affordable", value(player.getAskingPrice()) <= priceCap);
        out.put("likely_willing_by_reputation", likelyWilling(player, maxAllowedReputation));
        out.put("reputation_gap_to_club", highestReputation(player) - value(managingClub.getReputation()));
        out.put("reason", "PA " + value(player.getPa()) + ", age " + nullSafe(player.getAge())
                + ", asking price " + value(player.getAskingPrice()) + ", CA/PA gap "
                + (value(player.getPa()) - value(player.getCa())));
        return out;
    }

    private Map<String, Object> playerSummaryMap(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", player.getId());
        out.put("name", player.getName());
        out.put("age", player.getAge());
        out.put("gender", player.getGender());
        out.put("nationality", player.getNationality());
        out.put("club", player.getClub());
        out.put("playing_club", player.getPlayingClub());
        out.put("playing_nation", playingNation(player));
        out.put("playing_competition", playingCompetition(player));
        out.put("position_text", PositionTextFormatter.format(player));
        out.put("ca", player.getCa());
        out.put("pa", player.getPa());
        out.put("asking_price", player.getAskingPrice());
        out.put("salary_weekly_raw", player.getSalaryWeeklyRaw());
        out.put("contract_end_date", player.getContractEndDate());
        out.put("current_reputation", player.getCurrentReputation());
        out.put("home_reputation", player.getHomeReputation());
        out.put("world_reputation", player.getWorldReputation());
        out.put("height_cm", player.getHeightCm());
        return out;
    }

    private Map<String, Object> playerFullMap(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>(playerMapper.apply(player));
        out.put("POSITION_TEXT", PositionTextFormatter.format(player));
        out.put("POSITIONS", positionMap(player));
        out.put("ATTRIBUTES", attributeMap(player, AttributeDefinitions.VISIBLE_FIELDS));
        out.put("HIDDEN_ATTRIBUTES", attributeMap(player, AttributeDefinitions.HIDDEN_DIRECT_FIELDS));
        return out;
    }

    private Map<String, Object> clubMap(ClubEntity club) {
        Map<String, Object> out = new LinkedHashMap<>(club.toApiMap());
        out.put("ID", club.getId());
        out.put("NAME", club.getName());
        out.put("GENDER", club.getGender());
        out.put("COMPETITION", club.getCompetition());
        out.put("NATION", club.getNation());
        out.put("REPUTATION", club.getReputation());
        out.put("BALANCE", club.getBalance());
        out.put("TRANSFER_BUDGET", club.getTransferBudget());
        out.put("PAYROLL_BUDGET", club.getPayrollBudget());
        return out;
    }

    private Map<String, Object> squadSummary(List<PlayerEntity> squad) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player_count", squad.size());
        out.put("average_ca", averageInt(squad, PlayerEntity::getCa));
        out.put("average_pa", averageInt(squad, PlayerEntity::getPa));
        out.put("max_ca", squad.stream().map(PlayerEntity::getCa).filter(Objects::nonNull).max(Integer::compareTo).orElse(0));
        out.put("max_pa", squad.stream().map(PlayerEntity::getPa).filter(Objects::nonNull).max(Integer::compareTo).orElse(0));
        out.put("under_24_high_potential_count", squad.stream()
                .filter(player -> inRange(asInteger(player.getAge()), null, 23))
                .filter(player -> value(player.getPa()) >= 150)
                .count());
        return out;
    }

    private static Map<String, Object> result(String key, List<Map<String, Object>> rows, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("limit", limit);
        out.put(key, rows);
        return out;
    }

    private static Map<String, Object> positionMap(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDef field : AttributeDefinitions.POSITION_FIELDS) {
            String column = columnName(field);
            Object score = player.getColumnValue(column);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("score", score);
            value.put("text", PositionTextFormatter.positionLevelText(score));
            out.put(column, value);
        }
        return out;
    }

    private static Map<String, Object> attributeMap(PlayerEntity player, List<FieldDef> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDef field : fields) {
            String column = columnName(field);
            out.put(column, player.getColumnValue(column));
        }
        return out;
    }

    private static String columnName(FieldDef field) {
        String name = field.name();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                out.append('_');
            }
            out.append(Character.toUpperCase(ch));
        }
        return out.toString();
    }

    private static boolean likelyWilling(PlayerEntity player, int maxAllowedReputation) {
        return highestReputation(player) <= maxAllowedReputation;
    }

    private static int highestReputation(PlayerEntity player) {
        return Math.max(value(player.getWorldReputation()), Math.max(value(player.getCurrentReputation()), value(player.getHomeReputation())));
    }

    private static int averageInt(List<PlayerEntity> players, java.util.function.Function<PlayerEntity, Integer> value) {
        return (int) Math.round(players.stream()
                .map(value)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0));
    }

    private static String playingNation(PlayerEntity player) {
        return Optional.ofNullable(player.getPlayingClubEntity()).map(ClubEntity::getNation).orElse(null);
    }

    private static String playingCompetition(PlayerEntity player) {
        return Optional.ofNullable(player.getPlayingClubEntity()).map(ClubEntity::getCompetition).orElse(null);
    }

    private static boolean contains(String value, String needle) {
        return blank(needle) || normalize(value).contains(normalize(needle));
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return blank(right) || normalize(left).equals(normalize(right));
    }

    private static boolean inRange(Integer value, Integer min, Integer max) {
        return value != null && (min == null || value >= min) && (max == null || value <= max);
    }

    private static Integer asInteger(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int safeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ð', 'd')
                .replace('Ð', 'd')
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
