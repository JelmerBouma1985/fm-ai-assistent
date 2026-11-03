package com.github.fmaiassistent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Exact identity of the supported FM27 executable image. */
public final class NativeModuleProfile {
    public static final String FM27_SHA256 = "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";
    public static final String UPDATED_SHA256 = "252ff8fb5b7a4a5a5464db6ba632e309eef9b6e65ffa992ae20575a189e66c6a";
    private static final java.util.Map<String, Long> MODULE_BYTES = java.util.Map.of(
            FM27_SHA256, 433_986_560L, UPDATED_SHA256, 404_308_480L);

    private NativeModuleProfile() { }

    public static boolean supported(String hash) {
        return MODULE_BYTES.containsKey(hash == null ? "" : hash);
    }

    /** Verified absolute-image RVAs changed by two pages in native build 2493426.
     * Record-relative offsets and persisted scalar payloads did not change. */
    public static int rva(String profile, int legacyRva) {
        if (!supported(profile)) throw new IllegalArgumentException("Unsupported FM27 module profile");
        return UPDATED_SHA256.equals(profile) ? Math.addExact(legacyRva, 0x2000) : legacyRva;
    }

    /** Bounded streaming hash; a changed size is rejected before returning a profile. */
    public static Optional<String> identify(Path path) throws IOException {
        long size = Files.size(path);
        if (!MODULE_BYTES.containsValue(size)) return Optional.empty();
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (var in = Files.newInputStream(path)) {
                byte[] chunk = new byte[65536];
                for (int n; (n = in.read(chunk)) != -1;) {
                    total += n;
                    if (total > size) return Optional.empty();
                    hash.update(chunk, 0, n);
                }
            }
            if (total != size) return Optional.empty();
            String digest = HexFormat.of().formatHex(hash.digest());
            return java.util.Objects.equals(MODULE_BYTES.get(digest), size)
                    ? Optional.of(digest) : Optional.empty();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
