package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.service.ClubDatabaseService;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.player.AttributeDefinitions;
import com.github.fmaiassistent.player.FieldDef;
import com.github.fmaiassistent.shortlist.ShortlistFileService;
import com.github.fmaiassistent.web.ui.PositionTextFormatter;
import com.github.fmaiassistent.web.mapper.PlayerMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

@Service
public class FmAiAssistentTools {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 250;
    private static final int DEFAULT_SHORTLIST_LIMIT = 8;
    private static final int MAX_SHORTLIST_LIMIT = 30;
    private static final int DEFAULT_REPUTATION_MARGIN = 750;
    private static final int DEFAULT_MIN_POSITION_SCORE = 15;
    private static final int SOURCE_CLUB_REPUTATION_MARGIN = 1000;

    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final PlayerMapper playerMapper;
    private final JdbcTemplate jdbc;
    private final ShortlistFileService shortlistFiles;

    public FmAiAssistentTools(
            PlayerDatabaseService players,
            ClubDatabaseService clubs,
            PlayerMapper playerMapper,
            JdbcTemplate jdbc,
            ShortlistFileService shortlistFiles) {
        this.players = players;
        this.clubs = clubs;
        this.playerMapper = playerMapper;
        this.jdbc = jdbc;
        this.shortlistFiles = shortlistFiles;
    }

    @Tool(name = "fm26_find_clubs", description = "Find FM26 clubs by name, nation, competition, reputation, facilities and finances. Facility ratings use FM's 0-20 scale. Money values are raw pounds.")
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

    @Tool(name = "fm26_get_club_context", description = "Get a club profile, facility ratings, finances and squad snapshot for transfer advice. Facility ratings use FM's 0-20 scale. Money values are raw pounds.")
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
            @ToolParam(required = false, description = "Transfer-listed filter. Use true for only transfer-listed players, false for only players not transfer-listed.") Boolean transferListed,
            @ToolParam(required = false, description = "Listed-for-loan filter. Use true for only loan-listed players, false for only players not listed for loan.") Boolean listedForLoan,
            @ToolParam(required = false, description = "Transfer-agreed filter. Use true for players who already agreed a future move, false to exclude them.") Boolean transferAgreed,
            @ToolParam(required = false, description = "Future transfer destination club exact filter.") String futureTransferClub,
            @ToolParam(required = false, description = "Injury filter. Use true for only injured players, false for only currently fit players.") Boolean injured,
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
                        && inRange(player.getWorldReputation(), worldReputationMin, worldReputationMax)
                        && matchesBoolean(player.getTransferListed(), transferListed)
                        && matchesBoolean(player.getListedForLoan(), listedForLoan)
                        && matchesBoolean(player.getTransferAgreed(), transferAgreed)
                        && (blank(futureTransferClub) || equalsIgnoreCase(player.getFutureTransferClub(), futureTransferClub))
                        && matchesBoolean(player.getInjured(), injured);
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

    @Tool(
            name = "fm26_create_shortlist_file",
            description = "Create an importable FM26 player-shortlist .fmf file after recruitment candidates have been selected. Pass the UNIQUE_ID/player_unique_id values returned by the player and recruitment tools. This creates a shortlist, not a tactic.")
    public Map<String, Object> createShortlistFile(
            @ToolParam(description = "Name shown for the shortlist in FM26 and used for the output filename") String shortlistName,
            @ToolParam(description = "FM26 player UNIQUE_ID values in the desired shortlist order") List<Long> playerUniqueIds) {
        return shortlistFiles.create(shortlistName, playerUniqueIds).toMap();
    }

    @Tool(name = "fm26_get_role_attributes", description = "Get FM26 positional roles and the primary/secondary attributes that matter for each role. Use this for tactical fit and transfer advice.")
    @Transactional(readOnly = true)
    public Map<String, Object> getRoleAttributes(
            @ToolParam(required = false, description = "Phase exact filter: In Possession or Out of Possession") String phase,
            @ToolParam(required = false, description = "Position group exact filter, for example Striker, Goalkeeper, Defender Central, Midfielder Central") String positionGroup,
            @ToolParam(required = false, description = "Role name contains filter, for example Advanced Forward, Ball-Playing Centre-Back, Goalkeeper") String roleName,
            @ToolParam(required = false, description = "Maximum roles to return") Integer limit) {
        int safeLimit = safeLimit(limit);
        List<RoleAttributeRow> rows = roleAttributeRows();

        Map<RoleKey, RoleBucket> grouped = new LinkedHashMap<>();
        rows.stream()
                .filter(row -> blank(phase) || equalsIgnoreCase(row.phase(), phase))
                .filter(row -> blank(positionGroup) || equalsIgnoreCase(row.positionGroup(), positionGroup))
                .filter(row -> contains(row.roleName(), roleName))
                .forEach(row -> grouped
                        .computeIfAbsent(new RoleKey(row.game(), row.positionGroup(), row.roleName(), row.phase()), RoleBucket::new)
                        .add(row));

        List<Map<String, Object>> roles = grouped.values().stream()
                .limit(safeLimit)
                .map(RoleBucket::toMap)
                .toList();

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("phase", phase);
        filters.put("position_group", positionGroup);
        filters.put("role_name", roleName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", roles.size());
        out.put("limit", safeLimit);
        out.put("filters", filters);
        out.put("usage", "Compare a player's ATTRIBUTES from fm26_get_player_details with primary_attributes and secondary_attributes. Primary attributes matter most for the role.");
        out.put("roles", roles);
        return out;
    }

    @Tool(name = "fm26_transfer_shortlist", description = "Primary recruitment tool. Returns a compact ranked shortlist using squad strength, position and role fit, CA/PA, price, wage, reputation, source club, availability and time at club. Call this before broad player searches or player details.")
    @Transactional(readOnly = true)
    public Map<String, Object> transferShortlist(
            @ToolParam(description = "Managing club name, for example Feyenoord") String managingClub,
            @ToolParam(required = false, description = "Position: GK, DL, DC, DR, WBL, DMC, WBR, ML, MC, MR, AML, AMC, AMR or ST. Full names also work.") String position,
            @ToolParam(required = false, description = "Optional FM26 role name for attribute fit, for example Ball-Playing Centre-Back.") String roleName,
            @ToolParam(required = false, description = "Role phase: In Possession or Out of Possession. Omit to combine matching phases.") String phase,
            @ToolParam(required = false, description = "Minimum position ability 1-20. Defaults to 15 when position is supplied.") Integer minimumPositionScore,
            @ToolParam(required = false, description = "Maximum player age. No limit when omitted.") Integer maxAge,
            @ToolParam(required = false, description = "Minimum current ability. No minimum when omitted.") Integer minCurrentAbility,
            @ToolParam(required = false, description = "Minimum potential ability. No minimum when omitted.") Integer minPotentialAbility,
            @ToolParam(required = false, description = "Maximum asking price in pounds. If omitted, uses the club transfer budget.") Long maxAskingPrice,
            @ToolParam(required = false, description = "Maximum current weekly salary in pounds. Omit to score wage fit without excluding players.") Integer maxWeeklySalary,
            @ToolParam(required = false, description = "Extra player reputation above club reputation considered plausible. Defaults to 750.") Integer reputationMargin,
            @ToolParam(required = false, description = "Minimum time at current club. ISO-8601 period like P1Y or plain days like 365. Defaults to P1Y.") String minimumTimeAtCurrentClub,
            @ToolParam(required = false, description = "Transfer-listed filter. true=only listed, false=exclude listed.") Boolean transferListed,
            @ToolParam(required = false, description = "Loan-listed filter. true=only loan-listed, false=exclude loan-listed.") Boolean listedForLoan,
            @ToolParam(required = false, description = "Transfer-agreed filter. Defaults to false because agreed players are unavailable.") Boolean transferAgreed,
            @ToolParam(required = false, description = "Injury filter. false=fit only, true=injured only, omit=both.") Boolean injured,
            @ToolParam(required = false, description = "Maximum candidates. Defaults to 8, maximum 30.") Integer limit) {
        ClubEntity club = requireClub(managingClub);
        PositionSpec positionSpec = resolvePosition(position);
        if (!blank(roleName) && positionSpec == null) {
            throw new IllegalArgumentException("position is required when roleName is supplied");
        }
        int positionMinimum = positionSpec == null
                ? 1
                : Math.max(1, Math.min(20, minimumPositionScore == null ? DEFAULT_MIN_POSITION_SCORE : minimumPositionScore));
        RoleProfile roleProfile = resolveRoleProfile(positionSpec, roleName, phase);

        List<PlayerEntity> allPlayers = allPlayers();
        Map<String, ClubEntity> clubsByName = clubsByName(allClubs());
        List<PlayerEntity> squad = currentSquad(allPlayers, club.getName());
        List<PlayerEntity> positionSquad = positionSpec == null
                ? squad
                : squad.stream().filter(player -> positionScore(player, positionSpec) >= positionMinimum).toList();

        long budget = Math.max(0L, value(club.getTransferBudget()));
        int safeReputationMargin = reputationMargin == null ? DEFAULT_REPUTATION_MARGIN : Math.max(0, reputationMargin);
        Period minimumTime = parsePeriod(minimumTimeAtCurrentClub, Period.ofYears(1));
        long priceCap = maxAskingPrice == null ? budget : Math.min(Math.max(0L, maxAskingPrice), budget);
        long wageCeiling = maxWeeklySalary == null
                ? inferredWeeklyWageCeiling(squad, club)
                : Math.max(0L, maxWeeklySalary);
        int benchmarkCa = firstTeamAverageCa(positionSquad);
        int shortlistLimit = limit == null ? DEFAULT_SHORTLIST_LIMIT : Math.max(1, Math.min(limit, MAX_SHORTLIST_LIMIT));
        Boolean effectiveTransferAgreed = transferAgreed == null ? Boolean.FALSE : transferAgreed;

        List<ScoredCandidate> candidatePool = new ArrayList<>();
        for (PlayerEntity player : allPlayers) {
            if (belongsToClub(player, club.getName())
                    || !sameGender(player.getGender(), club.getGender())
                    || !inRange(asInteger(player.getAge()), null, maxAge)
                    || !inRange(player.getCa(), minCurrentAbility, null)
                    || !inRange(player.getPa(), minPotentialAbility, null)
                    || !matchesBoolean(player.getTransferListed(), transferListed)
                    || !matchesBoolean(player.getListedForLoan(), listedForLoan)
                    || !matchesBoolean(player.getTransferAgreed(), effectiveTransferAgreed)
                    || !matchesBoolean(player.getInjured(), injured)) {
                continue;
            }

            int candidatePositionScore = positionSpec == null ? bestPositionScore(player) : positionScore(player, positionSpec);
            if (candidatePositionScore < positionMinimum) {
                continue;
            }
            long askingPrice = value(player.getAskingPrice());
            boolean priceKnown = askingPrice > 0;
            boolean freeAgent = blank(player.getClub());
            if (priceKnown && askingPrice > priceCap) {
                continue;
            }
            if (maxWeeklySalary != null && value(player.getSalaryWeeklyRaw()) > maxWeeklySalary) {
                continue;
            }

            ClubEntity sourceClub = clubsByName.get(normalize(player.getClub()));
            Willingness willingness = willingness(player, club, sourceClub, safeReputationMargin, minimumTime);
            if (willingness == Willingness.LOW) {
                continue;
            }

            RoleFit roleFit = roleFit(player, roleProfile);
            double score = decisionScore(
                    player,
                    candidatePositionScore,
                    benchmarkCa,
                    priceKnown,
                    freeAgent,
                    priceCap,
                    wageCeiling,
                    willingness,
                    roleFit);
            candidatePool.add(new ScoredCandidate(
                    player,
                    sourceClub,
                    candidatePositionScore,
                    roleFit,
                    willingness,
                    priceKnown,
                    freeAgent,
                    score));
        }

        candidatePool.sort(Comparator
                .comparingDouble(ScoredCandidate::decisionScore).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ScoredCandidate candidate) -> value(candidate.player().getPa())).reversed())
                .thenComparing(Comparator.comparingInt(
                        (ScoredCandidate candidate) -> value(candidate.player().getCa())).reversed())
                .thenComparing(candidate -> candidate.player().getName(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int index = 0; index < Math.min(shortlistLimit, candidatePool.size()); index++) {
            candidates.add(recommendationMap(
                    index + 1,
                    candidatePool.get(index),
                    club,
                    benchmarkCa,
                    priceCap,
                    wageCeiling));
        }

        Map<String, Object> criteria = new LinkedHashMap<>();
        putIfNotNull(criteria, "position", positionSpec == null ? null : positionSpec.code());
        putIfNotNull(criteria, "minimum_position_score", positionSpec == null ? null : positionMinimum);
        putIfNotNull(criteria, "role", blank(roleName) ? null : roleName);
        putIfNotNull(criteria, "phase", blank(phase) ? null : phase);
        putIfNotNull(criteria, "max_age", maxAge);
        putIfNotNull(criteria, "min_ca", minCurrentAbility);
        putIfNotNull(criteria, "min_pa", minPotentialAbility);
        criteria.put("max_asking_price", priceCap);
        criteria.put("weekly_wage_ceiling", wageCeiling);
        criteria.put("reputation_margin", safeReputationMargin);
        criteria.put("minimum_time_at_current_club", minimumTime.toString());
        putIfNotNull(criteria, "transfer_listed", transferListed);
        putIfNotNull(criteria, "listed_for_loan", listedForLoan);
        criteria.put("transfer_agreed", effectiveTransferAgreed);
        putIfNotNull(criteria, "injured", injured);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("club", recruitmentClubMap(club));
        out.put("criteria", criteria);
        out.put("position_benchmark", positionBenchmark(positionSquad, positionSpec));
        if (!roleProfile.isEmpty()) {
            out.put("role_model", roleProfile.toMap());
        }
        out.put("candidate_pool_count", candidatePool.size());
        out.put("returned", candidates.size());
        out.put("candidates", candidates);
        out.put("guidance", "Ranked estimates, not guaranteed transfers. asking_price=null means unknown, not free. Call fm26_get_player_details only for finalists needing full attributes.");
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

    private static Map<String, ClubEntity> clubsByName(List<ClubEntity> clubs) {
        Map<String, ClubEntity> out = new HashMap<>();
        for (ClubEntity club : clubs) {
            if (blank(club.getName())) {
                continue;
            }
            out.merge(normalize(club.getName()), club, (left, right) ->
                    value(right.getReputation()) > value(left.getReputation()) ? right : left);
        }
        return out;
    }

    private static List<PlayerEntity> currentSquad(List<PlayerEntity> players, String clubName) {
        return players.stream()
                .filter(player -> equalsIgnoreCase(player.getPlayingClub(), clubName)
                        || (blank(player.getPlayingClub()) && equalsIgnoreCase(player.getClub(), clubName)))
                .toList();
    }

    private static boolean belongsToClub(PlayerEntity player, String clubName) {
        return equalsIgnoreCase(player.getClub(), clubName) || equalsIgnoreCase(player.getPlayingClub(), clubName);
    }

    private static boolean sameGender(String playerGender, String clubGender) {
        return blank(playerGender) || blank(clubGender) || normalize(playerGender).equals(normalize(clubGender));
    }

    private static PositionSpec resolvePosition(String position) {
        if (blank(position)) {
            return null;
        }
        String key = normalize(position).replaceAll("[^a-z0-9]+", "");
        return switch (key) {
            case "gk", "goalkeeper" -> new PositionSpec("GK", "GOALKEEPER", "Goalkeeper");
            case "dl", "defenderleft", "leftback", "lb" -> new PositionSpec("DL", "DEFENDER_LEFT", "Full-Back / Wing-Back");
            case "dc", "cb", "defendercentral", "centraldefender", "centreback", "centerback" -> new PositionSpec("DC", "DEFENDER_CENTRAL", "Centre-Back");
            case "dr", "defenderright", "rightback", "rb" -> new PositionSpec("DR", "DEFENDER_RIGHT", "Full-Back / Wing-Back");
            case "wbl", "wingbackleft", "leftwingback", "lwb" -> new PositionSpec("WBL", "WING_BACK_LEFT", "Full-Back / Wing-Back");
            case "dmc", "dm", "defensivemidfielder" -> new PositionSpec("DMC", "DEFENSIVE_MIDFIELDER", "Defensive Midfielder");
            case "wbr", "wingbackright", "rightwingback", "rwb" -> new PositionSpec("WBR", "WING_BACK_RIGHT", "Full-Back / Wing-Back");
            case "ml", "midfielderleft", "leftmidfielder", "lm" -> new PositionSpec("ML", "MIDFIELDER_LEFT", "Wide Midfielder / Winger");
            case "mc", "cm", "midfieldercentral", "centralmidfielder" -> new PositionSpec("MC", "MIDFIELDER_CENTRAL", "Central Midfielder");
            case "mr", "midfielderright", "rightmidfielder", "rm" -> new PositionSpec("MR", "MIDFIELDER_RIGHT", "Wide Midfielder / Winger");
            case "aml", "attackingmidfielderleft", "leftwinger", "lw" -> new PositionSpec("AML", "ATTACKING_MIDFIELDER_LEFT", "Wide Midfielder / Winger");
            case "amc", "am", "attackingmidfieldercentral", "attackingmidfielder" -> new PositionSpec("AMC", "ATTACKING_MIDFIELDER_CENTRAL", "Attacking Midfielder");
            case "amr", "attackingmidfielderright", "rightwinger", "rw" -> new PositionSpec("AMR", "ATTACKING_MIDFIELDER_RIGHT", "Wide Midfielder / Winger");
            case "st", "striker", "forward", "cf", "centreforward", "centerforward" -> new PositionSpec("ST", "STRIKER", "Striker");
            default -> throw new IllegalArgumentException("unsupported position: " + position
                    + ". Use GK, DL, DC, DR, WBL, DMC, WBR, ML, MC, MR, AML, AMC, AMR or ST");
        };
    }

    private RoleProfile resolveRoleProfile(PositionSpec position, String roleName, String phase) {
        if (blank(roleName)) {
            return RoleProfile.empty();
        }
        List<RoleAttributeRow> positionRows = roleAttributeRows().stream()
                .filter(row -> position == null || equalsIgnoreCase(row.positionGroup(), position.positionGroup()))
                .filter(row -> blank(phase) || equalsIgnoreCase(row.phase(), phase))
                .toList();
        List<RoleAttributeRow> exactRows = positionRows.stream()
                .filter(row -> equalsIgnoreCase(row.roleName(), roleName))
                .toList();
        List<RoleAttributeRow> rows = exactRows.isEmpty()
                ? positionRows.stream().filter(row -> contains(row.roleName(), roleName)).toList()
                : exactRows;
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("role not found for " + position.positionGroup() + ": " + roleName);
        }

        Map<String, Integer> weights = new LinkedHashMap<>();
        List<String> roles = new ArrayList<>();
        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        for (RoleAttributeRow row : rows) {
            String role = row.roleName() + " (" + row.phase() + ")";
            if (!roles.contains(role)) {
                roles.add(role);
            }
            String attribute = playerAttributeKey(row.attributeName());
            int weight = "primary".equalsIgnoreCase(row.attributePriority()) ? 2 : 1;
            weights.merge(attribute, weight, Math::max);
            List<String> target = weight == 2 ? primary : secondary;
            if (!target.contains(attribute)) {
                target.add(attribute);
            }
        }
        secondary.removeAll(primary);
        return new RoleProfile(roles, primary, secondary, weights);
    }

    private List<RoleAttributeRow> roleAttributeRows() {
        return jdbc.query("""
                        SELECT r.game, r.position_group, r.role_name, r.phase,
                               ra.attribute_priority, a.attribute_name, ra.sort_order
                        FROM fm_role r
                        JOIN fm_role_attribute ra ON ra.role_id = r.id
                        JOIN fm_attribute a ON a.id = ra.attribute_id
                        ORDER BY r.position_group, r.role_name, r.phase,
                                 CASE ra.attribute_priority WHEN 'primary' THEN 0 ELSE 1 END,
                                 ra.sort_order, a.attribute_name
                        """,
                (rs, rowNum) -> new RoleAttributeRow(
                        rs.getString("game"),
                        rs.getString("position_group"),
                        rs.getString("role_name"),
                        rs.getString("phase"),
                        rs.getString("attribute_priority"),
                        rs.getString("attribute_name"),
                        rs.getInt("sort_order")));
    }

    private static int positionScore(PlayerEntity player, PositionSpec position) {
        Object score = player.getColumnValue(position.column());
        return score instanceof Number number ? number.intValue() : 0;
    }

    private static int bestPositionScore(PlayerEntity player) {
        return AttributeDefinitions.POSITION_FIELDS.stream()
                .map(FmAiAssistentTools::columnName)
                .map(player::getColumnValue)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .max()
                .orElse(0);
    }

    private static long inferredWeeklyWageCeiling(List<PlayerEntity> squad, ClubEntity club) {
        long currentMaximum = squad.stream().map(PlayerEntity::getSalaryWeeklyRaw).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).max().orElse(0L);
        long squadStructure = Math.round(currentMaximum * 1.25);
        long payrollStructure = squad.isEmpty() ? 0L : value(club.getPayrollBudget()) / squad.size() * 2L;
        return Math.max(squadStructure, payrollStructure);
    }

    private static Willingness willingness(
            PlayerEntity player,
            ClubEntity managingClub,
            ClubEntity sourceClub,
            int reputationMargin,
            Period minimumTimeAtCurrentClub) {
        int managingReputation = value(managingClub.getReputation());
        int playerGap = highestReputation(player) - managingReputation;
        int sourceGap = sourceClub == null ? 0 : value(sourceClub.getReputation()) - managingReputation;
        boolean listed = Boolean.TRUE.equals(player.getTransferListed()) || Boolean.TRUE.equals(player.getListedForLoan());
        boolean recentlyJoined = recentlyJoinedCurrentClub(player, minimumTimeAtCurrentClub);

        if (playerGap > reputationMargin + (listed ? 500 : 0)
                || (sourceGap > SOURCE_CLUB_REPUTATION_MARGIN && !listed)
                || (recentlyJoined && !listed)) {
            return Willingness.LOW;
        }
        if (playerGap > 0 || sourceGap > 250 || recentlyJoined) {
            return Willingness.MEDIUM;
        }
        return Willingness.HIGH;
    }

    private static RoleFit roleFit(PlayerEntity player, RoleProfile role) {
        if (role.isEmpty()) {
            return RoleFit.empty();
        }
        List<AttributeScore> scores = role.weights().entrySet().stream()
                .map(entry -> new AttributeScore(
                        entry.getKey(),
                        attributeScore(player, entry.getKey()),
                        entry.getValue()))
                .toList();
        int weightTotal = scores.stream().mapToInt(AttributeScore::weight).sum();
        double weightedTotal = scores.stream().mapToDouble(score -> score.score() * score.weight()).sum();
        Double fit = weightTotal == 0 ? null : round1(weightedTotal / weightTotal);
        List<String> strengths = scores.stream()
                .sorted(Comparator.comparingInt(AttributeScore::score).reversed().thenComparing(AttributeScore::name))
                .limit(3)
                .map(AttributeScore::compact)
                .toList();
        List<String> gaps = scores.stream()
                .sorted(Comparator.comparingInt(AttributeScore::score).thenComparing(AttributeScore::name))
                .limit(3)
                .map(AttributeScore::compact)
                .toList();
        return new RoleFit(fit, strengths, gaps);
    }

    private static int attributeScore(PlayerEntity player, String attribute) {
        Object value = player.getColumnValue(attribute.toUpperCase(Locale.ROOT));
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double decisionScore(
            PlayerEntity player,
            int positionScore,
            int benchmarkCa,
            boolean priceKnown,
            boolean freeAgent,
            long priceCap,
            long wageCeiling,
            Willingness willingness,
            RoleFit roleFit) {
        double position = clamp(positionScore / 20.0);
        double ca = clamp(value(player.getCa()) / 200.0);
        double futureQuality = clamp(effectivePotential(player) / 200.0);
        double improvement = benchmarkCa <= 0 ? 1.0 : clamp((value(player.getCa()) - benchmarkCa + 25.0) / 50.0);
        double growth = clamp((effectivePotential(player) - value(player.getCa())) / 50.0);
        double age = ageScore(player);
        double price = freeAgent ? 1.0 : !priceKnown ? 0.35 : priceCap <= 0
                ? 0.0
                : clamp(1.0 - value(player.getAskingPrice()) / (double) priceCap);
        double wage = value(player.getSalaryWeeklyRaw()) == 0 ? 1.0 : wageCeiling <= 0
                ? 0.35
                : clamp(wageCeiling / (double) value(player.getSalaryWeeklyRaw()));
        double willingnessScore = willingness == Willingness.HIGH ? 1.0 : 0.6;

        if (roleFit.score() != null) {
            return round1(position * 15 + ca * 20 + futureQuality * 10 + improvement * 15 + growth * 5 + age * 5
                    + clamp(roleFit.score() / 20.0) * 20 + ((price + wage) / 2.0) * 5 + willingnessScore * 5);
        }
        return round1(position * 20 + ca * 25 + futureQuality * 10 + improvement * 15 + growth * 10 + age * 10
                + ((price + wage) / 2.0) * 5 + willingnessScore * 5);
    }

    private static Map<String, Object> recommendationMap(
            int rank,
            ScoredCandidate candidate,
            ClubEntity managingClub,
            int benchmarkCa,
            long priceCap,
            long wageCeiling) {
        PlayerEntity player = candidate.player();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rank", rank);
        out.put("score", candidate.decisionScore());
        out.put("player_unique_id", player.getUniqueId());
        out.put("name", player.getName());
        out.put("age", asInteger(player.getAge()));
        out.put("nationality", player.getNationality());
        out.put("club", player.getClub());
        out.put("position_score", candidate.positionScore());
        out.put("ca", player.getCa());
        out.put("pa", player.getPa());
        out.put("development_upside", effectivePotential(player) - value(player.getCa()));
        out.put("age_curve", ageCurve(player));
        out.put("ca_vs_squad_position_avg", value(player.getCa()) - benchmarkCa);
        if (candidate.roleFit().score() != null) {
            out.put("role_fit", candidate.roleFit().score());
            out.put("role_strengths", candidate.roleFit().strengths());
            out.put("role_gaps", candidate.roleFit().gaps());
        }
        out.put("asking_price", candidate.priceKnown() ? player.getAskingPrice() : null);
        out.put("price_fit", candidate.freeAgent() ? "free_agent" : !candidate.priceKnown() ? "unknown"
                : value(player.getAskingPrice()) <= priceCap ? "within_budget" : "over_budget");
        out.put("salary_weekly", player.getSalaryWeeklyRaw());
        out.put("wage_fit", value(player.getSalaryWeeklyRaw()) <= wageCeiling);
        out.put("willingness", candidate.willingness().name().toLowerCase(Locale.ROOT));
        out.put("player_reputation", highestReputation(player));
        out.put("reputation_gap", highestReputation(player) - value(managingClub.getReputation()));
        out.put("source_club_reputation", candidate.sourceClub() == null ? null : candidate.sourceClub().getReputation());
        out.put("joined_club", player.getJoinedClubDate());
        out.put("contract_end", player.getContractEndDate());
        out.put("transfer_listed", player.getTransferListed());
        out.put("loan_listed", player.getListedForLoan());
        out.put("injured", player.getInjured());
        out.put("signals", candidateSignals(candidate, benchmarkCa));
        return out;
    }

    private static List<String> candidateSignals(ScoredCandidate candidate, int benchmarkCa) {
        PlayerEntity player = candidate.player();
        List<String> signals = new ArrayList<>();
        signals.add(candidate.positionScore() >= 18 ? "natural_position" : "accomplished_position");
        int caDifference = value(player.getCa()) - benchmarkCa;
        if (benchmarkCa > 0) {
            signals.add("ca_vs_squad:" + signed(caDifference));
        }
        signals.add("development_upside:" + signed(effectivePotential(player) - value(player.getCa())));
        signals.add(candidate.freeAgent() ? "free_agent" : candidate.priceKnown() ? "price_known" : "price_unknown");
        if (Boolean.TRUE.equals(player.getTransferListed())) {
            signals.add("transfer_listed");
        }
        if (Boolean.TRUE.equals(player.getListedForLoan())) {
            signals.add("loan_listed");
        }
        if (Boolean.TRUE.equals(player.getInjured())) {
            signals.add("injured");
        }
        return signals;
    }

    private static Map<String, Object> recruitmentClubMap(ClubEntity club) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", club.getName());
        out.put("competition", club.getCompetition());
        out.put("nation", club.getNation());
        out.put("gender", club.getGender());
        out.put("reputation", club.getReputation());
        out.put("balance", club.getBalance());
        out.put("transfer_budget", club.getTransferBudget());
        out.put("payroll_budget_weekly", club.getPayrollBudget());
        return out;
    }

    private static Map<String, Object> positionBenchmark(List<PlayerEntity> squad, PositionSpec position) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("position", position == null ? "any" : position.code());
        out.put("players", squad.size());
        out.put("average_ca", averageInt(squad, PlayerEntity::getCa));
        out.put("first_team_average_ca", firstTeamAverageCa(squad));
        out.put("best_ca", squad.stream().map(PlayerEntity::getCa).filter(Objects::nonNull).max(Integer::compareTo).orElse(0));
        out.put("current_options", squad.stream()
                .sorted(Comparator.comparing((PlayerEntity player) -> value(player.getCa())).reversed())
                .limit(6)
                .map(player -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", player.getName());
                    row.put("age", asInteger(player.getAge()));
                    row.put("ca", player.getCa());
                    row.put("pa", player.getPa());
                    if (position != null) {
                        row.put("position_score", positionScore(player, position));
                    }
                    row.put("salary_weekly", player.getSalaryWeeklyRaw());
                    return row;
                })
                .toList());
        return out;
    }

    private static int firstTeamAverageCa(List<PlayerEntity> squad) {
        return (int) Math.round(squad.stream()
                .map(PlayerEntity::getCa)
                .filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder())
                .limit(5)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0));
    }

    private static int effectivePotential(PlayerEntity player) {
        int ca = value(player.getCa());
        int availableGrowth = Math.max(0, value(player.getPa()) - ca);
        return ca + (int) Math.round(availableGrowth * developmentFactor(player));
    }

    private static double developmentFactor(PlayerEntity player) {
        int age = Optional.ofNullable(asInteger(player.getAge())).orElse(40);
        if (age <= 21) {
            return 1.0;
        }
        if (age == 22) {
            return 0.9;
        }
        if (age == 23) {
            return 0.8;
        }
        if (age == 24) {
            return 0.65;
        }
        if (age == 25) {
            return 0.5;
        }
        if (age == 26) {
            return 0.35;
        }
        if (age == 27) {
            return 0.2;
        }
        if (age == 28) {
            return 0.1;
        }
        return 0.0;
    }

    private static double ageScore(PlayerEntity player) {
        int age = Optional.ofNullable(asInteger(player.getAge())).orElse(40);
        if (age <= 20) {
            return 1.0;
        }
        if (age <= 23) {
            return 0.95;
        }
        if (age <= 26) {
            return 0.85;
        }
        if (age <= 29) {
            return 0.7;
        }
        if (age <= 32) {
            return 0.5;
        }
        if (age <= 35) {
            return 0.25;
        }
        return 0.05;
    }

    private static String ageCurve(PlayerEntity player) {
        int age = Optional.ofNullable(asInteger(player.getAge())).orElse(40);
        if (age <= 23) {
            return "developing";
        }
        if (age <= 29) {
            return "prime";
        }
        if (age <= 33) {
            return "late_prime";
        }
        return "veteran";
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    private Map<String, Object> playerSummaryMap(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", player.getId());
        out.put("unique_id", player.getUniqueId());
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
        out.put("joined_club_date", player.getJoinedClubDate());
        out.put("transfer_listed", player.getTransferListed());
        out.put("listed_for_loan", player.getListedForLoan());
        out.put("transfer_agreed", player.getTransferAgreed());
        out.put("future_transfer_club", player.getFutureTransferClub());
        out.put("future_transfer_date", player.getFutureTransferDate());
        out.put("future_transfer_contract_end_date", player.getFutureTransferContractEndDate());
        out.put("injured", player.getInjured());
        out.put("injury", player.getInjury());
        out.put("injury_start_date", player.getInjuryStartDate());
        out.put("injury_light_training_days_remaining", player.getInjuryLightTrainingDaysRemaining());
        out.put("injury_full_training_days_remaining", player.getInjuryFullTrainingDaysRemaining());
        out.put("injury_min_days_remaining", player.getInjuryMinDaysRemaining());
        out.put("injury_max_days_remaining", player.getInjuryMaxDaysRemaining());
        out.put("injury_expected_return", player.getInjuryExpectedReturn());
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
        out.put("TRAINING_FACILITIES", club.getTrainingFacilities());
        out.put("YOUTH_FACILITIES", club.getYouthFacilities());
        out.put("YOUTH_COACHING", club.getYouthCoaching());
        out.put("YOUTH_RECRUITMENT", club.getYouthRecruitment());
        out.put("CORPORATE_FACILITIES", club.getCorporateFacilities());
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

    private static String playerAttributeKey(String attributeName) {
        String normalized = attributeName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return switch (normalized) {
            case "aerial_reach" -> "aerial_ability";
            case "jumping" -> "jumping_reach";
            case "team_work" -> "teamwork";
            case "free_kick_taking" -> "free_kicks";
            case "penalty_taking" -> "penalties";
            default -> normalized;
        };
    }

    private static String playerAttributeColumn(String attributeName) {
        return playerAttributeKey(attributeName).toUpperCase(Locale.ROOT);
    }

    private static boolean recentlyJoinedCurrentClub(PlayerEntity player, Period minimumTimeAtCurrentClub) {
        LocalDate joined = parseDate(player.getJoinedClubDate());
        LocalDate gameDate = parseDate(player.getAgeAsOf());
        if (joined == null || gameDate == null) {
            return false;
        }
        return joined.isAfter(gameDate.minus(minimumTimeAtCurrentClub));
    }

    private static Period parsePeriod(String value, Period defaultValue) {
        if (blank(value)) {
            return defaultValue;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        try {
            if (trimmed.chars().allMatch(Character::isDigit)) {
                return Period.ofDays(Integer.parseInt(trimmed));
            }
            if (trimmed.endsWith("D") && trimmed.substring(0, trimmed.length() - 1).chars().allMatch(Character::isDigit)) {
                return Period.ofDays(Integer.parseInt(trimmed.substring(0, trimmed.length() - 1)));
            }
            if (!trimmed.startsWith("P")) {
                trimmed = "P" + trimmed;
            }
            return Period.parse(trimmed);
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static LocalDate parseDate(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
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

    private static boolean matchesBoolean(Boolean value, Boolean expected) {
        return expected == null || Objects.equals(Boolean.TRUE.equals(value), expected);
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

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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

    private record PositionSpec(String code, String column, String positionGroup) {
    }

    private record RoleProfile(
            List<String> roles,
            List<String> primary,
            List<String> secondary,
            Map<String, Integer> weights) {

        private static RoleProfile empty() {
            return new RoleProfile(List.of(), List.of(), List.of(), Map.of());
        }

        private boolean isEmpty() {
            return weights.isEmpty();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("roles", roles);
            out.put("primary", primary);
            out.put("secondary", secondary);
            return out;
        }
    }

    private record RoleFit(Double score, List<String> strengths, List<String> gaps) {
        private static RoleFit empty() {
            return new RoleFit(null, List.of(), List.of());
        }
    }

    private record AttributeScore(String name, int score, int weight) {
        private String compact() {
            return name + ":" + score;
        }
    }

    private record ScoredCandidate(
            PlayerEntity player,
            ClubEntity sourceClub,
            int positionScore,
            RoleFit roleFit,
            Willingness willingness,
            boolean priceKnown,
            boolean freeAgent,
            double decisionScore) {
    }

    private enum Willingness {
        HIGH,
        MEDIUM,
        LOW
    }

    private record RoleAttributeRow(
            String game,
            String positionGroup,
            String roleName,
            String phase,
            String attributePriority,
            String attributeName,
            int sortOrder) {
    }

    private record RoleKey(String game, String positionGroup, String roleName, String phase) {
    }

    private static final class RoleBucket {
        private final RoleKey key;
        private final List<Map<String, Object>> primaryAttributes = new java.util.ArrayList<>();
        private final List<Map<String, Object>> secondaryAttributes = new java.util.ArrayList<>();

        private RoleBucket(RoleKey key) {
            this.key = key;
        }

        private void add(RoleAttributeRow row) {
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("name", row.attributeName());
            attribute.put("player_attribute_key", playerAttributeKey(row.attributeName()));
            attribute.put("player_attribute_column", playerAttributeColumn(row.attributeName()));
            attribute.put("sort_order", row.sortOrder());
            if ("primary".equalsIgnoreCase(row.attributePriority())) {
                primaryAttributes.add(attribute);
            } else {
                secondaryAttributes.add(attribute);
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("game", key.game());
            out.put("position_group", key.positionGroup());
            out.put("role_name", key.roleName());
            out.put("phase", key.phase());
            out.put("primary_attributes", primaryAttributes);
            out.put("secondary_attributes", secondaryAttributes);
            return out;
        }
    }
}
