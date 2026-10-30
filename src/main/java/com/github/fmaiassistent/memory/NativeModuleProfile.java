package com.github.fmaiassistent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Exact binary identities. Historical snapshots retain their original source profile. */
public final class NativeModuleProfile {
    public static final String LEGACY_SHA256 = "eb6c86fab56e051fe41482c7b93d8cb0af716a1cc2c6197ce1babbff68372bb8";
    public static final String FM27_SHA256 = "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";
    private static final long LEGACY_BYTES = 514_894_848L;
    private static final long FM27_BYTES = 433_986_560L;

    private NativeModuleProfile() { }

    public static boolean supported(String hash) {
        return LEGACY_SHA256.equals(hash) || FM27_SHA256.equals(hash);
    }

    public static boolean fm27(String hash) {
        return FM27_SHA256.equals(hash);
    }

    /** Bounded streaming hash; a changed size is rejected before returning a profile. */
    public static Optional<String> identify(Path path) throws IOException {
        long size = Files.size(path);
        if (size != LEGACY_BYTES && size != FM27_BYTES) return Optional.empty();
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
            return size == FM27_BYTES && FM27_SHA256.equals(digest)
                    || size == LEGACY_BYTES && LEGACY_SHA256.equals(digest)
                    ? Optional.of(digest) : Optional.empty();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
