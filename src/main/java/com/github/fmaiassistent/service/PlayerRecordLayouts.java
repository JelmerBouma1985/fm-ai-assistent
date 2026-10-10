package com.github.fmaiassistent.service;

import com.github.fmaiassistent.memory.ClubRecordLayout;
import com.github.fmaiassistent.memory.CompetitionRecordLayout;
import com.github.fmaiassistent.memory.PlayerRecordLayout;
import com.github.fmaiassistent.memory.StaffRecordLayout;
import com.github.fmaiassistent.steam.SteamLibraries;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Matches the installed FM26 Steam build against known record layouts.
 *
 * <p>Only exactingly verified builds are registered; anything else resolves to
 * the last known layouts so a game patch degrades to a warning instead of a
 * hard failure. Anchor validation downstream still guards the snapshot.</p>
 */
public final class PlayerRecordLayouts {
    /** Record layouts for every decoded table, resolved together per build. */
    public record LayoutSet(
            PlayerRecordLayout players,
            StaffRecordLayout staff,
            ClubRecordLayout clubs,
            CompetitionRecordLayout competitions) {
        static LayoutSet current() {
            return new LayoutSet(
                    PlayerRecordLayout.current(),
                    StaffRecordLayout.current(),
                    ClubRecordLayout.current(),
                    CompetitionRecordLayout.current());
        }
    }

    private static final Map<Long, LayoutSet> KNOWN_BUILDS = Map.of(23583635L, LayoutSet.current());

    private PlayerRecordLayouts() {
    }

    public record LayoutMatch(LayoutSet layouts, boolean knownBuild, long installedBuildId) {
    }

    /**
     * Resolves the record layouts for the Steam installation below the given
     * home directory. Never throws: a missing or unreadable manifest resolves
     * to the last known layouts with {@code installedBuildId} {@code -1}.
     */
    public static LayoutMatch resolve(Path home) {
        OptionalLong installed = SteamLibraries.installedBuildId(home, SteamLibraries.FM26_APP_ID);
        if (installed.isPresent()) {
            LayoutSet known = KNOWN_BUILDS.get(installed.getAsLong());
            if (known != null) {
                return new LayoutMatch(known, true, installed.getAsLong());
            }
            return new LayoutMatch(LayoutSet.current(), false, installed.getAsLong());
        }
        return new LayoutMatch(LayoutSet.current(), false, -1);
    }
}
