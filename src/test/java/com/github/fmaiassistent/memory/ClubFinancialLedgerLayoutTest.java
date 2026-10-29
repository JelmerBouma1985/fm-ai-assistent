package com.github.fmaiassistent.memory;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialLedgerLayoutTest {
    @Test void unavailableLegacyWrongHashCorruptPayloadAndClockAreDistinctFromObservedZeros() {
        var l=ClubFinancialLedgerLayout.current();String zeros="00".repeat(l.payloadBytes());
        assertThat(ClubFinancialLedgerLayout.values(null,null,l.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values(zeros,0x07F41A8D,"other-image")).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values(zeros.substring(2),0x07F41A8D,l.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values("zz".repeat(l.payloadBytes()),0x07F41A8D,l.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values(zeros,0,l.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values(zeros,0x07F401FF,l.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values(zeros,0x076C0001,l.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLedgerLayout.values(zeros,0x07F41A8D,l.MODULE_SHA256).orElseThrow().periods().getFirst().totalIncome()).isZero();
    }
    @Test void rawFifthUnknownSlotAndTailsSurviveWhileTotalsAndTaxFollowNativeWidths() {
        var l=ClubFinancialLedgerLayout.current();byte[] raw=new byte[l.payloadBytes()];var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(49*4,999999).putInt(10*4,Integer.MAX_VALUE).putInt(11*4,1).putInt(33*4,777).putInt(39*4,Integer.MAX_VALUE).putInt(40*4,1);
        b.putInt(4*232+49*4,123).putInt(228,456);
        var p=l.decode(raw,0x07F41A8D);assertThat(p.rawHex()).isEqualTo(HexFormat.of().formatHex(raw));assertThat(p.periods()).hasSize(4);
        var first=p.periods().getFirst();assertThat(first.amount(ClubFinancialLedgerLayout.Category.TAX)).isEqualTo(Integer.MIN_VALUE);
        assertThat(first.amount(ClubFinancialLedgerLayout.Category.YOUTH_SETUP)).isEqualTo(777);assertThat(first.totalIncome()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(()->first.rawSlots().set(0,1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
