package com.github.fmaiassistent.shortlist;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortlistFileServiceTest {
    @TempDir
    Path outputDirectory;

    @Test
    void resolvesLoadedPlayersAndCreatesNonOverwritingFmfFiles() throws Exception {
        FmfShortlistFile fmf = new FmfShortlistFile();
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        when(players.findAllPlayerEntities()).thenReturn(List.of(
                player("Brayley Lipman", 2002082558L),
                player("Sasa Zivadinovic", 2002097318L)));
        ShortlistFileService service = new ShortlistFileService(fmf, players, outputDirectory);

        ShortlistFileService.CreatedShortlist first = service.create(
                "AI / targets", List.of(2002097318L, 2002082558L));
        ShortlistFileService.CreatedShortlist second = service.create(
                "AI / targets", List.of(2002082558L));

        assertThat(first.path().getFileName().toString()).isEqualTo("AI _ targets.fmf");
        assertThat(second.path().getFileName().toString()).isEqualTo("AI _ targets-2.fmf");
        assertThat(first.players()).containsExactly("Sasa Zivadinovic", "Brayley Lipman");
        assertThat(fmf.read(Files.readAllBytes(first.path())).playerUniqueIds())
                .containsExactly(2002097318L, 2002082558L);
    }

    @Test
    void rejectsIdsThatAreNotInTheLoadedSave() {
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        when(players.findAllPlayerEntities()).thenReturn(List.of(player("Known", 123L)));
        ShortlistFileService service = new ShortlistFileService(new FmfShortlistFile(), players, outputDirectory);

        assertThatThrownBy(() -> service.create("targets", List.of(999L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void explainsThatOldLoadedDataMustBeReloaded() {
        PlayerDatabaseService players = mock(PlayerDatabaseService.class);
        when(players.findAllPlayerEntities()).thenReturn(List.of(player("Old row", 0L)));
        ShortlistFileService service = new ShortlistFileService(new FmfShortlistFile(), players, outputDirectory);

        assertThatThrownBy(() -> service.create("targets", List.of(123L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Load data again");
    }

    private static PlayerEntity player(String name, long uniqueId) {
        Map<String, Object> row = new HashMap<>();
        PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("name", name);
        row.put("unique_id", uniqueId);
        return PlayerEntity.fromExportRow(row);
    }
}
