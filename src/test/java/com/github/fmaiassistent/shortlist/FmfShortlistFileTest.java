package com.github.fmaiassistent.shortlist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FmfShortlistFileTest {
    private final FmfShortlistFile fmf = new FmfShortlistFile();

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
        assertThat(decoded.databaseVersion()).isEqualTo(6455715L);
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
