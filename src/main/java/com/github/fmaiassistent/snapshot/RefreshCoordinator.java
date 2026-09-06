package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.service.DatabaseLoadAllService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the single application-wide FM RAM refresh and shares it between callers. */
@Service
public class RefreshCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RefreshCoordinator.class);

    private final DatabaseLoadAllService loader;
    private final RefreshProperties properties;
    private final Timer refreshDuration;
    private final Counter refreshSuccesses;
    private final Counter refreshFailures;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("fm-refresh-coordinator", 0).factory());
    private final AtomicReference<CompletableFuture<DatabaseLoadAllService.LoadAllResult>> inFlight =
            new AtomicReference<>();
    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status(State.IDLE, null, null, null, null));

    @Autowired
    public RefreshCoordinator(
            DatabaseLoadAllService loader,
            RefreshProperties properties,
            MeterRegistry metrics) {
        this.loader = loader;
        this.properties = properties;
        refreshDuration = metrics.timer("fm.snapshot.refresh.duration");
        refreshSuccesses = metrics.counter("fm.snapshot.refresh.completed", "outcome", "success");
        refreshFailures = metrics.counter("fm.snapshot.refresh.completed", "outcome", "failure");
    }

    RefreshCoordinator(DatabaseLoadAllService loader, RefreshProperties properties) {
        this(loader, properties, new SimpleMeterRegistry());
    }

    public CompletableFuture<DatabaseLoadAllService.LoadAllResult> refresh(
            Integer pid, int build, Long gamePluginBase) {
        Request request = new Request(pid, build, gamePluginBase);
        while (true) {
            CompletableFuture<DatabaseLoadAllService.LoadAllResult> existing = inFlight.get();
            if (existing != null) {
                return existing;
            }
            CompletableFuture<DatabaseLoadAllService.LoadAllResult> created = new CompletableFuture<>();
            if (!inFlight.compareAndSet(null, created)) {
                continue;
            }
            Instant started = Instant.now();
            status.set(new Status(State.RUNNING, request, started, null, null));
            executor.execute(() -> execute(created, request, started));
            return created;
        }
    }

    public DatabaseLoadAllService.LoadAllResult refreshAndWait(
            Integer pid, int build, Long gamePluginBase) throws IOException {
        CompletableFuture<DatabaseLoadAllService.LoadAllResult> refresh = refresh(pid, build, gamePluginBase);
        try {
            return refresh.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while refreshing the FM26 snapshot", exception);
        } catch (TimeoutException exception) {
            // The shared refresh remains active for other callers. Native I/O has its
            // own deadline and will complete or fail independently.
            throw new IOException("Timed out waiting for the FM26 snapshot refresh after "
                    + properties.timeout().toSeconds() + " seconds", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Could not refresh the FM26 snapshot", cause);
        }
    }

    public Status status() {
        return status.get();
    }

    private void execute(
            CompletableFuture<DatabaseLoadAllService.LoadAllResult> refresh,
            Request request,
            Instant started) {
        try {
            DatabaseLoadAllService.LoadAllResult result = loader.loadAll(
                    request.pid(), request.build(), request.gamePluginBase());
            status.set(new Status(State.SUCCEEDED, request, started, Instant.now(), null));
            refreshSuccesses.increment();
            refresh.complete(result);
        } catch (Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            status.set(new Status(State.FAILED, request, started, Instant.now(), message));
            refreshFailures.increment();
            refresh.completeExceptionally(failure);
        } finally {
            refreshDuration.record(java.time.Duration.between(started, Instant.now()));
            inFlight.compareAndSet(refresh, null);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.debug("FM refresh coordinator stopped");
    }

    public enum State {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record Request(Integer pid, int build, Long gamePluginBase) {
    }

    public record Status(State state, Request request, Instant startedAt, Instant completedAt, String failure) {
        public Status {
            Objects.requireNonNull(state, "state");
        }
    }
}
