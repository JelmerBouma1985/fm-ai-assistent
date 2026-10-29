package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.assertj.core.api.Assertions.*;

class ClubFinancialLoanLayoutTest {
    static final ClubFinancialLoanLayout L=ClubFinancialLoanLayout.current();static final int CLOCK=0x07F41A8D;
    @Test void normalInterestOnlyPikConditionalAndGiftCalculationsKeepTheirOwnSemantics() {
        assertThat(decode(1,0,100000,1000,100).remainingPrincipal()).isEqualTo(100000);
        var interestOnly=decode(1,1,100000,1000,100);assertThat(interestOnly.interestOnly()).isTrue();
        assertThat(interestOnly.remainingPrincipal()).isEqualTo(100000);assertThat(interestOnly.remainingInterest()).isEqualTo(23100);
        assertThat(interestOnly.payableAmount()).isEqualTo(100);assertThat(interestOnly.scheduledPayment()).isEqualTo(1000);
        var pik=decode(13,0,100000,120000,20000);assertThat(pik.remainingPrincipal()).isEqualTo(100000);assertThat(pik.remainingInterest()).isEqualTo(20000);
        assertThat(decode(10,0,100000,0,0).remainingPrincipal()).isEqualTo(100000);
        assertThat(decode(9,0,100000,0,0).remainingPrincipal()).isZero();
    }
    @Test void unknownKindsModesOverflowAndLegacyMissingProfilesRemainUnavailable() {
        assertThat(decode(255,0,100000,1000,100).remainingPrincipal()).isNull();
        assertThat(decode(1,2,100000,1000,100).remainingInterest()).isNull();
        assertThat(decode(1,0,Integer.MAX_VALUE,Integer.MAX_VALUE,1).remainingPrincipal()).isNull();
        assertThat(ClubFinancialLoanLayout.values(null,null,L.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLoanLayout.values("",CLOCK,"other-build")).isEmpty();
        assertThat(ClubFinancialLoanLayout.values("00",CLOCK,L.MODULE_SHA256)).isEmpty();
        assertThat(ClubFinancialLoanLayout.values("",CLOCK,L.MODULE_SHA256).orElseThrow().loans()).isEmpty();
    }
    private static ClubFinancialLoanLayout.Loan decode(int kind,int mode,int principal,int payment,int interest) {
        var b=ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,principal).putInt(4,payment).putInt(8,interest);
        b.putInt(16,0x0807328A).putInt(20,0x07EE3290).put(24,(byte)kind).put(26,(byte)mode);
        return L.decode(b.array(),CLOCK).loans().getFirst();
    }
}
