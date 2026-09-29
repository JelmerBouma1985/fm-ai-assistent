package com.github.fmaiassistent.tactic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TacticDirectoryResolverTest {
    @Test
    void listsOnlyFmfFilesSortedByModifiedDesc(@TempDir Path dir) throws Exception {
        Path older = dir.resolve("older.fmf");
        Path newer = dir.resolve("newer.fmf");
        Files.write(older, new byte[]{1});
        Thread.sleep(1100);
        Files.write(newer, new byte[]{1, 2});
        Files.write(dir.resolve("notes.txt"), new byte[]{1});

        TacticDirectoryResolver resolver = new TacticDirectoryResolver(dir);
        List<TacticDirectoryResolver.DiskTactic> found = resolver.listTactics();

        assertThat(found).extracting(TacticDirectoryResolver.DiskTactic::fileName)
                .containsExactly("newer.fmf", "older.fmf");
        assertThat(found.getFirst().path()).isEqualTo(newer.toAbsolutePath().normalize());
    }

    @Test
    void returnsEmptyWhenDirectoryIsMissing(@TempDir Path dir) {
        TacticDirectoryResolver resolver = new TacticDirectoryResolver(dir.resolve("absent"));

        assertThat(resolver.listTactics()).isEmpty();
    }

    @Test
    void resolvesCandidatesUnderHome(@TempDir Path home) {
        List<Path> candidates = TacticDirectoryResolver.candidates(home);

        assertThat(candidates).hasSize(3);
        assertThat(candidates.getFirst().toString())
                .contains("Sports Interactive/Football Manager 26/tactics");
    }

    @Test
    void findsCustomSteamLibraryFromLibraryFolders(@TempDir Path home) throws Exception {
        Path customLib = home.resolve("mnt/bcache/Steam");
        Path tactics = customLib.resolve(
                "steamapps/compatdata/3551340/pfx/drive_c/users/steamuser/Documents/Sports Interactive/Football Manager 26/tactics");
        Files.createDirectories(tactics);
        Files.write(tactics.resolve("custom.fmf"), new byte[]{1});
        Path steamApps = home.resolve(".local/share/Steam/steamapps");
        Files.createDirectories(steamApps);
        Files.writeString(steamApps.resolve("libraryfolders.vdf"),
                "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"" + home.resolve(".local/share/Steam") + "\"\n\t}\n"
                        + "\t\"1\"\n\t{\n\t\t\"path\"\t\t\"" + customLib + "\"\n\t}\n}");

        List<Path> candidates = TacticDirectoryResolver.candidates(home);

        assertThat(candidates).contains(tactics.toAbsolutePath().normalize());

        TacticDirectoryResolver resolver = new TacticDirectoryResolver(candidates);
        assertThat(resolver.listTactics())
                .extracting(TacticDirectoryResolver.DiskTactic::fileName)
                .contains("custom.fmf");
        assertThat(resolver.contains(tactics.resolve("custom.fmf"))).isTrue();
        assertThat(resolver.contains(home.resolve("elsewhere.fmf"))).isFalse();
    }

    @Test
    void mergesTacticsFromSeveralLibraries(@TempDir Path first, @TempDir Path second) throws Exception {
        Files.write(first.resolve("a.fmf"), new byte[]{1});
        Files.write(second.resolve("b.fmf"), new byte[]{1, 2});

        TacticDirectoryResolver resolver = new TacticDirectoryResolver(List.of(first, second));

        assertThat(resolver.listTactics())
                .extracting(TacticDirectoryResolver.DiskTactic::fileName)
                .containsExactlyInAnyOrder("a.fmf", "b.fmf");
    }

    @Test
    void returnsEmptyLibrariesWhenVdfIsMissing(@TempDir Path home) {
        assertThat(TacticDirectoryResolver.parseLibraryFolders(home.resolve("libraryfolders.vdf"))).isEmpty();
    }
}
