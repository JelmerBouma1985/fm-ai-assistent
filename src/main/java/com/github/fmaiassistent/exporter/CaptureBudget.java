package com.github.fmaiassistent.exporter;

import java.time.Duration;

/**
 * Bounds for captures that walk every PERSON. They grow with the size of the save, so large
 * databases (300k+ people) are not rejected, and stay inside fixed ceilings so a corrupt
 * registry cannot turn a capture into an unbounded read or an endless wait.
 */
public final class CaptureBudget {
    static final long FLOOR_BYTES = 64L * 1024 * 1024;
    static final long PER_OWNER_BYTES = 2048;
    static final long CEILING_BYTES = 1024L * 1024 * 1024;
    /** People-registry size the original fixed deadlines were measured on. */
    static final long REFERENCE_PEOPLE_SLOTS = 100_000;
    static final int MAX_TIMEOUT_FACTOR = 6;

    private CaptureBudget() {
    }

    static long forOwners(int owners) {
        return Math.min(CEILING_BYTES, Math.max(FLOOR_BYTES, Math.max(0, owners) * PER_OWNER_BYTES));
    }

    /** The floor up to the reference registry size, then proportional, at most six times the floor. */
    public static Duration scaledTimeout(Duration floor, long peopleSlots) {
        if (peopleSlots <= REFERENCE_PEOPLE_SLOTS) return floor;
        if (peopleSlots >= REFERENCE_PEOPLE_SLOTS * MAX_TIMEOUT_FACTOR) return floor.multipliedBy(MAX_TIMEOUT_FACTOR);
        long millis = Math.ceilDiv(Math.multiplyExact(floor.toMillis(), peopleSlots), REFERENCE_PEOPLE_SLOTS);
        return Duration.ofMillis(millis);
    }
}
