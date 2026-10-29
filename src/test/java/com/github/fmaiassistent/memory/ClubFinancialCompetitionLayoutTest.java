package com.github.fmaiassistent.memory;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialCompetitionLayoutTest {
    static final ClubFinancialCompetitionLayout L=ClubFinancialCompetitionLayout.current();
    @Test void zeroAmountUnsignedUidRawSuffixAndUnnamedFifthPeriodArePreserved() {
        var b=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);b.putInt(1).putInt(-1).putInt(0).putInt(0x12345678);for(int i=1;i<10;i++)b.putInt(0);
        var profile=L.decode(b.array(),0x07F41A8D);var member=profile.periods().getFirst().tvRevenue().getFirst();
        assertThat(member.competitionUid()).isEqualTo(4294967295L);assertThat(member.amount()).isZero();assertThat(member.unassignedHex()).isEqualTo("78563412");
        assertThat(profile.periods().get(4).label()).isEqualTo("Unidentified native period");
        assertThatThrownBy(()->profile.periods().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ClubFinancialCompetitionLayout.values(profile.rawHex(),0x07F41A8D,ClubFinancialLedgerLayout.MODULE_SHA256)).isPresent();
        assertThat(ClubFinancialCompetitionLayout.values(profile.rawHex(),0x07F41A8D,"wrong-image")).isEmpty();
        assertThat(ClubFinancialCompetitionLayout.values(null,null,ClubFinancialLedgerLayout.MODULE_SHA256)).isEmpty();
    }
    @Test void malformedCountsTruncationTrailingBytesInvalidClockAndDuplicateIdentityReject() {
        for(byte[] raw:new byte[][]{new byte[39],new byte[41],ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(513).array(),ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array()})
            assertThatThrownBy(()->L.decode(raw,0x07F41A8D)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->L.decode(new byte[40],0)).isInstanceOf(IllegalArgumentException.class);
        var b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);b.putInt(2);for(int i=0;i<2;i++)b.putInt(29).putInt(-10).putInt(0);for(int i=1;i<10;i++)b.putInt(0);
        assertThat(ClubFinancialCompetitionLayout.values(HexFormat.of().formatHex(b.array()),0x07F41A8D,ClubFinancialLedgerLayout.MODULE_SHA256)).isEmpty();
    }
}
