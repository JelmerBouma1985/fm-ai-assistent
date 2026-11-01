package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FmMemoryAccessTest {
    @Test
    void snapshotCannotReadTemporaryScoutAttributesAndFailureReleasesAccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch read = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> FmMemoryAccess.exclusive(() -> {
                entered.countDown();
                try {
                    assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                throw new IOException("Native failure after restoring attributes");
            }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var reader = executor.submit(() -> FmMemoryAccess.exclusive(() -> {
                read.countDown();
                return "Original attributes";
            }));
            try {
                assertThat(read.await(100, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> writer.get(3, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Native failure after restoring attributes");
            assertThat(reader.get(3, TimeUnit.SECONDS)).isEqualTo("Original attributes");
        }
    }
}
