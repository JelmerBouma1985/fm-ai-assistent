package com.github.fmaiassistent.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.*;

/** Native16D2510/86A30 plus original UI/managed registration evidence, checkpoint41. */
public record ClubFinancialLedgerLayout(int financeRel, long baseVtableRva, long fullVtableRva,
        int ownerRel, int fullBytes, int blocksRel, int blockStride, int blockCount,
        int recordRel, int recordBytes, int tailRel, int tailBytes, int slotCount, int slotBytes,
        long clockRva, int clockBytes) {
    public static final String MODULE_SHA256="bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";

    public static ClubFinancialLedgerLayout current() {
        return new ClubFinancialLedgerLayout(0x150,0x4CF07F8L,0x4CF0CC8L,-1,0x8B0,0x248,0x118,5,
                0x30,0xE4,0x114,4,57,4,0x56AD378L,4);
    }
    public int payloadBytes(){return blockCount*(recordBytes+tailBytes);}
    public int blockWindowBytes(){return blockCount*blockStride;}

    public Profile decode(byte[] raw,int clock) {
        if(raw.length!=payloadBytes())throw new IllegalArgumentException("Financial ledger record extent anchor failed");
        int year=(clock>>>16)&0xFFFF,day=clock&0x1FF;
        if(year==0||day==0||year==1900&&day==1)throw new IllegalArgumentException("Financial ledger clock anchor failed");
        final LocalDate date;
        try{date=LocalDate.ofYearDay(year,day);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Financial ledger clock anchor failed",invalid);}
        var records=new ArrayList<Period>();
        for(int p=0;p<4;p++) {
            var b=ByteBuffer.wrap(raw,p*(recordBytes+tailBytes),recordBytes).slice().order(ByteOrder.LITTLE_ENDIAN);
            var slots=new ArrayList<Integer>();for(int s=0;s<slotCount;s++)slots.add(b.getInt(s*slotBytes));
            records.add(new Period(PeriodName.values()[p],slots));
        }
        return new Profile(date,records,HexFormat.of().formatHex(raw));
    }
    public static Optional<Profile> values(String hex,Integer clock,String sourceSha256) {
        var l=current();
        if((!MODULE_SHA256.equals(sourceSha256))||hex==null||clock==null||hex.length()!=l.payloadBytes()*2)return Optional.empty();
        try{return Optional.of(l.decode(HexFormat.of().parseHex(hex),clock));}
        catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return Optional.empty();}
    }
    public enum PeriodName {
        THIS_MONTH("This month"),LAST_MONTH("Last month"),THIS_SEASON("This season"),LAST_SEASON("Last season");
        private final String label;PeriodName(String label){this.label=label;}public String label(){return label;}
    }
    public enum Category {
        GATE_RECEIPTS("Gate receipts",true,39),SEASON_TICKETS("Season tickets",true,41),TV_REVENUE("TV revenue",true,53),
        MERCHANDISING("Merchandising",true,44),PLAYERS_SOLD("Players sold",true,45),PRIZE_MONEY("Prize money",true,52),
        INVESTMENTS("Investments",true,43),INTEREST("Interest",true,42),SPONSORSHIP("Sponsorship",true,46),
        MATCH_DAY_INCOME("Match day income",true,47),GRANTS("Grants",true,50),FUND_RAISING("Fund raising",true,48),
        AFFILIATE_INCOME("Affiliate / B club income",true,51),OTHER_INCOME("Other income",true,40),
        CORPORATE_INCOME("Corporate facilities income",true,54),SOLIDARITY("Solidarity payments",true,55),LOAN_INCOME("Loan income",true,56),
        PLAYER_WAGES("Player wages",false,4),BONUSES("Bonuses",false,7),LOYALTY_BONUSES("Loyalty bonuses",false,8),
        STAFF_WAGES("Staff wages",false,5),NON_FOOTBALL("Non-football costs",false,31),DIRECTOR_EMOLUMENTS("Director emoluments",false,30),
        TRANSFER_EXPENDITURE("Transfer expenditure",false,9),AGENT_FEES("Agent and intermediary fees",false,32),
        MATCH_DAY_EXPENSES("Match day expenses",false,29),GROUND_MAINTENANCE("Ground maintenance",false,3),LEAGUE_FINES("League fines",false,2),
        YOUTH_SETUP("Youth setup",false,33),SCOUTING("Scouting costs",false,34),DIVIDENDS("Dividends",false,6),
        LOAN_REPAYMENTS("Loan repayments and interest",false,1),TAX("Tax",false,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28),
        AFFILIATE_EXPENDITURE("Affiliate / B club expenditure",false,36),TRAVEL("Travel costs",false,35),INSURANCE("Insurance",false,37),
        LOAN_FEES("Loan fees",false,38),OTHER_EXPENDITURE("Other expenditure",false,0);
        private final String label;private final boolean income;private final List<Integer> slots;
        Category(String label,boolean income,Integer... slots){this.label=label;this.income=income;this.slots=List.of(slots);}
        public String label(){return label;}public boolean income(){return income;}public List<Integer> slots(){return slots;}
    }
    /** Native213A470 labels; FULL_FINANCE::slot230 books type t into TAX.slots[t].
     * Generic Other codes intentionally retain distinct identities, not guessed statutory names. */
    public enum TaxType {
        OTHER_0(0,"Other"),INCOME_TAX(1,"Income tax"),EMPLOYER_TAX(2,"Employer tax"),
        CORPORATION_TAX(3,"Corporation tax"),VAT(4,"VAT"),NI_EMPLOYEE(5,"NI Employee"),NI_EMPLOYER(6,"NI Employer"),
        PENSION_EMPLOYEE(7,"Pension Employee"),PENSION_EMPLOYER(8,"Pension Employer"),TRANSFER_TAX(9,"Transfer Tax"),
        INTERNAL_TRANSFER_TAX(10,"Internal Transfer Tax"),EXTERNAL_TRANSFER_TAX(11,"External Transfer Tax"),
        OTHER_12(12,"Other"),OTHER_13(13,"Other"),TICKET_ENTERTAINMENT_TAX(14,"Ticket Entertainment Tax"),
        OTHER_15(15,"Other"),BRAZILIAN_TICKET_TAX(16,"Brazilian Ticket Tax"),
        FOREIGN_PLAYER_TRANSFER_TAX(17,"Foreign Player Transfer Tax"),DOMESTIC_PLAYER_TRANSFER_TAX(18,"Domestic Player Transfer Tax");
        private final int code;private final String label;
        TaxType(int code,String label){this.code=code;this.label=label;}
        public int code(){return code;}public String label(){return label;}
    }
    public record Period(PeriodName name,List<Integer> rawSlots) {
        public Period {rawSlots=List.copyOf(rawSlots);if(rawSlots.size()!=57)throw new IllegalArgumentException("Financial category slot count");}
        /** Tax uses native32-bit additions; preserve two's-complement behavior. */
        public int amount(Category category){int sum=0;for(int s:category.slots())sum+=rawSlots.get(s);return sum;}
        public int taxAmount(TaxType type){return rawSlots.get(Category.TAX.slots().get(type.code()));}
        public int totalIncome(){return total(true,false);}
        public int turnover(){return total(true,true);}
        public int totalExpenditure(){return total(false,false);}
        private int total(boolean income,boolean turnover){long sum=0;for(int i=income?39:0;i<(income?57:39);i++)
            if(i!=49&&(!turnover||i!=43&&i!=45))sum+=rawSlots.get(i);
            return (int)Math.min(Integer.MAX_VALUE,sum);}
    }
    /** Raw payload preserves all five records, slot49,19individual Tax fields and all five4-byte tails. */
    public record Profile(LocalDate capturedDate,List<Period> periods,String rawHex) {
        public Profile {periods=List.copyOf(periods);}
    }
}
