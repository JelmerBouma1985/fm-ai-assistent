package com.github.fmaiassistent.service;

import com.github.fmaiassistent.memory.ClubRecordLayout;
import com.github.fmaiassistent.memory.CompetitionRecordLayout;
import com.github.fmaiassistent.memory.PlayerRecordLayout;
import com.github.fmaiassistent.memory.StaffRecordLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerRecordLayoutsTest {
    @Test
    void resolvesLatestSteamBuild(@TempDir Path home) throws Exception {
        writeManifest(home, "\"AppState\"\n{\n\t\"buildid\"\t\t\"26385295\"\n}");
        var match = PlayerRecordLayouts.resolve(home);
        assertThat(match.knownBuild()).isTrue();
        assertThat(match.installedBuildId()).isEqualTo(26385295L);
        assertThat(match.layouts()).isEqualTo(PlayerRecordLayouts.LayoutSet.current());
    }

    @Test
    void resolvesNovemberUpdate(@TempDir Path home) throws Exception {
        writeManifest(home, "\"AppState\"\n{\n\t\"buildid\"\t\t\"26340721\"\n}");
        var match = PlayerRecordLayouts.resolve(home);
        assertThat(match.knownBuild()).isTrue();
        assertThat(match.installedBuildId()).isEqualTo(26340721L);
        assertThat(match.layouts().players()).isEqualTo(PlayerRecordLayout.current());
        assertThat(match.layouts().staff()).isEqualTo(StaffRecordLayout.current());
        assertThat(match.layouts().clubs()).isEqualTo(ClubRecordLayout.current());
        assertThat(match.layouts().competitions()).isEqualTo(CompetitionRecordLayout.current());
    }

    @Test
    void verifiedGamePassImageIsKnownWithoutASteamManifest(@TempDir Path home) {
        var match = PlayerRecordLayouts.resolve(home,
                com.github.fmaiassistent.memory.NativeModuleProfile.GAME_PASS_SHA256);
        assertThat(match.knownBuild()).isTrue();
        assertThat(match.installedBuildId()).isEqualTo(-1);
        assertThat(match.layouts().players()).isEqualTo(PlayerRecordLayout.current());
        assertThat(PlayerRecordLayouts.resolve(home, "not-a-verified-image").knownBuild()).isFalse();
        assertThat(PlayerRecordLayouts.resolve(home, null).knownBuild()).isFalse();
    }

    @Test
    void resolvesKnownBuild(@TempDir Path home) throws Exception {
        writeManifest(home, "\"AppState\"\n{\n\t\"buildid\"\t\t\"26227640\"\n}");

        PlayerRecordLayouts.LayoutMatch match = PlayerRecordLayouts.resolve(home);

        assertThat(match.knownBuild()).isTrue();
        assertThat(match.installedBuildId()).isEqualTo(26227640L);
        assertThat(match.layouts().players()).isEqualTo(PlayerRecordLayout.current());
        assertThat(match.layouts().staff()).isEqualTo(StaffRecordLayout.current());
        assertThat(match.layouts().clubs()).isEqualTo(ClubRecordLayout.current());
        assertThat(match.layouts().competitions()).isEqualTo(CompetitionRecordLayout.current());
    }

    @Test
    void fallsBackToLastKnownLayoutOnUnknownBuild(@TempDir Path home) throws Exception {
        writeManifest(home, "\"AppState\"\n{\n\t\"buildid\"\t\t\"99999999\"\n}");

        PlayerRecordLayouts.LayoutMatch match = PlayerRecordLayouts.resolve(home);

        assertThat(match.knownBuild()).isFalse();
        assertThat(match.installedBuildId()).isEqualTo(99999999L);
        assertThat(match.layouts().players()).isEqualTo(PlayerRecordLayout.current());
        assertThat(match.layouts().staff()).isEqualTo(StaffRecordLayout.current());
        assertThat(match.layouts().clubs()).isEqualTo(ClubRecordLayout.current());
        assertThat(match.layouts().competitions()).isEqualTo(CompetitionRecordLayout.current());
    }

    @Test
    void fallsBackWhenManifestMissing(@TempDir Path home) {
        PlayerRecordLayouts.LayoutMatch match = PlayerRecordLayouts.resolve(home);

        assertThat(match.knownBuild()).isFalse();
        assertThat(match.installedBuildId()).isEqualTo(-1);
    }

    private static void writeManifest(Path home, String manifest) throws Exception {
        Path customLib = home.resolve("mnt/bcache/Steam");
        Path steamApps = home.resolve(".local/share/Steam/steamapps");
        Files.createDirectories(steamApps);
        Files.createDirectories(customLib.resolve("steamapps"));
        Files.writeString(steamApps.resolve("libraryfolders.vdf"),
                "\"libraryfolders\"\n{\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"" + customLib + "\"\n\t}\n}");
        Files.writeString(customLib.resolve("steamapps/appmanifest_4530370.acf"), manifest);
    }
}
