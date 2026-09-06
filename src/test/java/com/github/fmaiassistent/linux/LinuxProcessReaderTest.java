package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.MemoryRegion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinuxProcessReaderTest {
    @Test
    void skipsUnsignedKernelMappingsButKeepsReadableUserMappings() {
        String maps = """
                00012000-00110000 rw-p 00000000 00:00 0
                7fffffbb0000-7fffffff0000 rw-p 00000000 00:00 0
                ffffffffff600000-ffffffffff601000 --xp 00000000 00:00 0 [vsyscall]
                """;

        List<MemoryRegion> regions = LinuxProcessReader.parseMaps(maps);

        assertThat(regions).extracting(MemoryRegion::start)
                .containsExactly(0x12000L, 0x7fffffbb0000L);
        assertThat(regions).extracting(MemoryRegion::path)
                .containsExactly("", "");
    }

    @Test
    void ignoresMalformedOrBackwardsRangesWithoutLosingValidEntries() {
        String maps = """
                malformed r--p 00000000 00:00 0
                2000-1000 r--p 00000000 00:00 0
                4000-5000 r--p not-hex 00:00 0
                6000-7000 r-xp 00001000 08:01 42 /tmp/game_plugin.dll
                """;

        assertThat(LinuxProcessReader.parseMaps(maps))
                .containsExactly(new MemoryRegion(
                        0x6000L, 0x7000L, "r-xp", 0x1000L,
                        "08:01", "42", "/tmp/game_plugin.dll"));
    }
}
