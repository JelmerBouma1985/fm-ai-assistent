package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.enums.ScoutingMode;
import com.github.fmaiassistent.memory.FmMemoryAccess;
import com.github.fmaiassistent.repository.PlayerFilterCriteria;
import com.github.fmaiassistent.windows.scouting.WindowsScoutingWriter;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One application-wide scouting job; the UI never performs native calls itself. */
@Service
public class ScoutReportService {
    private final PlayerDatabaseService players;
    private final ScoutSettingsService settings;
    private final DatabaseLoadAllService loader;
    private final WindowsScoutingWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("fm-scouting", 0).factory());
    private final AtomicReference<Job> active = new AtomicReference<>();
    private final AtomicReference<Progress> progress = new AtomicReference<>(new Progress(0, 0, ""));

    public ScoutReportService(PlayerDatabaseService players, ScoutSettingsService settings,
                              DatabaseLoadAllService loader, WindowsScoutingWriter writer) {
        this.players = players;
        this.settings = settings;
        this.loader = loader;
        this.writer = writer;
    }

    public boolean isSupported() {
        return writer.isSupported();
    }

    public boolean isRunning() {
        return active.get() != null;
    }

    public Progress progress() {
        return progress.get();
    }

    /** Requests a stop at the next player boundary; completed reports remain in FM. */
    public boolean cancel() {
        Job job = active.get();
        if (job == null) return false;
        job.cancelled().set(true);
        return true;
    }

    /** A stale browser view must not cancel a newer request from another window. */
    public boolean cancel(CompletableFuture<Result> expected) {
        Job job = active.get();
        if (job == null || job.future() != expected) return false;
        job.cancelled().set(true);
        return true;
    }

    public CompletableFuture<Result> scoutFiltered(PlayerFilterCriteria filter) {
        PlayerFilterCriteria captured = filter == null ? PlayerFilterCriteria.empty() : filter;
        return submit(() -> players.findPlayerEntities(captured).stream()
                .map(player -> player.getUniqueId()).toList());
    }

    public CompletableFuture<Result> scoutPlayer(long uniqueId) {
        return submit(() -> List.of(uniqueId));
    }

    private CompletableFuture<Result> submit(Supplier<List<Long>> targets) {
        if (!isSupported()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Creating scout reports is currently supported on Windows only"));
        }
        CompletableFuture<Result> future = new CompletableFuture<>();
        Job job = new Job(future, new AtomicBoolean());
        if (!active.compareAndSet(null, job)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A scouting job is already running"));
        }
        try {
            ScoutingMode mode = settings.mode();
            progress.set(new Progress(0, 0, "Finding players to scout"));
            executor.execute(() -> execute(job, targets, mode));
        } catch (RuntimeException failure) {
            active.compareAndSet(job, null);
            future.completeExceptionally(failure);
        }
        return future;
    }

    private void execute(Job job, Supplier<List<Long>> targets, ScoutingMode mode) {
        Result result = null;
        Throwable failure = null;
        try {
            List<Long> requested = job.cancelled().get() ? List.of() : targets.get();
            List<Long> ids = requested.stream().filter(id -> id != null && id > 0 && id <= 0xffff_ffffL)
                    .distinct().toList();
            int skipped = (int) requested.stream().filter(id -> id == null || id <= 0 || id > 0xffff_ffffL).count();
            if (ids.isEmpty()) {
                result = new Result(0, 0, skipped, job.cancelled().get());
            } else {
                progress.set(new Progress(0, ids.size(), "Waiting for Football Manager memory access"));
                WindowsScoutingWriter.Result written = FmMemoryAccess.exclusive(() -> {
                    if (job.cancelled().get()) {
                        return new WindowsScoutingWriter.Result(ids.size(), 0, 0, "", true);
                    }
                    int pid = loader.detectFmPid();
                    progress.set(new Progress(0, ids.size(), "Preparing scout reports"));
                    return writer.scout(pid, ids, mode == ScoutingMode.PERFECT_SCOUT,
                            (completed, total) -> progress.set(new Progress(completed, total,
                                    "Scouting players: " + completed + " / " + total)), job.cancelled()::get);
                });
                result = new Result(written.created(), written.updated(), skipped, written.cancelled());
            }
            Progress last = progress.get();
            progress.set(new Progress(last.completed(), last.total(), result.cancelled() ? "Scouting cancelled" : "Scouting complete"));
        } catch (Throwable problem) {
            Progress last = progress.get();
            String detail = problem.getMessage() == null ? problem.getClass().getSimpleName() : problem.getMessage();
            String message = detail.startsWith("Scouting stopped after ") ? detail
                    : "Scouting stopped after " + last.completed() + " of " + last.total() + " players. " + detail;
            progress.set(new Progress(last.completed(), last.total(), message));
            failure = new IOException(message, problem);
        } finally {
            // Publish idle before completing: a completion handler may immediately start another job.
            active.compareAndSet(job, null);
        }
        if (failure == null) {
            job.future().complete(result);
        } else {
            job.future().completeExceptionally(failure);
        }
    }

    @PreDestroy
    void shutdown() {
        cancel();
        executor.shutdown();
        try {
            // Java interruption is checked between players. An FM callback already in
            // progress finishes and restores attributes independently on FM's UI thread.
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public record Progress(int completed, int total, String message) {
    }

    private record Job(CompletableFuture<Result> future, AtomicBoolean cancelled) {
    }

    public record Result(int created, int updated, int skipped, boolean cancelled) {
        public Result(int created, int updated, int skipped) {
            this(created, updated, skipped, false);
        }
    }
}
