package com.github.fmaiassistent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Exact identity of the supported FM27 executable image. */
public final class NativeModuleProfile {
    public static final String FM27_SHA256 = "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";
    public static final String UPDATED_SHA256 = "252ff8fb5b7a4a5a5464db6ba632e309eef9b6e65ffa992ae20575a189e66c6a";
    /** Xbox / PC Game Pass image (Microsoft Store package of Football Manager 27). */
    public static final String GAME_PASS_SHA256 = "c0a4466f8183a999917de285d47ec19f12b746e29417e402ef55ff927b927df5";
    private static final Map<String, Long> MODULE_BYTES = Map.of(
            FM27_SHA256, 433_986_560L, UPDATED_SHA256, 404_308_480L, GAME_PASS_SHA256, 391_445_504L);

    /**
     * The Game Pass image is packed differently, so its roots are not a constant shift of the
     * Steam image. Each legacy root was resolved on the live Game Pass process: vtables by
     * RTTI class name and this-offset, globals by a unique live value, registry slots relative
     * to the registry table. Roots without a unique match are absent, and the features that
     * need them stay unavailable instead of reading a guessed address.
     */
    private static final Map<Integer, Integer> GAME_PASS_RVAS = Map.ofEntries(
            Map.entry(0x4B00AE8, 0x4AC05F8),  // db::FULL_CONTRACT
            Map.entry(0x4B01058, 0x4AC0B68),  // db::BASIC_CONTRACT
            Map.entry(0x4C8C898, 0x4C3CB08),  // TRANSFER_MANAGER (+0x18 view)
            Map.entry(0x4CB2718, 0x4C62AE8),  // db::NON_PLAYING_DATA member
            Map.entry(0x4CC08D8, 0x4C70268),  // db::HUMAN_NON_PLAYER (+0x440 PERSON view)
            Map.entry(0x4CDA1F8, 0x4C89B68),  // db::CLUB
            Map.entry(0x4CDFAD8, 0x4C8F438),  // db::COMP
            Map.entry(0x4CE1108, 0x4C90A78),  // db::TEAM
            Map.entry(0x4CEC0B8, 0x4C9B0E8),  // db::NATIONAL_TEAM_CONTAINER
            Map.entry(0x4CF07F8, 0x4C9F8A8),  // db::CLUB_FINANCE_BASE
            Map.entry(0x4CF0CC8, 0x4C9FD78),  // db::CLUB_FINANCE
            Map.entry(0x4CF6078, 0x4CA5198),  // db::AWARD
            Map.entry(0x4D8BE78, 0x4D3B3C8),  // db::ACTUAL_NON_PLAYER (+0x100 PERSON view)
            Map.entry(0x4DC2288, 0x4D71998),  // db::NATIONAL_TEAM
            Map.entry(0x4DC91F8, 0x4D78AF8),  // db::LOAN_CONTRACT
            Map.entry(0x4E195B8, 0x4DC39C8),  // LOAN_OFFER
            Map.entry(0x4F392E8, 0x4EE4098),  // FULL_TRANSFER_OFFER
            Map.entry(0x4F53F28, 0x4EFE928),  // db::B_CLUB_CONTRACT
            Map.entry(0x4F65538, 0x4F103F8),  // db::LEAGUE_BODY_CLUB
            Map.entry(0x4F75F38, 0x4F21258),  // db::ACTUAL_PLAYER_AND_NON_PLAYER (+0x380 PERSON view)
            Map.entry(0x56AD378, 0x56257C8),  // current game date
            Map.entry(0x56F2EF0, 0x5672240),  // transfer-manager root
            Map.entry(0x56F32C8, 0x5672618),  // current human manager
            Map.entry(0x57035E8, 0x5683BD8),  // database registry table
            Map.entry(0x57036D0, 0x5683CC0),  // registry slot +0xE8
            Map.entry(0x57036E0, 0x5683CD0),  // registry slot +0xF8 (clubs)
            Map.entry(0x57036E8, 0x5683CD8),  // registry slot +0x100 (competitions)
            Map.entry(0x5703730, 0x5683D20),  // registry slot +0x148
            Map.entry(0x5703738, 0x5683D28),  // registry slot +0x150 (people)
            Map.entry(0x5703768, 0x5683D58)); // registry slot +0x180 (teams)

    /** Hashing a 400 MB image takes seconds; a module file is hashed once per size and timestamp.
     * A file replaced with identical size and a preserved timestamp keeps its earlier identity;
     * game updates rewrite the file, and record anchors still guard every read. */
    private static final Map<Path, CachedIdentity> IDENTITIES = new ConcurrentHashMap<>();

    private NativeModuleProfile() { }

    public static boolean supported(String hash) {
        return MODULE_BYTES.containsKey(hash == null ? "" : hash);
    }

    /** Every verified image, for persisted-row queries and layout checks that accept any of them. */
    public static List<String> supportedProfiles() {
        return List.of(FM27_SHA256, UPDATED_SHA256, GAME_PASS_SHA256);
    }

    /** Whether a legacy root has a verified location in the given image. */
    public static boolean mapsRva(String profile, int legacyRva) {
        if (!supported(profile)) return false;
        return !GAME_PASS_SHA256.equals(profile) || GAME_PASS_RVAS.containsKey(legacyRva);
    }

    /** Verified absolute-image RVAs. The Steam update moved them by two pages in native build 2493426;
     * the Game Pass image uses an explicit per-root table. Record-relative offsets and persisted
     * scalar payloads are shared by all images. */
    public static int rva(String profile, int legacyRva) {
        if (!supported(profile)) throw new IllegalArgumentException("Unsupported FM27 module profile");
        if (GAME_PASS_SHA256.equals(profile)) {
            Integer mapped = GAME_PASS_RVAS.get(legacyRva);
            if (mapped == null) {
                throw new IllegalArgumentException("FM27 root 0x" + Integer.toHexString(legacyRva)
                        + " is not verified for the Game Pass image");
            }
            return mapped;
        }
        return UPDATED_SHA256.equals(profile) ? Math.addExact(legacyRva, 0x2000) : legacyRva;
    }

    /** Bounded streaming hash; a changed size is rejected before returning a profile. */
    public static Optional<String> identify(Path path) throws IOException {
        long size = Files.size(path);
        if (!MODULE_BYTES.containsValue(size)) return Optional.empty();
        long modified = Files.getLastModifiedTime(path).toMillis();
        CachedIdentity cached = IDENTITIES.get(path);
        if (cached != null && cached.size() == size && cached.modified() == modified) return cached.profile();
        Optional<String> profile = hash(path, size);
        IDENTITIES.put(path, new CachedIdentity(size, modified, profile));
        return profile;
    }

    private static Optional<String> hash(Path path, long size) throws IOException {
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

    private record CachedIdentity(long size, long modified, Optional<String> profile) { }
}
