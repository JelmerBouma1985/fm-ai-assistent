package com.github.fmaiassistent.memory;

import java.nio.*;
import java.time.*;
import java.util.*;

/** Exact-build history consumer A9B2D, scalar getters16640C0/1664A90 and complete calendar leaves.
 * Durable format v1 replaces transient pointers with presence bytes, retaining all child bytes.
 */
public record ClubFinancialHistoryLayout(ClubFinancialLedgerLayout ledger,int summaryRel,int baseRingRel,
        int headerBytes,int countRel,int pointersRel,int pointerBytes,int cursorRel,int capacity,
        int summaryBytes,int baseBytes,int balanceRel,int remainingRel,int wagesRel,int scoutingRel) {
    public static ClubFinancialHistoryLayout current(){return new ClubFinancialHistoryLayout(
            ClubFinancialLedgerLayout.current(),0x40,0x230,0x1F0,0,8,8,0x1E8,60,48,228,0,8,16,136);}
    public int maximumPayloadBytes(){return 1+16+capacity*(1+summaryBytes)+1+16+capacity*(1+baseBytes);}
    public Profile decode(byte[] raw,int clock) {
        if(raw.length<18||raw.length>maximumPayloadBytes())throw new IllegalArgumentException("Financial history extent anchor failed");
        LocalDate date;
        try{date=LocalDate.ofYearDay((clock>>>16)&65535,clock&511);
            if(date.getYear()==0||date.equals(LocalDate.of(1900,1,1)))throw new DateTimeException("Unavailable clock");
        }catch(DateTimeException invalid){throw new IllegalArgumentException("Financial history clock anchor failed",invalid);}
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if(Byte.toUnsignedInt(b.get())!=1)throw new IllegalArgumentException("Financial history version anchor failed");
            Ring summary=ring(b,summaryBytes);int present=Byte.toUnsignedInt(b.get());
            if(present>1)throw new IllegalArgumentException("Financial history presence anchor failed");
            Ring expenditure=present==1?ring(b,baseBytes):null;
            if(b.hasRemaining())throw new IllegalArgumentException("Trailing financial history bytes");
            int encoded=(clock&65535)>>>9,hhmm=encoded==0?0:encoded==127?1755:
                    600+100*((encoded-1)/4)+15*((encoded-1)%4);
            YearMonth last=YearMonth.from(date).minusMonths(date.getDayOfMonth()==1&&hhmm<=1199?2:1);
            int baseCount=expenditure==null?summary.count():Math.min(summary.count(),expenditure.count());
            return new Profile(date,summary,expenditure,points(summary,summary.count(),balanceRel,last),
                    points(summary,summary.count(),remainingRel,last),points(expenditure,baseCount,wagesRel,last),
                    points(expenditure,baseCount,scoutingRel,last),HexFormat.of().formatHex(raw));
        }catch(BufferUnderflowException invalid){throw new IllegalArgumentException("Truncated financial history",invalid);}
    }
    private Ring ring(ByteBuffer b,int bytes) {
        int count=Byte.toUnsignedInt(b.get()),cursor=Byte.toUnsignedInt(b.get());
        if(count>capacity||cursor>=capacity)throw new IllegalArgumentException("Financial history count/cursor anchor failed");
        byte[] prefix=new byte[7],suffix=new byte[7];b.get(prefix).get(suffix);var members=new ArrayList<Member>();
        for(int i=0;i<count;i++){int present=Byte.toUnsignedInt(b.get());if(present>1)throw new IllegalArgumentException("Financial history member presence anchor failed");
            String hex=null;if(present==1){byte[] data=new byte[bytes];b.get(data);hex=HexFormat.of().formatHex(data);}
            members.add(new Member(i,(cursor+i)%capacity,hex));}
        return new Ring(count,cursor,HexFormat.of().formatHex(prefix),HexFormat.of().formatHex(suffix),members);
    }
    private List<Point> points(Ring ring,int count,int offset,YearMonth last) {
        var points=new ArrayList<Point>();for(int i=0;i<count;i++) {
            String hex=ring==null?null:ring.members().get(i).rawHex();Integer amount=hex==null?null:
                    ByteBuffer.wrap(HexFormat.of().parseHex(hex)).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
            points.add(new Point(last.minusMonths(count-1L-i).atDay(1),amount));
        }return List.copyOf(points);
    }
    public static Optional<Profile> values(String hex,Integer clock,String sha) {
        var l=current();if(!ClubFinancialLedgerLayout.MODULE_SHA256.equals(sha)||hex==null||clock==null
                ||hex.length()<36||hex.length()>2*l.maximumPayloadBytes()||hex.length()%2!=0)return Optional.empty();
        try{return Optional.of(l.decode(HexFormat.of().parseHex(hex),clock));}catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    public record Member(int ordinal,int physicalSlot,String rawHex) { }
    public record Ring(int count,int cursor,String unassignedPrefixHex,String unassignedSuffixHex,List<Member> members) {
        public Ring {members=List.copyOf(members);}
    }
    /** Null amount preserves an absent source even though the native graph can render it as zero. */
    public record Point(LocalDate month,Integer amount) { }
    public record Profile(LocalDate capturedDate,Ring summary,Ring expenditure,List<Point> balances,
            List<Point> transferBudgets,List<Point> playerWages,List<Point> scouting,String rawHex) { }
}
