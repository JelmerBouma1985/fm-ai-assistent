package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureBudgetTest {
    private static final long MIB = 1024L * 1024;

    @Test
    void smallSavesKeepTheOriginalSnapshotBudget() {
        assertThat(CaptureBudget.forOwners(0)).isEqualTo(64 * MIB);
        assertThat(CaptureBudget.forOwners(32_768)).isEqualTo(64 * MIB);
    }

    @Test
    void largeDatabasesGetHeadroomProportionalToTheirOwners() {
        // A 327k-owner Game Pass save read 157 MB of contracts and 143 MB of future transfers.
        long budget = CaptureBudget.forOwners(326_991);
        assertThat(budget).isGreaterThan(2 * 157 * MIB);
        assertThat(budget).isEqualTo(326_991L * 2048);
    }

    @Test
    void deadlinesScaleWithThePeopleRegistryInsideAFixedCeiling() {
        var floor = java.time.Duration.ofSeconds(90);
        assertThat(CaptureBudget.scaledTimeout(floor, 94_815)).isEqualTo(floor);
        assertThat(CaptureBudget.scaledTimeout(floor, 0)).isEqualTo(floor);
        assertThat(CaptureBudget.scaledTimeout(floor, 390_837)).isEqualTo(java.time.Duration.ofMillis(351_754));
        assertThat(CaptureBudget.scaledTimeout(floor, Long.MAX_VALUE)).isEqualTo(floor.multipliedBy(6));
    }

    @Test
    void theBudgetStaysBoundedForCorruptCounts() {
        assertThat(CaptureBudget.forOwners(Integer.MAX_VALUE)).isEqualTo(1024 * MIB);
        assertThat(CaptureBudget.forOwners(-1)).isEqualTo(64 * MIB);
    }
}
