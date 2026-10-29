package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerRecordLayout;
import com.github.fmaiassistent.player.AttributeDefinitions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the current layout against a bounded capture of a named FM26 player. */
class Fm26CapturedPlayerEvidenceTest {
    private static final Path CAPTURE = Path.of(
            "docs/reverse-engineering/fm26/evidence/sierd_raw_bytes_20261013.json");

    @Test
    void currentLayoutDecodesIndependentNamedPlayerCapture() throws Exception {
        var json = JsonMapper.builder().build().readTree(Files.readString(CAPTURE));
        byte[] player = HexFormat.ofDelimiter(" ").parseHex(json.get("playerHex").asText());
        byte[] team = HexFormat.ofDelimiter(" ").parseHex(json.get("teamHex").asText());
        assertThat(player).hasSize(968);
        assertThat(team).hasSize(184);

        long playerAddress = Long.decode(json.get("playerAddress").asText());
        long teamAddress = Long.decode(json.get("teamAddress").asText());
        int recordOffset = PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET;
        var layout = PlayerRecordLayout.current().direct();

        assertThat(u32(player, recordOffset + 8)).isEqualTo(91_481);
        assertThat(u32(player, recordOffset + layout.uniqueIdRel())).isEqualTo(2_002_079_297L);
        assertThat(u16(player, recordOffset + layout.currentAbilityRel())).isEqualTo(160);
        assertThat(u16(player, recordOffset + layout.potentialAbilityRel())).isEqualTo(160);
        assertThat(u64(player, 0x130)).isEqualTo(teamAddress);
        assertThat(u64(team, 0x38)).isNotZero();

        int passingOffset = recordOffset + layout.historyCopySourceRel()
                + 0x40 - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET;
        assertThat(passingOffset).isEqualTo(0x166);
        int rawPassing = Byte.toUnsignedInt(player[passingOffset]);
        assertThat(rawPassing).isEqualTo(73);
        assertThat(AttributeDefinitions.norm20(rawPassing)).isEqualTo(15);
        assertThat(AttributeDefinitions.norm20(Byte.toUnsignedInt(player[passingOffset + 1])))
                .isNotEqualTo(15);
        assertThat(playerAddress).isPositive();
    }

    private static int u16(byte[] bytes, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static long u64(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
