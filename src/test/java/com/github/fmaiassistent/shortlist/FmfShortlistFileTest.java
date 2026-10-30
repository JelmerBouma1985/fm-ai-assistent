package com.github.fmaiassistent.shortlist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FmfShortlistFileTest {
    private final FmfShortlistFile fmf = new FmfShortlistFile();

    @Test
    void decodesFreshFm27ExportAndWritesItsDatabaseVersion() throws IOException {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/fm27/shortlist-export.fmf")) {
            assertThat(stream).isNotNull();
            bytes = stream.readNBytes(1025);
        }
        assertThat(bytes).hasSize(486);
        var exported = fmf.read(bytes);
        assertThat(exported.name()).isEqualTo("my_shortlist");
        assertThat(exported.databaseVersion()).isEqualTo(0x007b4924L);
        assertThat(exported.playerUniqueIds()).containsExactly(8428653L);
        var generated = fmf.read(fmf.write("FM27 verification", exported.playerUniqueIds()));
        assertThat(generated.databaseVersion()).isEqualTo(exported.databaseVersion());
        assertThat(generated.playerUniqueIds()).isEqualTo(exported.playerUniqueIds());
    }

    @Test
    void decodesRealFm26ShortlistFixture() throws IOException {
        byte[] bytes = getClass().getResourceAsStream("/my_shortlist.fmf").readAllBytes();

        FmfShortlistFile.Shortlist shortlist = fmf.read(bytes);

        assertThat(shortlist.name()).isEqualTo("my_shortlist");
        assertThat(shortlist.databaseVersion()).isEqualTo(6455715L);
        // Brayley Lipman, Sasa Zivadinovic and Romero Tagliaferro, in fixture order.
        assertThat(shortlist.playerUniqueIds()).containsExactly(2002082558L, 2002093222L, 2002081310L);
        assertThat(shortlist.resources()).containsExactly(
                "image.img", "my_shortlist.slf", "_data/details.aom");
    }

    @Test
    void writesAPlayerShortlistThatCanBeDecodedAgain() {
        List<Long> uniqueIds = List.of(7458500L, 2002082558L, 4_000_000_000L);

        byte[] bytes = fmf.write("AI targets", uniqueIds);
        FmfShortlistFile.Shortlist decoded = fmf.read(bytes);

        assertThat(bytes[25]).isEqualTo((byte) 3);
        assertThat(decoded.name()).isEqualTo("AI targets");
        assertThat(decoded.databaseVersion()).isEqualTo(8079652L);
        assertThat(decoded.playerUniqueIds()).containsExactlyElementsOf(uniqueIds);
        assertThat(decoded.resources()).containsExactly(
                "image.img", "AI targets.slf", "_data/details.aom");
    }

    @Test
    void rejectsAnArchiveWithoutTheFmResourceFormatMarker() {
        byte[] bytes = fmf.write("AI targets", List.of(2002082558L));
        bytes[25] = 0;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fmf.read(bytes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This is not a supported Football Manager FMF archive");
    }
}
