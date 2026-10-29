package com.github.fmaiassistent.memory;

/** Exact EB6C86...BB8 native PERSON478/cancel-transfer query and UI producer2C27F20. */
public record FutureTransferLayout(int personIndices, int managerRoot, long managerVtable,
                                   int tableOffset, long fullOfferVtable, long loanOfferVtable,
                                   int offerPrefixSize, int person, int buyerTeam, int index,
                                   int date, int dateKind, int status, int exchangeVector, int exchangeStride) {
    public static FutureTransferLayout current() {
        return new FutureTransferLayout(0x98,0x4E37720,0x4473F28,8,0x470B128,0x45EBC88,
                0x90,0x38,0x40,0x5C,0x7C,0x86,0x8D,0x90,0x18);
    }
}
