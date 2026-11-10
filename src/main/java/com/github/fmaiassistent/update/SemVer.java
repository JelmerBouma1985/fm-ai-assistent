package com.github.fmaiassistent.update;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Stable SemVer only. Build metadata does not affect ordering. */
public record SemVer(BigInteger major, BigInteger minor, BigInteger patch) implements Comparable<SemVer> {
    private static final Pattern STABLE = Pattern.compile("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    public static SemVer parse(String value) {
        var match = STABLE.matcher(value == null ? "" : value);
        if (!match.matches()) throw new IllegalArgumentException("Expected a stable semantic version");
        return new SemVer(new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match.group(3)));
    }
    public int compareTo(SemVer other) {
        int c = major.compareTo(other.major);
        if (c == 0) c = minor.compareTo(other.minor);
        return c == 0 ? patch.compareTo(other.patch) : c;
    }
}
