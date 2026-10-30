package com.github.fmaiassistent.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Native16E9450/16FEAD0/16FEFD0, shipped English keys and independent477-record capture. */
public record ClubFinancialLoanLayout(int clubDataRel, int vectorRel, int headerBytes, int pointerStride,
        int recordBytes, int maximumRecords, int principalRel, int paymentRel, int interestRel,
        int promotionRel, int maturityRel, int startRel, int kindRel, int flagRel, int modeRel,
        int unassignedRel, long clockRva, int clockBytes) {
    public static final String MODULE_SHA256="eb6c86fab56e051fe41482c7b93d8cb0af716a1cc2c6197ce1babbff68372bb8";
    public static final String FM27_MODULE_SHA256=ClubFinanceLayout.FM27_MODULE_SHA256;
    public static ClubFinancialLoanLayout current() {
        return new ClubFinancialLoanLayout(0x100,0x48,24,8,28,512,0,4,8,12,16,20,24,25,26,27,0x4DF3C18L,4);
    }
    public static ClubFinancialLoanLayout fm27() {
        return new ClubFinancialLoanLayout(0x100,0x50,24,8,28,512,0,4,8,12,16,20,24,25,26,27,0x56AD378L,4);
    }

    public Profile decode(byte[] raw,int clock) {
        if(raw.length%recordBytes!=0||raw.length/recordBytes>maximumRecords)
            throw new IllegalArgumentException("Financial loan record extent/count anchor failed");
        LocalDate capturedDate=date(clock);
        if(capturedDate==null)throw new IllegalArgumentException("Financial loan clock anchor failed");
        var loans=new ArrayList<Loan>();
        for(int offset=0;offset<raw.length;offset+=recordBytes) {
            var b=ByteBuffer.wrap(raw,offset,recordBytes).slice().order(ByteOrder.LITTLE_ENDIAN);
            int principal=b.getInt(principalRel),payment=b.getInt(paymentRel),interest=b.getInt(interestRel);
            int kind=Byte.toUnsignedInt(b.get(kindRel)),mode=Byte.toUnsignedInt(b.get(modeRel));
            int maturity=b.getInt(maturityRel),start=b.getInt(startRel);
            LocalDate due=date(maturity),repaymentsStart=date(start);
            Integer remaining=null,remainingInterest=null;
            if(kind<=16&&mode<=1&&principal>=0&&payment>=0&&interest>=0) {
                //1900-01-01 is an unavailable date in the display, but remains the native arithmetic operand.
                LocalDate calculationDue=arithmeticDate(maturity);
                if(calculationDue!=null) {
                    long days=ChronoUnit.DAYS.between(capturedDate,calculationDue);
                    long periods=days<=0?0:Math.max(1,days/30);
                    boolean conditional=kind<=14&&((0x6D00>>>kind)&1)!=0;
                    long principalValue=conditional||mode!=0?principal:((long)payment-interest)*periods;
                    long interestValue=kind==13?interest:periods*interest;
                    // Native signed32 multiplication can wrap: do not publish an unsafe calculated value.
                    if(principalValue>=0&&principalValue<=Integer.MAX_VALUE&&interestValue>=0&&interestValue<=Integer.MAX_VALUE) {
                        remaining=(int)Math.min(principal,principalValue);remainingInterest=(int)interestValue;
                    }
                }
            }
            loans.add(new Loan(principal,payment,interest,b.getInt(promotionRel),repaymentsStart,due,kind,
                    Byte.toUnsignedInt(b.get(flagRel)),mode,Byte.toUnsignedInt(b.get(unassignedRel)),
                    remaining,remainingInterest,HexFormat.of().formatHex(raw,offset,offset+recordBytes)));
        }
        return new Profile(capturedDate,loans);
    }

    public static Optional<Profile> values(String hex,Integer clock,String sourceSha256) {
        var layout=current();
        if((!MODULE_SHA256.equals(sourceSha256)&&!FM27_MODULE_SHA256.equals(sourceSha256))||hex==null||clock==null
                ||hex.length()>layout.maximumRecords*layout.recordBytes*2||hex.length()%(layout.recordBytes*2)!=0)
            return Optional.empty();
        try{return Optional.of(layout.decode(HexFormat.of().parseHex(hex),clock));}
        catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return Optional.empty();}
    }

    private static LocalDate arithmeticDate(int raw) {
        if(raw==0||raw==-1)return null;
        int year=(raw>>>16)&0xFFFF,day=raw&0x1FF;
        if(year==0||day==0)throw new IllegalArgumentException("Financial loan date range anchor failed");
        try{return LocalDate.ofYearDay(year,day);}
        catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Financial loan date range anchor failed",invalid);}
    }
    private static LocalDate date(int raw) {
        LocalDate value=arithmeticDate(raw);
        return value!=null&&value.getYear()==1900&&value.getDayOfYear()==1?null:value;
    }
    public record Profile(LocalDate capturedDate,List<Loan> loans) {
        public Profile{loans=List.copyOf(loans);}
    }
    public record Loan(int originalPrincipal,int scheduledPayment,int interestComponent,int promotionRepayment,
            LocalDate repaymentsStart,LocalDate maturity,int kind,int rawFlag,int paymentMode,int unassignedByte,
            Integer remainingPrincipal,Integer remainingInterest,String rawHex) {
        public boolean interestOnly(){return paymentMode==1;}
        public Integer payableAmount(){return kind>16||paymentMode>1?null:interestOnly()?interestComponent:scheduledPayment;}
        public String typeName(){return switch(kind){
            case 0->"Loan";case 1->"Bank loan";case 2->"Club official loan";case 3->"Company voluntary agreement";
            case 4->"Players union loan";case 5->"State loan";case 6->"Supporters' trust loan";case 7->"Gift loan";
            case 8->"Gift loan repayable on departure";case 9->"Gift loan (non-repayable)";
            case 10->"Loan repayable on promotion to the top division";case 11->"Loan repayable when established in the top division";
            case 12->"Club director loan";case 13->"PIK loan repayable at maturity";case 14->"Immediate repayment on promotion";
            case 15->"Miscellaneous debt";case 16->"Mortgage loan";default->"Unknown loan type ("+kind+")";};}
    }
}
