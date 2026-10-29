package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.exporter.ClubFinancialLedgerCaptureTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Names come from the independent native switch/localization artifact, not application enums. */
class ClubTaxTypeTest {
    @Test void everyNameCodeAndSlotMatchesNativeFormatterAndWriterEvidence()throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_tax_slots_audit_20261027.json"))){bytes=in.readNBytes(1048577);}
        assertThat(bytes.length).isLessThanOrEqualTo(1048576);var evidence=JsonMapper.builder().build().readTree(bytes);
        assertThat(evidence.path("status").asText()).isEqualTo("PASS");var mapping=evidence.path("mapping");
        assertThat(ClubFinancialLedgerLayout.TaxType.values()).hasSize(19);var codes=new HashSet<Integer>();
        for(var type:ClubFinancialLedgerLayout.TaxType.values()){var source=mapping.get(type.code());
            assertThat(codes.add(type.code())).isTrue();assertThat(type.label()).isEqualTo(source.path("label").asText());
            assertThat(ClubFinancialLedgerLayout.Category.TAX.slots().get(type.code())).isEqualTo(source.path("recordSlot").asInt());}
        assertThat(Arrays.stream(ClubFinancialLedgerLayout.TaxType.values()).filter(t->t.label().equals("Other")).map(ClubFinancialLedgerLayout.TaxType::code)).containsExactly(0,12,13,15);
    }
    @Test void all51680CapturedTaxValuesAndTotalsSurviveWithRawFifthPeriodUnchanged()throws Exception {
        int values=0;for(var entry:ClubFinancialLedgerCaptureTest.financeRows().entrySet()) {
            var raw=HexFormat.of().parseHex(entry.getValue()[5]); // Independent full native8C0body.
            var payload=ClubFinancialLedgerCaptureTest.payload(entry.getValue());
            var p=ClubFinancialLedgerLayout.values(payload,ClubFinancialLedgerCaptureTest.CLOCK,ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
            int[] blocks={0x250,0x368,0x480,0x598};var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<4;i++){int sum=0;for(var type:ClubFinancialLedgerLayout.TaxType.values()) {
                int expected=b.getInt(blocks[i]+0x58+4*type.code());assertThat(p.periods().get(i).taxAmount(type)).isEqualTo(expected);sum+=expected;values++;}
                assertThat(p.periods().get(i).amount(ClubFinancialLedgerLayout.Category.TAX)).isEqualTo(sum);}
            assertThat(p.rawHex()).isEqualTo(payload);
        }assertThat(values).isEqualTo(51680);
    }
    @Test void genericCategoriesSignedRefundsZeroAndTaxOverflowRemainIndependent() {
        byte[] raw=new byte[ClubFinancialLedgerLayout.current().payloadBytes()];var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(10*4,Integer.MAX_VALUE).putInt(11*4,1).putInt(22*4,-7).putInt(23*4,7).putInt(25*4,123);
        var p=ClubFinancialLedgerLayout.current().decode(raw,0x07F41A8D).periods().getFirst();
        assertThat(p.taxAmount(ClubFinancialLedgerLayout.TaxType.OTHER_0)).isEqualTo(Integer.MAX_VALUE);
        assertThat(p.taxAmount(ClubFinancialLedgerLayout.TaxType.OTHER_12)).isEqualTo(-7);
        assertThat(p.taxAmount(ClubFinancialLedgerLayout.TaxType.OTHER_13)).isEqualTo(7);
        assertThat(p.taxAmount(ClubFinancialLedgerLayout.TaxType.OTHER_15)).isEqualTo(123);
        assertThat(p.taxAmount(ClubFinancialLedgerLayout.TaxType.VAT)).isZero();
        assertThat(p.amount(ClubFinancialLedgerLayout.Category.TAX)).isEqualTo(Integer.MIN_VALUE+123);
    }
}
