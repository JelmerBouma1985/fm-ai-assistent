package com.github.fmaiassistent.memory;

/** Windows FM27 27.0.0+2486695 player fitness members, relative to PERSON.
 * The player/non-player variant uses the player's scalar displacement.
 */
public record PlayerFitnessLayout(int conditionRel, int fatigueRel, int sharpnessRel) {
    public static PlayerFitnessLayout current() {
        return new PlayerFitnessLayout(-0x30, -0x2E, -0x2C);
    }
}
