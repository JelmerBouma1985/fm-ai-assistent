package com.github.fmaiassistent.steam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SteamLibrariesTest {
    @Test
    void readsLibrariesFromLibraryFolders(@TempDir Path home) throws Exception {
        Path customLib = home.resolve("mnt/bcache/Steam");
        Path steamApps = home.resolve(".local/share/Steam/steamapps");
        Files.createDirectories(steamApps);
        Files.writeString(steamApps.resolve("libraryfolders.vdf"),
                "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"" + home.resolve(".local/share/Steam") + "\"\n\t}\n"
                        + "\t\"1\"\n\t{\n\t\t\"path\"\t\t\"" + customLib + "\"\n\t}\n}");

        List<Path> libraries = SteamLibraries.libraries(home);

        assertThat(libraries).contains(
                home.resolve(".local/share/Steam").toAbsolutePath().normalize(),
                customLib.toAbsolutePath().normalize());
    }

    @Test
    void buildsProtonUserDirectoriesPerLibrary(@TempDir Path home) {
        List<Path> userDirs = SteamLibraries.fmProtonUserDirectories(home);

        assertThat(userDirs).allMatch(path -> path.endsWith("pfx/drive_c/users/steamuser"));
        assertThat(userDirs.getFirst().toString()).contains(".local/share/Steam");
    }

    @Test
    void returnsEmptyWhenVdfIsMissing(@TempDir Path home) {
        assertThat(SteamLibraries.parseLibraryFolders(home.resolve("libraryfolders.vdf"))).isEmpty();
    }

    @Test
    void findsLibraryHoldingTheGame(@TempDir Path home) throws Exception {
        Path customLib = home.resolve("mnt/bcache/Steam");
        Path steamApps = home.resolve(".local/share/Steam/steamapps");
        Files.createDirectories(steamApps);
        Files.createDirectories(customLib.resolve("steamapps"));
        Files.writeString(steamApps.resolve("libraryfolders.vdf"),
                "\"libraryfolders\"\n{\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"" + customLib + "\"\n\t}\n}");
        Files.writeString(customLib.resolve("steamapps/appmanifest_3551340.acf"), "\"AppState\"{}");

        assertThat(SteamLibraries.libraryWithApp(home, 3551340))
                .hasValue(customLib.toAbsolutePath().normalize());
        assertThat(SteamLibraries.libraryWithApp(home, 12345)).isEmpty();
    }
}
