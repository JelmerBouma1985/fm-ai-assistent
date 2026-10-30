package com.github.fmaiassistent.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.*;

/** Checkpoint43: native10A2CA50/10AEA080 plus independently captured COMP identities.
 * Persistent members replace the transient pointer with its unsigned32-bit COMP UID.
 * Amounts and the unassigned four native suffix bytes survive unchanged. */
public record ClubFinancialCompetitionLayout(ClubFinancialLedgerLayout ledger,long competitionRootRva,
        int registryHeaderRel,long competitionVtableRva,int competitionPrefixBytes,int competitionIndexRel,
        int competitionUidRel,int vectorCount,int headerBytes,int beginRel,int endRel,int capacityRel,
        int entryBytes,int competitionRel,int amountRel,int suffixRel,int suffixBytes,int maximumEntries) {

    public static ClubFinancialCompetitionLayout current(){return new ClubFinancialCompetitionLayout(
            ClubFinancialLedgerLayout.current(),0x57036E8L,0x80,0x4CDFAD8L,16,8,12,2,24,0,8,16,16,0,8,12,4,512);}
    public int maximumPayloadBytes(){return ledger.blockCount()*vectorCount*(4+maximumEntries*12);}

    public Profile decode(byte[] raw,int clock) {
        if(raw.length<ledger.blockCount()*vectorCount*4||raw.length>maximumPayloadBytes())
            throw new IllegalArgumentException("Financial competition payload extent anchor failed");
        LocalDate date=ledger.decode(new byte[ledger.payloadBytes()],clock).capturedDate();
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);var periods=new ArrayList<Period>();
        try{for(int p=0;p<ledger.blockCount();p++) {
            var kinds=new ArrayList<List<Member>>();
            for(int k=0;k<vectorCount;k++) {
                int count=b.getInt();if(count<0||count>maximumEntries||count>b.remaining()/12)
                    throw new IllegalArgumentException("Financial competition member count anchor failed");
                var members=new ArrayList<Member>();var ids=new HashSet<Long>();
                for(int i=0;i<count;i++){long uid=Integer.toUnsignedLong(b.getInt());int amount=b.getInt();byte[] suffix=new byte[suffixBytes];b.get(suffix);
                    if(uid==0||!ids.add(uid))throw new IllegalArgumentException("Financial competition identity anchor failed");
                    members.add(new Member(uid,amount,HexFormat.of().formatHex(suffix)));}
                kinds.add(List.copyOf(members));
            }
            periods.add(new Period(p,kinds.get(0),kinds.get(1)));
        }}catch(java.nio.BufferUnderflowException invalid){throw new IllegalArgumentException("Truncated financial competition payload",invalid);}
        if(b.hasRemaining())throw new IllegalArgumentException("Trailing financial competition payload");
        return new Profile(date,periods,HexFormat.of().formatHex(raw));
    }
    public static Optional<Profile> values(String hex,Integer clock,String sourceSha256) {
        var l=current();
        if((!ClubFinancialLedgerLayout.MODULE_SHA256.equals(sourceSha256))||hex==null||clock==null
                ||hex.length()>l.maximumPayloadBytes()*2||hex.length()%2!=0)return Optional.empty();
        try{return Optional.of(l.decode(HexFormat.of().parseHex(hex),clock));}catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    /** Durable collections must reconcile with the inline ledger captured in the same snapshot. */
    public static Optional<Profile> values(String hex,Integer clock,String sourceSha256,String ledgerHex) {
        var profile=values(hex,clock,sourceSha256);if(profile.isEmpty()||ledgerHex==null
                ||ledgerHex.length()!=current().ledger().payloadBytes()*2)return Optional.empty();
        try {
            var l=current().ledger();var raw=ByteBuffer.wrap(HexFormat.of().parseHex(ledgerHex)).order(ByteOrder.LITTLE_ENDIAN);
            for(var period:profile.orElseThrow().periods())for(int k=0;k<2;k++) {
                var members=k==0?period.tvRevenue():period.prizeMoney();int sum=0;for(var member:members)sum+=member.amount();
                var category=k==0?ClubFinancialLedgerLayout.Category.TV_REVENUE:ClubFinancialLedgerLayout.Category.PRIZE_MONEY;
                if(sum!=raw.getInt(period.index()*(l.recordBytes()+l.tailBytes())+category.slots().getFirst()*l.slotBytes()))return Optional.empty();
            }
            return profile;
        }catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    public record Member(long competitionUid,int amount,String unassignedHex) { }
    public record Period(int index,List<Member> tvRevenue,List<Member> prizeMoney) {
        public Period {tvRevenue=List.copyOf(tvRevenue);prizeMoney=List.copyOf(prizeMoney);}
        public String label(){return index<4?ClubFinancialLedgerLayout.PeriodName.values()[index].label():"Unidentified native period";}
    }
    public record Profile(LocalDate capturedDate,List<Period> periods,String rawHex) {
        public Profile {periods=List.copyOf(periods);}
    }
}
