package com.github.fmaiassistent.domain;

/** Canonical native identity. A club can have several competition/gender views.
 * The prefix is diagnostic data, not the object's complete allocation or a durable pointer chain. */
public record NativeClubSnapshot(long uniqueId, int recordIndex, String kind, String name,
                                 long sourceAddress, String prefixHex, String uefaCoefficientHex, Integer uefaRuleVersion,
                                 String financialLoansHex, Integer financialLoanClock,
                                 String financialLedgerHex, Integer financialLedgerClock,String financialCompetitionsHex,
                                 String financialHistoryHex) {
    public NativeClubSnapshot(long uniqueId,int recordIndex,String kind,String name,long sourceAddress,String prefixHex,
                              String uefaCoefficientHex,Integer uefaRuleVersion,String financialLoansHex,Integer financialLoanClock,
                              String financialLedgerHex,Integer financialLedgerClock,String financialCompetitionsHex) {
        this(uniqueId,recordIndex,kind,name,sourceAddress,prefixHex,uefaCoefficientHex,uefaRuleVersion,
                financialLoansHex,financialLoanClock,financialLedgerHex,financialLedgerClock,financialCompetitionsHex,null);
    }
    public NativeClubSnapshot(long uniqueId,int recordIndex,String kind,String name,long sourceAddress,String prefixHex,
                              String uefaCoefficientHex,Integer uefaRuleVersion,String financialLoansHex,Integer financialLoanClock,
                              String financialLedgerHex,Integer financialLedgerClock) {
        this(uniqueId,recordIndex,kind,name,sourceAddress,prefixHex,uefaCoefficientHex,uefaRuleVersion,
                financialLoansHex,financialLoanClock,financialLedgerHex,financialLedgerClock,null);
    }
    public NativeClubSnapshot(long uniqueId,int recordIndex,String kind,String name,long sourceAddress,String prefixHex,
                              String uefaCoefficientHex,Integer uefaRuleVersion,String financialLoansHex,Integer financialLoanClock) {
        this(uniqueId,recordIndex,kind,name,sourceAddress,prefixHex,uefaCoefficientHex,uefaRuleVersion,financialLoansHex,financialLoanClock,null,null);
    }
    public NativeClubSnapshot(long uniqueId, int recordIndex, String kind, String name, long sourceAddress,
                              String prefixHex, String uefaCoefficientHex, Integer uefaRuleVersion) {
        this(uniqueId,recordIndex,kind,name,sourceAddress,prefixHex,uefaCoefficientHex,uefaRuleVersion,null,null);
    }
    public NativeClubSnapshot(long uniqueId, int recordIndex, String kind, String name, long sourceAddress, String prefixHex) {
        this(uniqueId, recordIndex, kind, name, sourceAddress, prefixHex, null, null);
    }
}
