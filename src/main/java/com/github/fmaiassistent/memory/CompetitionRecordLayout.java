package com.github.fmaiassistent.memory;

/**
 * All byte offsets of the FM26 competition record and its linked structures.
 *
 * <p>Canonical home for the {@code *_REL} values previously scattered as
 * constants across the competition exporter. {@link #current()} holds the
 * offsets of the supported build; alternate instances describe other builds
 * (or synthetic drift in tests).</p>
 */
public record CompetitionRecordLayout(
        long nameRel,
        long nationRel,
        long nationNameRelA,
        long nationNameRelB,
        long genderFlagRel,
        long reputationRel) {

    /** The offsets of the currently supported FM26 build. */
    public static CompetitionRecordLayout current() {
        return new CompetitionRecordLayout(0x40L, 0x60L, 0x18L, 0x20L, 0xF9L, 0x188L);
    }
}
