package com.github.fmaiassistent.snapshot;

import com.github.fmaiassistent.service.DatabaseLoadAllService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshCoordinatorTest {
    @Test
    void sharesOneInFlightRefreshBetweenAllCallers() throws Exception {
        DatabaseLoadAllService loader = mock(DatabaseLoadAllService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DatabaseLoadAllService.LoadAllResult expected = new DatabaseLoadAllService.LoadAllResult(
                123, "2033-06-10", 2, 1, 3, 4, "snapshot");
        when(loader.loadAll(null, 1530, null)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return expected;
        });
        RefreshCoordinator coordinator = new RefreshCoordinator(
                loader, new RefreshProperties(Duration.ofSeconds(5), Duration.ofSeconds(1)));
        try {
            CompletableFuture<DatabaseLoadAllService.LoadAllResult> first = coordinator.refresh(null, 1530, null);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<DatabaseLoadAllService.LoadAllResult> second = coordinator.refresh(null, 1530, null);

            assertThat(second).isSameAs(first);
            assertThat(coordinator.status().state()).isEqualTo(RefreshCoordinator.State.RUNNING);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isSameAs(expected);
            assertThat(coordinator.status().state()).isEqualTo(RefreshCoordinator.State.SUCCEEDED);
            verify(loader, times(1)).loadAll(null, 1530, null);
        } finally {
            coordinator.shutdown();
        }
    }
}
