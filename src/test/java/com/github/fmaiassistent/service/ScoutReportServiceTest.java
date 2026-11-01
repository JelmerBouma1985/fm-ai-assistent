package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.enums.ScoutingMode;
import com.github.fmaiassistent.repository.PlayerFilterCriteria;
import com.github.fmaiassistent.windows.scouting.WindowsScoutingWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScoutReportServiceTest {
    private final PlayerDatabaseService players = mock(PlayerDatabaseService.class);
    private final ScoutSettingsService settings = mock(ScoutSettingsService.class);
    private final DatabaseLoadAllService loader = mock(DatabaseLoadAllService.class);
    private final WindowsScoutingWriter writer = mock(WindowsScoutingWriter.class);
    private final ScoutReportService service = new ScoutReportService(players, settings, loader, writer);

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private void supported() throws IOException {
        when(writer.isSupported()).thenReturn(true);
        when(settings.mode()).thenReturn(ScoutingMode.BEST_CLUB_SCOUT);
        when(loader.detectFmPid()).thenReturn(123);
    }

    @Test
    void scoutsEveryFilteredTargetDeduplicatingIdsAndSkippingMissingIds() throws Exception {
        supported();
        PlayerFilterCriteria filter = mock(PlayerFilterCriteria.class);
        List<PlayerEntity> rows = List.of(player(41L), player(42L), player(41L), player(null));
        when(players.findPlayerEntities(filter)).thenReturn(rows);
        when(writer.scout(eq(123), eq(List.of(41L, 42L)), eq(false), any(), any()))
                .thenReturn(new WindowsScoutingWriter.Result(2, 1, 1, "Club scout"));

        assertThat(service.scoutFiltered(filter).get(3, TimeUnit.SECONDS))
                .isEqualTo(new ScoutReportService.Result(1, 1, 1));
        verify(players).findPlayerEntities(filter);
        verify(writer).scout(eq(123), eq(List.of(41L, 42L)), eq(false), any(), any());
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturesPerfectModeAndReportsNativeProgressForOnlySelectedPlayer() throws Exception {
        supported();
        when(settings.mode()).thenReturn(ScoutingMode.PERFECT_SCOUT);
        when(writer.scout(eq(123), eq(List.of(67288450L)), eq(true), any(), any())).thenAnswer(call -> {
            BiConsumer<Integer, Integer> update = call.getArgument(3);
            update.accept(1, 1);
            assertThat(service.progress().completed()).isEqualTo(1);
            assertThat(service.progress().total()).isEqualTo(1);
            return new WindowsScoutingWriter.Result(1, 1, 0, "Club scout");
        });

        assertThat(service.scoutPlayer(67288450L).get(3, TimeUnit.SECONDS).created()).isEqualTo(1);
        verifyNoInteractions(players);
        assertThat(service.progress().message()).isEqualTo("Scouting complete");
    }

    @Test
    void refusesOverlappingRequestsAndAllowsNextAfterNativeFailure() throws Exception {
        supported();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(writer.scout(anyInt(), anyList(), anyBoolean(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            throw new IOException("FM closed");
        });
        CompletableFuture<ScoutReportService.Result> first = service.scoutPlayer(1);
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.scoutPlayer(2).join())
                    .hasRootCauseMessage("A scouting job is already running");
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS)).hasRootCauseMessage("FM closed");
        assertThat(service.isRunning()).isFalse();
        assertThat(service.progress().message()).contains("0 of 1", "FM closed");
        doReturn(new WindowsScoutingWriter.Result(1, 0, 1, "Club scout"))
                .when(writer).scout(anyInt(), anyList(), anyBoolean(), any(), any());
        assertThat(service.scoutPlayer(2).get(3, TimeUnit.SECONDS).updated()).isEqualTo(1);
    }

    @Test
    void noMatchingPlayersDoesNotOpenGame() throws Exception {
        supported();
        when(players.findPlayerEntities(any())).thenReturn(List.of());
        assertThat(service.scoutFiltered(null).get(3, TimeUnit.SECONDS))
                .isEqualTo(new ScoutReportService.Result(0, 0, 0));
        verifyNoInteractions(loader);
        verify(writer, never()).scout(anyInt(), anyList(), anyBoolean(), any(), any());
    }

    @Test
    void unsupportedPlatformFailsBeforeReadingSettingsOrPlayers() {
        assertThatThrownBy(() -> service.scoutPlayer(1).join())
                .hasRootCauseMessage("Creating scout reports is currently supported on Windows only");
        verifyNoInteractions(settings, players, loader);
    }

    @Test
    void settingsFailureReleasesJobReservation() {
        when(writer.isSupported()).thenReturn(true);
        when(settings.mode()).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(() -> service.scoutPlayer(1).join()).hasRootCauseMessage("Database unavailable");
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void cancellationKeepsCurrentReportAndJobReservedUntilNativeWriterFinishes() throws Exception {
        supported();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finishCurrent = new CountDownLatch(1);
        when(writer.scout(anyInt(), anyList(), anyBoolean(), any(), any())).thenAnswer(call -> {
            BooleanSupplier cancelled = call.getArgument(4);
            entered.countDown();
            assertThat(finishCurrent.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.getAsBoolean()).isTrue();
            return new WindowsScoutingWriter.Result(1, 1, 0, "Club scout", true);
        });
        assertThat(service.cancel()).isFalse();
        var future = service.scoutPlayer(1);
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(new CompletableFuture<>())).isFalse();
            assertThat(service.cancel(future)).isTrue();
            assertThat(future.isDone()).isFalse();
            assertThat(service.isRunning()).isTrue();
        } finally {
            finishCurrent.countDown();
        }
        assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(new ScoutReportService.Result(1, 0, 0, true));
        assertThat(service.progress().message()).isEqualTo("Scouting cancelled");
        assertThat(service.cancel()).isFalse();
        // A fresh request must never inherit the previous job's cancellation flag.
        doAnswer(call -> {
            assertThat(((BooleanSupplier) call.getArgument(4)).getAsBoolean()).isFalse();
            return new WindowsScoutingWriter.Result(1, 0, 1, "Club scout");
        }).when(writer).scout(anyInt(), anyList(), anyBoolean(), any(), any());
        assertThat(service.scoutPlayer(2).get(3, TimeUnit.SECONDS).cancelled()).isFalse();
    }

    @Test
    void cancellationWhileFindingPlayersAvoidsOpeningFm() throws Exception {
        supported();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var target = player(1L);
        when(players.findPlayerEntities(any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return List.of(target);
        });
        var future = service.scoutFiltered(null);
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel()).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(future.get(3, TimeUnit.SECONDS).cancelled()).isTrue();
        verifyNoInteractions(loader);
        verify(writer, never()).scout(anyInt(), anyList(), anyBoolean(), any(), any());
    }

    private PlayerEntity player(Long uid) {
        PlayerEntity player = mock(PlayerEntity.class);
        when(player.getUniqueId()).thenReturn(uid);
        return player;
    }
}
