package com.example.fmgenie26.db;

import com.example.fmgenie26.fm.FmOffsets;
import com.example.fmgenie26.linux.LinuxProcessReader;
import com.example.fmgenie26.linux.ProcessInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Comparator;

@Service
public class DatabaseLoadAllService {
    private final PlayerDatabaseService players;
    private final ClubDatabaseService clubs;
    private final CompetitionDatabaseService competitions;

    public DatabaseLoadAllService(PlayerDatabaseService players, ClubDatabaseService clubs, CompetitionDatabaseService competitions) {
        this.players = players;
        this.clubs = clubs;
        this.competitions = competitions;
    }

    @Transactional
    public LoadAllResult loadAll(Integer pid, int build, Long gamePluginBase) throws IOException {
        int resolvedPid = pid == null ? detectFmPid() : pid;
        PlayerDatabaseService.LoadResult playerResult = players.loadAllPlayers(resolvedPid, build, gamePluginBase);
        return new LoadAllResult(
                resolvedPid,
                playerResult.gameDate(),
                playerResult.count(),
                clubs.countClubs(),
                competitions.countCompetitions());
    }

    public int detectFmPid() throws IOException {
        return LinuxProcessReader.findProcesses("fm.exe").stream()
                .max(Comparator.comparingInt(DatabaseLoadAllService::processScore))
                .filter(process -> processScore(process) > 0)
                .map(ProcessInfo::pid)
                .orElseThrow(() -> new IllegalStateException("fm.exe process not found"));
    }

    private static int processScore(ProcessInfo process) {
        String name = process.name().toLowerCase();
        String cmdline = process.cmdline().toLowerCase();
        int score = 0;
        if ("fm.exe".equals(name)) {
            score += 100;
        }
        if (cmdline.contains("football manager 26")) {
            score += 50;
        }
        if (cmdline.endsWith("fm.exe") || cmdline.endsWith("fm.exe\"")) {
            score += 25;
        }
        if (cmdline.contains("proton") || cmdline.contains("steamlaunch") || cmdline.contains("reaper")
                || cmdline.contains("bwrap") || cmdline.contains("steam.exe")) {
            score -= 100;
        }
        return score;
    }

    public record LoadAllResult(int pid, String gameDate, long players, long clubs, long competitions) {
        public static int defaultBuild() {
            return FmOffsets.DEFAULT_BUILD;
        }
    }
}
