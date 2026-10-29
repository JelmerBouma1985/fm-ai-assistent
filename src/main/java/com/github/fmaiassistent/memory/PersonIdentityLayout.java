package com.github.fmaiassistent.memory;

/** Shared PERSON prefix, independently captured across FM26 reloads; see reverse-engineering evidence. */
public record PersonIdentityLayout(int indexRel, int uniqueIdRel, int prefixBytes) {
    public PersonIdentityLayout {
        if (prefixBytes < 16 || prefixBytes > 64) {
            throw new IllegalArgumentException("PERSON identity prefix must be bounded to 16..64 bytes");
        }
    }

    public static PersonIdentityLayout current() {
        return new PersonIdentityLayout(0x08, 0x0c, 0x10);
    }
}
