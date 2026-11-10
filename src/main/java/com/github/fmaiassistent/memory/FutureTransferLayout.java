package com.github.fmaiassistent.memory;

/** FM27 future transfer queries and offer records. */
public record FutureTransferLayout(int personIndices, int managerRoot, long managerVtable,
                                   int tableOffset, long fullOfferVtable, long loanOfferVtable,
                                   int offerPrefixSize, int person, int buyerTeam, int index,
                                   int date, int dateKind, int status, int exchangeVector, int exchangeStride) {

    /** Exact-image profile for FM27 27.0.1+2494687. */
    public static FutureTransferLayout forModule(String hash) {
        if (NativeModuleProfile.FM27_SHA256.equals(hash)) return current();
        NativeModuleProfile.require2701(hash);
        return new FutureTransferLayout(0x98,0x580FF30,0x4D908D8,8,0x5051CD8,0x4F26178,
                0x90,0x38,0x40,0x5C,0x7C,0x86,0x8D,0x90,0x18);

    }

    public static FutureTransferLayout current() {
        return new FutureTransferLayout(0x98,0x56F2EF0,0x4C8C898,8,0x4F392E8,0x4E195B8,
                0x90,0x38,0x40,0x5C,0x7C,0x86,0x8D,0x90,0x18);
    }
}
