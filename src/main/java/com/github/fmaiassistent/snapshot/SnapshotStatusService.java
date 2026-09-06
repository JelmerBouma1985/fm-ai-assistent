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
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class SnapshotStatusService {
    private final LoadMetadataRepository metadata;
    private final DatabaseLoadAllService loader;
    private final RefreshCoordinator refreshes;
    private final SnapshotProperties properties;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
    private final ManagedClubContextService managedClubs;
    private final TacticContextService tactics;
    private record Freshness(String snapshotId, Boolean stale, List<String> reasons) {}

    private final AtomicReference<Freshness> lastFreshness = new AtomicReference<>();

    public SnapshotStatusService(
            LoadMetadataRepository metadata,
            DatabaseLoadAllService loader,
            RefreshCoordinator refreshes,
            ManagedClubContextService managedClubs,
            TacticContextService tactics) {
        this(metadata, loader, refreshes, managedClubs, tactics, new SnapshotProperties());
    }

    @Autowired
    public SnapshotStatusService(
            LoadMetadataRepository metadata,
            DatabaseLoadAllService loader,
            RefreshCoordinator refreshes,
            ManagedClubContextService managedClubs,
            TacticContextService tactics,
            SnapshotProperties properties) {
        this.metadata = metadata;
        this.loader = loader;
        this.refreshes = refreshes;
        this.properties = properties;
        this.managedClubs = managedClubs;
        this.tactics = tactics;
    }

    public CompletableFuture<Map<String, Object>> statusAsync(boolean probeLive) {
        return CompletableFuture.supplyAsync(() -> status(probeLive), probes)
                .orTimeout(properties.liveProbeTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        probes.shutdownNow();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reference() {
        return reference(metadataValues());
    }

    private Map<String, Object> reference(Map<String, String> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot_id", values.get("snapshot_id"));
        out.put("game_date", values.get("game_date"));
        out.put("loaded_at", values.get("loaded_at"));
        out.put("career_key", values.get("career_key"));
        out.put("state", values.containsKey("snapshot_id") ? "loaded" : "not_loaded");
        Freshness checked = lastFreshness.get();
        boolean matches = checked != null && Objects.equals(checked.snapshotId(), values.get("snapshot_id"));
        Boolean stale = matches ? checked.stale() : null;
        out.put("stale", stale);
        out.put("freshness", stale == null ? "unverified" : stale ? "stale" : "verified_current");
        out.put("stale_reasons", matches ? checked.reasons() : List.of());
        out.put("refresh_recommended", !values.containsKey("snapshot_id") || Boolean.TRUE.equals(stale));
        out.put("refresh_policy", "Reuse the loaded snapshot. Refresh only when no snapshot is loaded or stale=true. "
                + "Unverified freshness alone is not a reason to reload. If the user already loaded data, use that snapshot. "
                + "For known in-game changes since loading (including same-day changes or switching saves), use force=true. "
                + "The live probe checks date/process only; it cannot detect all same-day changes.");
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(boolean probeLive) {
        Map<String, String> values = metadataValues();
        Map<String, Object> out = new LinkedHashMap<>(reference(values));
        putNumber(out, "fm_pid", values.get("fm_pid"));
        putNumber(out, "fm_build", values.get("fm_build"));
        putNumber(out, "players", values.get("players_count"));
        putNumber(out, "staff", values.get("staff_count"));
        putNumber(out, "clubs", values.get("clubs_count"));
        putNumber(out, "competitions", values.get("competitions_count"));
        RefreshCoordinator.Status refresh = refreshes.status();
        out.put("refresh_state", refresh.state().name().toLowerCase(java.util.Locale.ROOT));
        out.put("refresh_started_at", refresh.startedAt());
        out.put("refresh_completed_at", refresh.completedAt());
        out.put("refresh_failure", refresh.failure());
        Map<String, Object> quality = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith("quality_")) {
                putNumber(quality, key.substring("quality_".length()), value);
            }
        });
        out.put("data_quality", quality);
        putNumber(out, "people_slots", values.get("people_slots"));
        putNumber(out, "people_workers", values.get("people_workers"));
        putNumber(out, "people_extraction_ms", values.get("people_extraction_ms"));

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
            String loadedPid = values.get("fm_pid");
            if (loadedPid != null && !loadedPid.equals(String.valueOf(pid))) {
                staleReasons.add("fm_process_changed");
            }
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
        } catch (IOException | RuntimeException exception) {
            staleReasons.add("fm_not_running_or_unreadable");
        }
        out.put("fm_process_running", running);
        out.put("live_game_date", liveGameDate);
        Boolean stale = staleReasons.contains("game_date_changed") || staleReasons.contains("fm_process_changed")
                ? Boolean.TRUE
                : running && liveGameDate != null && values.get("game_date") != null ? Boolean.FALSE : null;
        lastFreshness.set(new Freshness(values.get("snapshot_id"), stale, List.copyOf(staleReasons)));
        out.put("refresh_recommended", !values.containsKey("snapshot_id") || Boolean.TRUE.equals(stale));
        out.put("stale", stale);
        out.put("freshness", stale == null ? "unverified" : stale ? "stale" : "verified_current");
        out.put("stale_reasons", staleReasons);
        out.put("live_probe", "completed");
        return out;
    }

    public Map<String, Object> refresh() throws IOException {
        return refresh(false);
    }

    public Map<String, Object> refresh(boolean force) throws IOException {
        if (!force && metadataValues().containsKey("snapshot_id")) {
            Map<String, Object> current = status(true);
            if ("loaded".equals(current.get("state")) && !Boolean.TRUE.equals(current.get("stale"))) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("refreshed", false);
                out.put("reason", Boolean.FALSE.equals(current.get("stale"))
                        ? "snapshot_current" : "snapshot_loaded_freshness_unverified");
                out.put("snapshot", current);
                return out;
            }
        }
        DatabaseLoadAllService.LoadAllResult result = refreshes.refreshAndWait(
                null, DatabaseLoadAllService.LoadAllResult.defaultBuild(), null);
        lastFreshness.set(new Freshness(result.snapshotId(), false, List.of()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refreshed", true);
        out.put("pid", result.pid());
        out.put("game_date", result.gameDate());
        out.put("players", result.players());
        out.put("staff", result.staff());
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
