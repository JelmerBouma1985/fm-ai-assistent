package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.FixtureEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.player.PositionTextFormatter;
import com.github.fmaiassistent.repository.ClubRepository;
import com.github.fmaiassistent.repository.FixtureRepository;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.snapshot.SnapshotStatusService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
public class FmFixtureTools {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final FixtureRepository fixtures;
    private final ClubRepository clubs;
    private final PlayerDatabaseService players;
    private final SnapshotStatusService snapshots;

    public FmFixtureTools(
            FixtureRepository fixtures,
            ClubRepository clubs,
            PlayerDatabaseService players,
            SnapshotStatusService snapshots) {
        this.fixtures = fixtures;
        this.clubs = clubs;
        this.players = players;
        this.snapshots = snapshots;
    }

    @Tool(name = "fm26_get_managed_club_fixtures", description = "Get fixtures read from FM26 RAM for the human-managed club, ordered by kickoff. Defaults to upcoming fixtures from the loaded in-game date.")
    @Transactional(readOnly = true)
    public Map<String, Object> getManagedClubFixtures(
            @ToolParam(required = false, description = "Include fixtures before the loaded game date. Defaults to false.") Boolean includePast,
            @ToolParam(required = false, description = "Maximum fixtures to return. Defaults to 20, maximum 100.") Integer limit) {
        Map<String, Object> snapshot = snapshots.reference();
        LocalDate gameDate = date(snapshot.get("game_date"));
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit == null ? DEFAULT_LIMIT : limit));
        List<FixtureEntity> selected = fixtures.findAllByOrderByKickoffDateAscKickoffTimeAsc().stream()
                .filter(fixture -> Boolean.TRUE.equals(includePast)
                        || gameDate == null
                        || isOnOrAfter(fixture, gameDate))
                .limit(safeLimit)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshot);
        out.put("fixtures", selected.stream().map(FmFixtureTools::fixtureMap).toList());
        out.put("count", selected.size());
        out.put("include_past", Boolean.TRUE.equals(includePast));
        return out;
    }

    @Tool(name = "fm26_analyze_next_opposition", description = "Analyze the next opponent from the managed club's RAM-loaded fixture list. Returns the opponent squad's strongest and weakest attribute groups, key players, availability and evidence for an AI agent to turn into a match plan.")
    @Transactional(readOnly = true)
    public Map<String, Object> analyzeNextOpposition() {
        Map<String, Object> snapshot = snapshots.reference();
        LocalDate gameDate = date(snapshot.get("game_date"));
        FixtureEntity next = fixtures.findAllByOrderByKickoffDateAscKickoffTimeAsc().stream()
                .filter(fixture -> gameDate == null || isOnOrAfter(fixture, gameDate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No upcoming managed-club fixture is loaded"));
        String opponentName = next.getOpponent();
        ClubEntity opponent = next.getOpponentClubEntity() != null
                ? next.getOpponentClubEntity()
                : clubs.findAll().stream()
                        .filter(club -> equalsText(club.getName(), opponentName))
                        .max(Comparator.comparingInt(club -> value(club.getReputation())))
                        .orElse(null);
        List<PlayerEntity> squad = players.findAllPlayerEntities().stream()
                .filter(player -> equalsText(player.getPlayingClub(), opponentName)
                        || (blank(player.getPlayingClub()) && equalsText(player.getClub(), opponentName)))
                .sorted(Comparator.comparingInt((PlayerEntity player) -> value(player.getCa())).reversed())
                .toList();
        List<PlayerEntity> firstTeam = squad.stream().limit(18).toList();
        List<Profile> profiles = profiles(firstTeam);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", snapshot);
        out.put("fixture", fixtureMap(next));
        out.put("opponent_club", clubMap(opponent, opponentName));
        out.put("squad_size", squad.size());
        out.put("first_team_average_ca", round1(firstTeam.stream().map(PlayerEntity::getCa)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).average().orElse(0)));
        out.put("strengths", profiles.stream().sorted(Comparator.comparingDouble(Profile::rating).reversed())
                .limit(3).map(Profile::toMap).toList());
        out.put("weaknesses", profiles.stream().sorted(Comparator.comparingDouble(Profile::rating))
                .limit(3).map(Profile::toMap).toList());
        out.put("key_players", squad.stream().limit(8).map(FmFixtureTools::playerMap).toList());
        out.put("unavailable", squad.stream()
                .filter(player -> Boolean.TRUE.equals(player.getInjured()) || Boolean.TRUE.equals(player.getOnDuty()))
                .map(FmFixtureTools::availabilityMap).toList());
        out.put("limitations", List.of(
                "Strengths and weaknesses are inferred from the loaded player attributes and squad composition.",
                "Opponent tactics, recent form, match statistics, morale, condition, suspensions and likely rotation are not loaded."));
        return out;
    }

    private static List<Profile> profiles(List<PlayerEntity> squad) {
        List<Profile> out = new ArrayList<>();
        out.add(profile("attacking", squad, List.of(PlayerEntity::getFinishing, PlayerEntity::getOffTheBall,
                PlayerEntity::getDribbling, PlayerEntity::getFirstTouch, PlayerEntity::getComposure)));
        out.add(profile("chance_creation", squad, List.of(PlayerEntity::getPassing, PlayerEntity::getVision,
                PlayerEntity::getTechnique, PlayerEntity::getDecisions, PlayerEntity::getFlair)));
        out.add(profile("defending", squad, List.of(PlayerEntity::getMarking, PlayerEntity::getTackling,
                PlayerEntity::getPositioning, PlayerEntity::getConcentration, PlayerEntity::getAnticipation)));
        out.add(profile("aerial", squad, List.of(PlayerEntity::getHeading, PlayerEntity::getJumpingReach,
                PlayerEntity::getStrength, PlayerEntity::getBravery)));
        out.add(profile("pace_and_mobility", squad, List.of(PlayerEntity::getPace, PlayerEntity::getAcceleration,
                PlayerEntity::getAgility, PlayerEntity::getStamina)));
        out.add(profile("work_and_mentality", squad, List.of(PlayerEntity::getWorkRate, PlayerEntity::getTeamwork,
                PlayerEntity::getDetermination, PlayerEntity::getLeadership, PlayerEntity::getPressure)));
        return out;
    }

    private static Profile profile(
            String name, List<PlayerEntity> squad, List<Function<PlayerEntity, Integer>> attributes) {
        double average = squad.stream().flatMapToInt(player -> attributes.stream()
                .mapToInt(attribute -> value(attribute.apply(player))))
                .filter(value -> value > 0).average().orElse(0);
        return new Profile(name, round1(average), squad.size(), attributes.size());
    }

    private static Map<String, Object> fixtureMap(FixtureEntity fixture) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kickoff_date", fixture.getKickoffDate());
        out.put("kickoff_time", fixture.getKickoffTime());
        out.put("competition", fixture.getCompetition());
        out.put("home_club", fixture.getHomeClub());
        out.put("away_club", fixture.getAwayClub());
        out.put("opponent", fixture.getOpponent());
        out.put("managed_club_venue", Boolean.TRUE.equals(fixture.getHome()) ? "home" : "away");
        out.put("venue", fixture.getVenue());
        return out;
    }

    private static Map<String, Object> clubMap(ClubEntity club, String name) {
        if (club == null) return Map.of("name", name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", club.getName());
        out.put("competition", club.getCompetition());
        out.put("nation", club.getNation());
        out.put("reputation", club.getReputation());
        out.put("training_facilities", club.getTrainingFacilities());
        out.put("youth_facilities", club.getYouthFacilities());
        return out;
    }

    private static Map<String, Object> playerMap(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player_unique_id", player.getUniqueId());
        out.put("name", player.getName());
        out.put("age", player.getAge());
        out.put("position", PositionTextFormatter.format(player));
        out.put("ca", player.getCa());
        out.put("pa", player.getPa());
        out.put("injured", player.getInjured());
        out.put("on_international_duty", player.getOnDuty());
        return out;
    }

    private static Map<String, Object> availabilityMap(PlayerEntity player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player_unique_id", player.getUniqueId());
        out.put("name", player.getName());
        out.put("injured", player.getInjured());
        out.put("injury", player.getInjury());
        out.put("injury_expected_return", player.getInjuryExpectedReturn());
        out.put("on_international_duty", player.getOnDuty());
        out.put("duty_end_date", player.getDutyEndDate());
        return out;
    }

    private static LocalDate date(Object raw) {
        try { return raw == null ? null : LocalDate.parse(String.valueOf(raw)); }
        catch (DateTimeParseException exception) { return null; }
    }

    private static boolean isOnOrAfter(FixtureEntity fixture, LocalDate date) {
        LocalDate kickoff = date(fixture.getKickoffDate());
        return kickoff != null && !kickoff.isBefore(date);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean equalsText(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
    private static int value(Integer value) { return value == null ? 0 : value; }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }

    private record Profile(String name, double rating, int players, int attributes) {
        Map<String, Object> toMap() {
            return Map.of("area", name, "average_20", rating,
                    "players_sampled", players, "attributes_per_player", attributes);
        }
    }
}
