package com.github.fmaiassistent.service;

import com.github.fmaiassistent.memory.PlayerRecordLayout;
import com.github.fmaiassistent.steam.SteamLibraries;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Matches the installed FM26 Steam build against known player-record layouts.
 *
 * <p>Only exactingly verified builds are registered; anything else resolves to
 * the last known layout so a game patch degrades to a warning instead of a
 * hard failure. Anchor validation downstream still guards the snapshot.</p>
 */
public final class PlayerRecordLayouts {
    private static final Map<Long, PlayerRecordLayout> KNOWN_BUILDS =
            Map.of(23583635L, PlayerRecordLayout.current());

    private PlayerRecordLayouts() {
    }

    public record LayoutMatch(PlayerRecordLayout layout, boolean knownBuild, long installedBuildId) {
    }

    /**
     * Resolves the record layout for the Steam installation below the given
     * home directory. Never throws: a missing or unreadable manifest resolves
     * to the last known layout with {@code installedBuildId} {@code -1}.
     */
    public static LayoutMatch resolve(Path home) {
        OptionalLong installed = SteamLibraries.installedBuildId(home, SteamLibraries.FM26_APP_ID);
        if (installed.isPresent()) {
            PlayerRecordLayout known = KNOWN_BUILDS.get(installed.getAsLong());
            if (known != null) {
                return new LayoutMatch(known, true, installed.getAsLong());
            }
            return new LayoutMatch(PlayerRecordLayout.current(), false, installed.getAsLong());
        }
        return new LayoutMatch(PlayerRecordLayout.current(), false, -1);
    }
}
