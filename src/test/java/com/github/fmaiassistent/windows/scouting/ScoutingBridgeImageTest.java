package com.github.fmaiassistent.windows.scouting;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingBridgeImageTest {
    @Test
    void trackedImageMatchesSourceAndPatchesFullWidthControlPointer() throws Exception {
        var image = ScoutingBridgeImage.load();
        long control = 0x27B12340000L;
        byte[] relocated = image.relocate(0x27B98760000L, control);
        assertThat(ByteBuffer.wrap(relocated).order(ByteOrder.LITTLE_ENDIAN)
                .getLong(image.offset("control_address"))).isEqualTo(control);
        assertThat(image.offset("bridge_proc")).isLessThan(image.size());
        assertThat(image.offset("bridge_install")).isLessThan(image.size());
        assertThat(image.offset("bridge_uninstall")).isLessThan(image.size());

        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/native/scouting/scouting-bridge.properties")) {
            byte[] bytes = input.readNBytes(65_537);
            assertThat(bytes.length).isLessThanOrEqualTo(65_536);
            properties.load(new ByteArrayInputStream(bytes));
        }
        byte[] source;
        try (var input = Files.newInputStream(Path.of("src/main/native/scouting/scouting_bridge.c"))) {
            source = input.readNBytes(65_537);
        }
        assertThat(source.length).isLessThanOrEqualTo(65_536);
        byte[] normalized = new String(source, StandardCharsets.UTF_8).replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(properties.getProperty("sourceSha256")).isEqualTo(ScoutingBridgeImage.sha256(normalized));
        assertThat(properties.getProperty("relocations")).isEmpty();
    }
}
