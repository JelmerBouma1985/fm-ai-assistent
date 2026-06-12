package com.github.fmaiscout.web;

import com.github.fmaiscout.csv.CsvWriter;
import com.github.fmaiscout.linux.LinuxProcessReader;
import com.github.fmaiscout.linux.ProcessInfo;
import com.github.fmaiscout.player.PlayerExporter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
public class ExportController {
    private final PlayerExporter exporter = new PlayerExporter();

    @GetMapping("/api/processes")
    public List<ProcessInfo> processes(@RequestParam(defaultValue = "fm.exe") String query) throws IOException {
        return LinuxProcessReader.findProcesses(query);
    }

    @GetMapping("/api/players/club")
    public Map<String, Object> players(
            @RequestParam int pid,
            @RequestParam(defaultValue = "Feyenoord") String club,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        PlayerExporter.ExportResult result = exporter.exportClub(pid, club, parseInt(build), gamePluginBase == null ? null : parseLong(gamePluginBase));
        return Map.of("game_date", result.gameDate(), "count", result.rows().size(), "rows", result.rows());
    }

    @GetMapping("/api/export/club")
    public Map<String, Object> exportClub(
            @RequestParam int pid,
            @RequestParam(defaultValue = "Feyenoord") String club,
            @RequestParam(defaultValue = "exports/feyenoord_players_java.csv") String output,
            @RequestParam(defaultValue = "0x235144") String build,
            @RequestParam(required = false, name = "gamePluginBase") String gamePluginBase) throws IOException {
        PlayerExporter.ExportResult result = exporter.exportClub(pid, club, parseInt(build), gamePluginBase == null ? null : parseLong(gamePluginBase));
        CsvWriter.write(Path.of(output), PlayerExporter.FIELD_NAMES, result.rows());
        return Map.of("game_date", result.gameDate(), "count", result.rows().size(), "output", output);
    }

    private static int parseInt(String value) {
        return (int) Long.decode(value).longValue();
    }

    private static long parseLong(String value) {
        return Long.decode(value);
    }
}
