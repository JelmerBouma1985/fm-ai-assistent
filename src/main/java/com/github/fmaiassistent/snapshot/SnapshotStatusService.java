package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.GameDateFinder;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.service.DatabaseLoadAllService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SnapshotStatusService {
    private final LoadMetadataRepository metadata;
    private final DatabaseLoadAllService loader;
    private final ManagedClubContextService managedClubs;
    private final TacticContextService tactics;
    private final AtomicReference<Boolean> lastKnownStale = new AtomicReference<>(null);
    private final AtomicReference<List<String>> lastStaleReasons = new AtomicReference<>(List.of());

    public SnapshotStatusService(
            LoadMetadataRepository metadata,
            DatabaseLoadAllService loader,
            ManagedClubContextService managedClubs,
            TacticContextService tactics) {
        this.metadata = metadata;
        this.loader = loader;
        this.managedClubs = managedClubs;
        this.tactics = tactics;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reference() {
        Map<String, String> values = metadataValues();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot_id", values.get("snapshot_id"));
        out.put("game_date", values.get("game_date"));
        out.put("loaded_at", values.get("loaded_at"));
        out.put("career_key", values.get("career_key"));
        out.put("state", values.containsKey("snapshot_id") ? "loaded" : "not_loaded");
        Boolean stale = lastKnownStale.get();
        out.put("stale", stale);
        out.put("freshness", stale == null ? "unverified" : stale ? "stale" : "verified_current");
        out.put("stale_reasons", lastStaleReasons.get());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(boolean probeLive) {
        Map<String, String> values = metadataValues();
        Map<String, Object> out = new LinkedHashMap<>(reference());
        putNumber(out, "fm_pid", values.get("fm_pid"));
        putNumber(out, "fm_build", values.get("fm_build"));
        putNumber(out, "players", values.get("players_count"));
        putNumber(out, "clubs", values.get("clubs_count"));
        putNumber(out, "competitions", values.get("competitions_count"));

        ManagedClubContext club = managedClubs.current();
        out.put("managed_club", club.available() ? club.clubName() : null);
        out.put("managed_club_state", club.state().name().toLowerCase());
        TacticContext tactic = tactics.current();
        out.put("tactic", tactic.active() ? tactic.title() : null);
        out.put("tactic_version", tactic.version());
        out.put("tactic_fingerprint", tactic.fingerprint());
        out.put("tactic_definition", tactic.definition() == null ? null : tactic.definition().toMap());

        if (!probeLive) {
            out.put("live_probe", "not_requested");
            return out;
        }

        List<String> staleReasons = new ArrayList<>();
        boolean running = false;
        String liveGameDate = null;
        try {
            int pid = loader.detectFmPid();
            running = true;
            int build = parseInt(values.get("fm_build"), FmOffsets.DEFAULT_BUILD);
            try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
                liveGameDate = new GameDateFinder().find(reader, 0, build, null)
                        .map(LocalDate::toString)
                        .orElse(null);
            }
            if (liveGameDate == null) {
                staleReasons.add("live_game_date_unavailable");
            } else if (values.get("game_date") != null && !liveGameDate.equals(values.get("game_date"))) {
                staleReasons.add("game_date_changed");
            }
            String loadedPid = values.get("fm_pid");
            if (loadedPid != null && !loadedPid.equals(String.valueOf(pid))) {
                staleReasons.add("fm_process_changed");
            }
        } catch (IOException | RuntimeException exception) {
            staleReasons.add("fm_not_running_or_unreadable");
        }
        out.put("fm_process_running", running);
        out.put("live_game_date", liveGameDate);
        Boolean stale = staleReasons.contains("game_date_changed") || staleReasons.contains("fm_process_changed")
                ? Boolean.TRUE
                : running && liveGameDate != null && values.get("game_date") != null ? Boolean.FALSE : null;
        lastKnownStale.set(stale);
        lastStaleReasons.set(List.copyOf(staleReasons));
        out.put("stale", stale);
        out.put("freshness", stale == null ? "unverified" : stale ? "stale" : "verified_current");
        out.put("stale_reasons", staleReasons);
        out.put("live_probe", "completed");
        return out;
    }

    public Map<String, Object> refresh() throws IOException {
        DatabaseLoadAllService.LoadAllResult result = loader.loadAll(
                null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null);
        lastKnownStale.set(false);
        lastStaleReasons.set(List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refreshed", true);
        out.put("pid", result.pid());
        out.put("game_date", result.gameDate());
        out.put("players", result.players());
        out.put("clubs", result.clubs());
        out.put("competitions", result.competitions());
        out.put("snapshot", reference());
        return out;
    }

    private Map<String, String> metadataValues() {
        Map<String, String> values = new LinkedHashMap<>();
        metadata.findAll().forEach(row -> values.put(row.getKey(), row.getValue()));
        return values;
    }

    private static void putNumber(Map<String, Object> target, String key, String raw) {
        if (raw != null && !raw.isBlank()) {
            target.put(key, Long.parseLong(raw));
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
