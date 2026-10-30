package com.github.fmaiassistent.memory;

/** Shared PERSON prefix and RTTI displacements; retained captures distinguish each supported profile. */
public record PersonIdentityLayout(int indexRel, int uniqueIdRel, int prefixBytes) {
    public PersonIdentityLayout {
        if (prefixBytes < 16 || prefixBytes > 64) {
            throw new IllegalArgumentException("PERSON identity prefix must be bounded to 16..64 bytes");
        }
    }

    public static PersonIdentityLayout current() {
        return new PersonIdentityLayout(0x08, 0x0c, 0x10);
    }

    /** MSVC complete-object offsets for PERSON views in FM27 27.0.0+2486695. */
    public static Subtypes currentSubtypes() {
        return new Subtypes(0x100, 0x288, 0x380, 0x440);
    }

    public record Subtypes(int staff, int player, int playerStaff, int humanManager) { }
}
