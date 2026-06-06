package com.example.fmgenie26.web;

import com.example.fmgenie26.db.ClubDatabaseService;
import com.example.fmgenie26.db.CompetitionDatabaseService;
import com.example.fmgenie26.db.DatabaseLoadAllService;
import com.example.fmgenie26.db.PlayerDatabaseService;
import com.example.fmgenie26.fm.FmOffsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class DatabaseController {
    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final CompetitionDatabaseService competitions;
    private final DatabaseLoadAllService loadAll;

    public DatabaseController(
            PlayerDatabaseService players,
            ClubDatabaseService clubs,
            CompetitionDatabaseService competitions,
            DatabaseLoadAllService loadAll) {
        this.players = players;
        this.clubs = clubs;
        this.competitions = competitions;
        this.loadAll = loadAll;
    }

    @PostMapping("/api/db/load-all")
    public Map<String, Object> loadAllPost(
            @RequestParam(required = false) Integer pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadAll(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/load-all")
    public Map<String, Object> loadAllGet(
            @RequestParam(required = false) Integer pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadAll(pid, build, gamePluginBase);
    }

    @PostMapping("/api/db/players/load")
    public Map<String, Object> loadPlayersPost(
            @RequestParam int pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadPlayers(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/players/load")
    public Map<String, Object> loadPlayersGet(
            @RequestParam int pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadPlayers(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/players")
    public List<Map<String, Object>> players(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String gender,
            @RequestParam(defaultValue = "") String nationality,
            @RequestParam(defaultValue = "50") int limit) {
        return players.findPlayers(name, gender, nationality, limit);
    }

    @GetMapping("/api/db/players/count")
    public Map<String, Object> count() {
        return Map.of("count", players.countPlayers());
    }

    @PostMapping("/api/db/clubs/load")
    public Map<String, Object> loadClubsPost(
            @RequestParam int pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadClubs(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/clubs/load")
    public Map<String, Object> loadClubsGet(
            @RequestParam int pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadClubs(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/clubs")
    public List<Map<String, Object>> clubs(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String gender,
            @RequestParam(defaultValue = "") String nation,
            @RequestParam(defaultValue = "") String competition,
            @RequestParam(defaultValue = "50") int limit) {
        return clubs.findClubs(name, gender, nation, competition, limit);
    }

    @GetMapping("/api/db/clubs/count")
    public Map<String, Object> clubCount() {
        return Map.of("count", clubs.countClubs());
    }

    @PostMapping("/api/db/competitions/load")
    public Map<String, Object> loadCompetitionsPost(
            @RequestParam int pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadCompetitions(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/competitions/load")
    public Map<String, Object> loadCompetitionsGet(
            @RequestParam int pid,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        return loadCompetitions(pid, build, gamePluginBase);
    }

    @GetMapping("/api/db/competitions")
    public List<Map<String, Object>> competitions(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String nation,
            @RequestParam(defaultValue = "") String gender,
            @RequestParam(defaultValue = "50") int limit) {
        return competitions.findCompetitions(name, nation, gender, limit);
    }

    @GetMapping("/api/db/competitions/count")
    public Map<String, Object> competitionCount() {
        return Map.of("count", competitions.countCompetitions());
    }

    @GetMapping("/api/db/metadata")
    public Map<String, Object> metadata() {
        return players.metadata();
    }

    private Map<String, Object> loadPlayers(int pid, String build, String gamePluginBase) throws IOException {
        PlayerDatabaseService.LoadResult result = players.loadAllPlayers(pid, parseInt(build), gamePluginBase == null ? null : parseLong(gamePluginBase));
        return Map.of("game_date", result.gameDate(), "count", result.count(), "table", "players");
    }

    private Map<String, Object> loadClubs(int pid, String build, String gamePluginBase) throws IOException {
        ClubDatabaseService.LoadResult result = clubs.loadAllClubs(pid, parseInt(build), gamePluginBase == null ? null : parseLong(gamePluginBase));
        return Map.of("count", result.count(), "table", "clubs");
    }

    private Map<String, Object> loadCompetitions(int pid, String build, String gamePluginBase) throws IOException {
        CompetitionDatabaseService.LoadResult result = competitions.loadAllCompetitions(pid, parseInt(build), gamePluginBase == null ? null : parseLong(gamePluginBase));
        return Map.of("count", result.count(), "table", "competitions");
    }

    private Map<String, Object> loadAll(Integer pid, String build, String gamePluginBase) throws IOException {
        DatabaseLoadAllService.LoadAllResult result = loadAll.loadAll(pid, parseInt(build), gamePluginBase == null ? null : parseLong(gamePluginBase));
        return Map.of(
                "pid", result.pid(),
                "game_date", result.gameDate(),
                "players", result.players(),
                "clubs", result.clubs(),
                "competitions", result.competitions());
    }

    private static int parseInt(String value) {
        return (int) Long.decode(value).longValue();
    }

    private static long parseLong(String value) {
        return Long.decode(value);
    }
}
