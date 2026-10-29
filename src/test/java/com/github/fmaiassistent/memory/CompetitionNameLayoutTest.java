package com.github.fmaiassistent.memory;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
class CompetitionNameLayoutTest {
    final CompetitionNameLayout l=CompetitionNameLayout.current();
    static String raw(String text){byte[] b=text.getBytes(StandardCharsets.UTF_8);return HexFormat.of().formatHex(ByteBuffer.allocate(4+b.length).order(ByteOrder.LITTLE_ENDIAN).putInt(b.length).put(b).array());}
    @Test void strictUtf8UsesByteLengthPreservesNullAndEmptyAndFallsBackOnlyForDisplay() {
        assertThat(l.decode(raw("São Tomé"))).isEqualTo("São Tomé");assertThat(l.decode((String)null)).isNull();assertThat(l.decode(raw(""))).isEmpty();
        assertThat(l.decode(raw(" "),raw("Short" )).display()).contains("Short");assertThat(l.decode(null,null).display()).isEmpty();
    }
    @Test void malformedLengthEncodingTrailingBytesAndEmbeddedNulReject() {
        for(String hex:new String[]{"00","ffffffff","0000000001","02000000c328",raw("bad\0name"),"01040000","not-hex"})
            assertThatThrownBy(()->l.decode(hex)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->l.decode(raw("x".repeat(1025)))).isInstanceOf(IllegalArgumentException.class);
    }
}
