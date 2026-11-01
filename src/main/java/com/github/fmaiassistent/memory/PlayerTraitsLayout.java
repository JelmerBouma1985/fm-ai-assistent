package com.github.fmaiassistent.memory;

/** FM27 Windows PERSON trait mask, independent of the player component displacement. */
public record PlayerTraitsLayout(int personRel, int bytes) {
    public static PlayerTraitsLayout current() {
        return new PlayerTraitsLayout(0xC0, Long.BYTES);
    }
}
