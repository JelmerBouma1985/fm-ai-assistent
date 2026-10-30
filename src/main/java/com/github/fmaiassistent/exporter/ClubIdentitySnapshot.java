package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot;
import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.memory.ClubIdentityLayout;
import com.github.fmaiassistent.memory.ClubUefaCoefficientLayout;
import com.github.fmaiassistent.memory.ClubFinancialLoanLayout;
import com.github.fmaiassistent.memory.ClubFinancialLedgerLayout;
import com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout;
import com.github.fmaiassistent.memory.CompetitionNameLayout;
import com.github.fmaiassistent.memory.ClubFinancialHistoryLayout;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** One bounded capture, never cached across exports. Preserves all typed native identities, not only eligible views. */
final class ClubIdentitySnapshot {
    private static final int MAX_RECORDS=100_000;
    private final ProcessMemoryReader reader;
    private final long base,descriptor,header,begin;
    private final ClubIdentityLayout layout;
    private final byte[] rootBytes,headerPointer,bounds,pointers;
    private final List<NativeClubSnapshot> records;
    private final Map<Long,NativeClubSnapshot> byAddress;
    private final Map<Long,byte[]> coefficientFrames = new LinkedHashMap<>();
    private long competitionBegin=-1,competitionCount;
    private final Map<Long,Long> competitionUids=new HashMap<>(),competitionAddresses=new HashMap<>();
    private final CompetitionNameLayout nameLayout;
    private final Map<Long,NativeCompetitionNameSnapshot> competitionNames=new LinkedHashMap<>();

    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout)throws IOException {
        this(source,base,layout,null); // Identity-only diagnostics may use older captures.
    }

    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout,
                         ClubUefaCoefficientLayout uefa)throws IOException {
        this(source,base,layout,uefa,null);
    }
    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout,
                         ClubUefaCoefficientLayout uefa,ClubFinancialLoanLayout loans)throws IOException {
        this(source,base,layout,uefa,loans,null);
    }
    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout,
                         ClubUefaCoefficientLayout uefa,ClubFinancialLoanLayout loans,ClubFinancialLedgerLayout ledger)throws IOException {
        this(source,base,layout,uefa,loans,ledger,null);
    }
    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout,
                         ClubUefaCoefficientLayout uefa,ClubFinancialLoanLayout loans,ClubFinancialLedgerLayout ledger,
                         ClubFinancialCompetitionLayout competitions)throws IOException {
        this(source,base,layout,uefa,loans,ledger,competitions,null);
    }
    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout,
                         ClubUefaCoefficientLayout uefa,ClubFinancialLoanLayout loans,ClubFinancialLedgerLayout ledger,
                         ClubFinancialCompetitionLayout competitions,CompetitionNameLayout names)throws IOException {
        this(source,base,layout,uefa,loans,ledger,competitions,names,null);
    }
    ClubIdentitySnapshot(ProcessMemoryReader source,long base,ClubIdentityLayout layout,
                         ClubUefaCoefficientLayout uefa,ClubFinancialLoanLayout loans,ClubFinancialLedgerLayout ledger,
                         ClubFinancialCompetitionLayout competitions,CompetitionNameLayout names,
                         ClubFinancialHistoryLayout history)throws IOException {
        this.reader=new BudgetReader(source);this.base=base;this.layout=layout;
        PlayerSnapshotValidator.validateClubIdentityLayout(layout);
        if(history!=null){PlayerSnapshotValidator.validateClubFinancialHistoryLayout(history);
            if(!history.ledger().equals(ledger))throw new IOException("Financial history requires matching ledger layout anchor");}
        this.nameLayout=names;
        if(names!=null){PlayerSnapshotValidator.validateCompetitionNameLayout(names);
            if(competitions==null)throw new IOException("Competition name requires COMP identity layout anchor");}
        if(uefa!=null)PlayerSnapshotValidator.validateClubUefaLayout(uefa);
        if(loans!=null)PlayerSnapshotValidator.validateClubFinancialLoanLayout(loans);
        if(ledger!=null)PlayerSnapshotValidator.validateClubFinancialLedgerLayout(ledger);
        if(competitions!=null){PlayerSnapshotValidator.validateClubFinancialCompetitionLayout(competitions);
            if(!competitions.ledger().equals(ledger))throw new IOException("Financial competition requires matching ledger layout anchor");}
        Integer loanClock=loans==null?null:buffer(frame(base+loans.clockRva(),loans.clockBytes())).getInt();
        Integer ledgerClock=ledger==null?null:buffer(frame(base+ledger.clockRva(),ledger.clockBytes())).getInt();
        int rule=uefa==null?0:Short.toUnsignedInt(buffer(frame(base+uefa.ruleVersionRva(),uefa.ruleVersionBytes())).getShort());
        rootBytes=reader.readBytes(base+layout.registryRva(),8);descriptor=buffer(rootBytes).getLong();
        headerPointer=reader.readBytes(descriptor+layout.registryHeader(),8);header=buffer(headerPointer).getLong();
        bounds=reader.readBytes(header,24);begin=buffer(bounds).getLong();long end=buffer(bounds).getLong(8),cap=buffer(bounds).getLong(16);
        if(begin<0||end<begin||cap<end||cap>ProcessMemoryReader.MAX_USER_ADDRESS||(end-begin)%8!=0||(cap-begin)%8!=0
                ||(end-begin)/8>MAX_RECORDS||begin==0&&cap!=0)
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB registry bounds/count anchor failed");
        pointers=readSpan(begin,Math.toIntExact(end-begin));
        records=new ArrayList<>();byAddress=new HashMap<>();var uids=new HashSet<Long>();
        for(int i=0;i<pointers.length/8;i++) {
            if(Thread.currentThread().isInterrupted())throw new IOException("CLUB identity capture interrupted");
            long address=buffer(pointers).getLong(i*8);if(address==0)continue;
            byte[] prefix=reader.readBytes(address,layout.prefixBytes());
            long uid=PlayerSnapshotValidator.validateClubIdentity(i,prefix,base,layout);
            String kind=buffer(prefix).getLong()==base+layout.clubVtable()?"CLUB":"LEAGUE_BODY_CLUB";
            String coefficientHex=null;
            if(uefa!=null&&kind.equals("CLUB"))coefficientHex=coefficient(address,rule,uefa);
            String loanHex=loans!=null&&kind.equals("CLUB")?financialLoans(address,loanClock,loans):null;
            String ledgerHex=ledger!=null&&kind.equals("CLUB")?financialLedger(address,ledgerClock,ledger):null;
            String competitionHex=competitions!=null&&ledgerHex!=null?financialCompetitions(address,ledgerHex,ledgerClock,competitions):null;
            String historyHex=history!=null&&ledgerHex!=null?financialHistory(address,ledgerClock,history):null;
            var record=new NativeClubSnapshot(uid,i,kind,FmMemoryStrings.clubDisplayName(reader,address).orElse(null),
                    address,HexFormat.of().formatHex(prefix),coefficientHex,coefficientHex==null?null:rule,
                    loanHex,loanHex==null?null:loanClock,ledgerHex,ledgerHex==null?null:ledgerClock,competitionHex,historyHex);
            if(!uids.add(uid)||byAddress.put(address,record)!=null)
                throw new PlayerSnapshotValidator.ClubIdentityChangedException("Duplicate native CLUB identity");
            records.add(record);
        }
    }

    NativeClubSnapshot club(long address)throws IOException {
        var value=byAddress.get(address);
        if(value==null)throw new PlayerSnapshotValidator.ClubIdentityChangedException("TEAM owner is outside the captured CLUB registry");
        return value;
    }
    List<NativeClubSnapshot> records(){return List.copyOf(records);}
    List<NativeCompetitionNameSnapshot> competitionNames(){return nameLayout==null?null:List.copyOf(competitionNames.values());}
    long base(){return base;}
    ClubIdentityLayout layout(){return layout;}

    void verify()throws IOException {
        unchanged(base+layout.registryRva(),rootBytes);unchanged(descriptor+layout.registryHeader(),headerPointer);unchanged(header,bounds);
        if(pointers.length>0&&!Arrays.equals(pointers,readSpan(begin,pointers.length)))
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB registry slots changed during capture");
        for(var record:records)unchanged(record.sourceAddress(),HexFormat.of().parseHex(record.prefixHex()));
        for(var entry:coefficientFrames.entrySet())unchanged(entry.getKey(),entry.getValue());
        if(pointers.length>0&&!Arrays.equals(pointers,readSpan(begin,pointers.length)))
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB registry slots changed during verification");
        unchanged(base+layout.registryRva(),rootBytes);unchanged(descriptor+layout.registryHeader(),headerPointer);unchanged(header,bounds);
    }
    private byte[] readSpan(long address,int length)throws IOException {
        if(length>0)PlayerSnapshotValidator.validateContractPointer(address,0);
        byte[] data=new byte[length];
        for(int at=0;at<length;at+=65536)reader.readInto(address+at,data,at,Math.min(65536,length-at));
        return data;
    }
    private void unchanged(long address,byte[] old)throws IOException {
        if(!Arrays.equals(old,reader.readBytes(address,old.length)))
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB identity changed during capture");
    }
    private byte[] frame(long address,int length)throws IOException {
        byte[] value=reader.readBytes(address,length);byte[] prior=coefficientFrames.putIfAbsent(address,value);
        // A ledger block and its vector header share a start address. Compare their overlap,
        // retaining the larger window so verification still covers every previously captured byte.
        if(prior!=null) {
            int common=Math.min(prior.length,value.length);
            if(!Arrays.equals(prior,0,common,value,0,common))throw new IOException("CLUB context changed during capture");
            if(value.length>prior.length)coefficientFrames.put(address,value);
        }
        return value;
    }
    private String coefficient(long club,int rule,ClubUefaCoefficientLayout uefa)throws IOException {
        long data=buffer(frame(club+uefa.clubDataRel(),8)).getLong();
        long nation=buffer(frame(club+uefa.nationRel(),8)).getLong();
        int selector=Byte.toUnsignedInt(frame(club+uefa.selectorRel(),1)[0]);
        if(selector>1)throw new IOException("UEFA coefficient selector anchor failed");
        long context=nation==0?0:buffer(frame(nation+uefa.nationContextRel()+(long)selector*uefa.contextStride(),8)).getLong();
        if(data==0)return null;
        long pointer=buffer(frame(data+uefa.coefficientRel(),8)).getLong();
        if(pointer==0)return null;
        if(context==0)throw new IOException("Existing UEFA coefficient failed native availability guard");
        byte[] raw=frame(pointer,uefa.objectBytes());
        PlayerSnapshotValidator.validateClubUefaCoefficient(raw,rule,uefa);
        return HexFormat.of().formatHex(raw);
    }
    private static ByteBuffer buffer(byte[] raw){return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);}

    private String financialLoans(long club,int clock,ClubFinancialLoanLayout loans)throws IOException {
        long data=buffer(frame(club+loans.clubDataRel(),Long.BYTES)).getLong();
        if(data==0)return null;
        var h=buffer(frame(data+loans.vectorRel(),loans.headerBytes()));
        long first=h.getLong(),end=h.getLong(),cap=h.getLong();
        if(first<0||end<first||cap<end||cap>ProcessMemoryReader.MAX_USER_ADDRESS
                ||(end-first)%loans.pointerStride()!=0||(cap-first)%loans.pointerStride()!=0
                ||(cap-first)/loans.pointerStride()>loans.maximumRecords()||first==0&&cap!=0)
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("Financial loan collection range anchor failed");
        byte[] pointers=end==first?new byte[0]:frame(first,Math.toIntExact(end-first));
        byte[] records=new byte[pointers.length/loans.pointerStride()*loans.recordBytes()];
        var addresses=new HashSet<Long>();
        for(int i=0;i<pointers.length/loans.pointerStride();i++) {
            long pointer=buffer(pointers).getLong(i*loans.pointerStride());
            PlayerSnapshotValidator.validateContractPointer(pointer,loans.recordBytes());
            if(!addresses.add(pointer))throw new IOException("Duplicate financial loan collection member");
            System.arraycopy(frame(pointer,loans.recordBytes()),0,records,i*loans.recordBytes(),loans.recordBytes());
        }
        PlayerSnapshotValidator.validateClubFinancialLoans(records,clock,loans);
        return HexFormat.of().formatHex(records);
    }

    private String financialLedger(long club,int clock,ClubFinancialLedgerLayout ledger)throws IOException {
        long finance=buffer(frame(club+ledger.financeRel(),Long.BYTES)).getLong();
        if(finance==0)return null;
        PlayerSnapshotValidator.validateContractPointer(finance,ledger.fullBytes());
        byte[] prefix=frame(finance,Math.max(Long.BYTES,ledger.ownerRel()+Long.BYTES));
        var p=buffer(prefix);long type=p.getLong();
        if(ledger.ownerRel()>=0&&p.getLong(ledger.ownerRel())!=club)throw new IOException("Financial ledger owner identity anchor failed");
        if(type==base+ledger.baseVtableRva())return null;
        if(type!=base+ledger.fullVtableRva())throw new IOException("Financial ledger native type anchor failed");
        byte[] blocks=frame(finance+ledger.blocksRel(),ledger.blockWindowBytes()),raw=new byte[ledger.payloadBytes()];
        for(int i=0;i<ledger.blockCount();i++) {
            int target=i*(ledger.recordBytes()+ledger.tailBytes()),source=i*ledger.blockStride();
            System.arraycopy(blocks,source+ledger.recordRel(),raw,target,ledger.recordBytes());
            System.arraycopy(blocks,source+ledger.tailRel(),raw,target+ledger.recordBytes(),ledger.tailBytes());
        }
        PlayerSnapshotValidator.validateClubFinancialLedger(raw,clock,ledger);
        return HexFormat.of().formatHex(raw);
    }

    private String financialHistory(long club,int clock,ClubFinancialHistoryLayout l)throws IOException {
        long finance=buffer(frame(club+l.ledger().financeRel(),8)).getLong();
        var out=ByteBuffer.allocate(l.maximumPayloadBytes()).order(ByteOrder.LITTLE_ENDIAN);out.put((byte)1);
        historyRing(finance+l.summaryRel(),l.summaryBytes(),out,l);
        long optional=buffer(frame(finance+l.baseRingRel(),8)).getLong();out.put((byte)(optional==0?0:1));
        if(optional!=0)historyRing(optional,l.baseBytes(),out,l);
        byte[] raw=Arrays.copyOf(out.array(),out.position());PlayerSnapshotValidator.validateClubFinancialHistory(raw,clock,l);
        return HexFormat.of().formatHex(raw);
    }
    private void historyRing(long address,int bytes,ByteBuffer out,ClubFinancialHistoryLayout l)throws IOException {
        byte[] header=frame(address,l.headerBytes());var h=buffer(header);
        int count=Byte.toUnsignedInt(header[l.countRel()]),cursor=Byte.toUnsignedInt(header[l.cursorRel()]);
        if(count>l.capacity()||cursor>=l.capacity())throw new IOException("Financial history count/cursor range anchor failed");
        out.put((byte)count).put((byte)cursor).put(header,l.countRel()+1,7).put(header,l.cursorRel()+1,7);
        long[] ordered=new long[count];var addresses=new TreeSet<Long>();
        for(int i=0;i<count;i++){long p=h.getLong(l.pointersRel()+((cursor+i)%l.capacity())*l.pointerBytes());
            ordered[i]=p;if(p!=0){PlayerSnapshotValidator.validateContractPointer(p,bytes);addresses.add(p);}}
        var sorted=new ArrayList<>(addresses);var members=new HashMap<Long,byte[]>();
        for(int first=0;first<sorted.size();) {
            long start=sorted.get(first),end=start+bytes;int last=first+1;
            while(last<sorted.size()&&sorted.get(last)==end&&end+bytes-start<=16384){last++;end+=bytes;}
            byte[] raw=frame(start,Math.toIntExact(end-start));
            for(int i=first;i<last;i++){long p=sorted.get(i);int offset=Math.toIntExact(p-start);members.put(p,Arrays.copyOfRange(raw,offset,offset+bytes));}
            first=last;
        }
        for(long p:ordered){out.put((byte)(p==0?0:1));if(p!=0)out.put(members.get(p));}
    }

    private long competitionUid(long address,ClubFinancialCompetitionLayout l)throws IOException {
        var known=competitionUids.get(address);if(known!=null)return known;
        if(competitionBegin<0) {
            long descriptor=buffer(frame(base+l.competitionRootRva(),8)).getLong();
            long header=buffer(frame(descriptor+l.registryHeaderRel(),8)).getLong();var h=buffer(frame(header,l.headerBytes()));
            long first=h.getLong(l.beginRel()),end=h.getLong(l.endRel()),cap=h.getLong(l.capacityRel());
            if(first<0||end<first||cap<end||cap>ProcessMemoryReader.MAX_USER_ADDRESS||(end-first)%8!=0
                    ||(cap-first)%8!=0||(cap-first)/8>MAX_RECORDS||first==0&&cap!=0)
                throw new IOException("Financial COMP registry range anchor failed");
            competitionBegin=first;competitionCount=(end-first)/8;
        }
        var prefix=buffer(frame(address,l.competitionPrefixBytes()));int index=prefix.getInt(l.competitionIndexRel());
        long uid=Integer.toUnsignedLong(prefix.getInt(l.competitionUidRel()));
        if(prefix.getLong()!=base+l.competitionVtableRva()||index<0||index>=competitionCount||uid==0
                ||buffer(frame(competitionBegin+(long)index*8,8)).getLong()!=address)
            throw new IOException("Financial COMP identity/type/registry anchor failed");
        var old=competitionAddresses.putIfAbsent(uid,address);
        if(old!=null&&old!=address)throw new IOException("Duplicate financial COMP identity");
        if(nameLayout!=null) {
            String nameHex=competitionName(address,nameLayout.nameRel()),shortHex=competitionName(address,nameLayout.shortNameRel());
            try{var decoded=nameLayout.decode(nameHex,shortHex);
                competitionNames.put(uid,new NativeCompetitionNameSnapshot(uid,index,address,HexFormat.of().formatHex(prefix.array()),
                        nameHex,shortHex,decoded.name(),decoded.shortName()));
            }catch(IllegalArgumentException invalid){throw new IOException("Competition name byte/encoding anchor failed",invalid);}
        }
        competitionUids.put(address,uid);return uid;
    }
    private String competitionName(long address,int offset)throws IOException {
        long pointer=buffer(frame(address+offset,Long.BYTES)).getLong();if(pointer==0)return null;
        int length=buffer(frame(pointer+nameLayout.lengthRel(),nameLayout.lengthBytes())).getInt();
        if(length<0||length>nameLayout.maximumBytes())throw new IOException("Competition name byte length anchor failed");
        return HexFormat.of().formatHex(frame(pointer,nameLayout.dataRel()+length));
    }
    private String financialCompetitions(long club,String ledgerHex,int clock,ClubFinancialCompetitionLayout l)throws IOException {
        var ledger=l.ledger();long finance=buffer(frame(club+ledger.financeRel(),8)).getLong();
        var output=ByteBuffer.allocate(l.maximumPayloadBytes()).order(ByteOrder.LITTLE_ENDIAN);
        var scalar=buffer(HexFormat.of().parseHex(ledgerHex));
        for(int p=0;p<ledger.blockCount();p++)for(int k=0;k<l.vectorCount();k++) {
            var h=buffer(frame(finance+ledger.blocksRel()+(long)p*ledger.blockStride()+(long)k*l.headerBytes(),l.headerBytes()));
            long first=h.getLong(l.beginRel()),end=h.getLong(l.endRel()),cap=h.getLong(l.capacityRel());
            if(first<0||end<first||cap<end||cap>ProcessMemoryReader.MAX_USER_ADDRESS||(end-first)%l.entryBytes()!=0
                    ||(cap-first)%l.entryBytes()!=0||(cap-first)/l.entryBytes()>l.maximumEntries()||first==0&&cap!=0)
                throw new IOException("Financial competition vector range anchor failed");
            byte[] raw=first==end?new byte[0]:frame(first,Math.toIntExact(end-first));var members=buffer(raw);
            int count=raw.length/l.entryBytes(),sum=0;output.putInt(count);var ids=new HashSet<Long>();
            for(int i=0;i<count;i++) {
                int at=i*l.entryBytes();long uid=competitionUid(members.getLong(at+l.competitionRel()),l);
                if(!ids.add(uid))throw new IOException("Duplicate financial competition vector member");
                int amount=members.getInt(at+l.amountRel());sum+=amount;
                output.putInt((int)uid).putInt(amount).put(raw,at+l.suffixRel(),l.suffixBytes());
            }
            var category=k==0?ClubFinancialLedgerLayout.Category.TV_REVENUE:ClubFinancialLedgerLayout.Category.PRIZE_MONEY;
            int expected=scalar.getInt(p*(ledger.recordBytes()+ledger.tailBytes())+category.slots().getFirst()*ledger.slotBytes());
            if(sum!=expected)throw new IOException("Financial competition scalar reconciliation anchor failed");
        }
        byte[] raw=Arrays.copyOf(output.array(),output.position());l.decode(raw,clock);
        return HexFormat.of().formatHex(raw);
    }

    private static final class BudgetReader implements ProcessMemoryReader {
        private final ProcessMemoryReader delegate;private long bytes;
        BudgetReader(ProcessMemoryReader delegate){this.delegate=delegate;}
        private void check(long address,int length)throws IOException {
            PlayerSnapshotValidator.validateContractPointer(address,length);
            if((bytes+=length)>64L*1024*1024)throw new IOException("CLUB identity capture byte budget exceeded");
        }
        public int pid(){return delegate.pid();}
        public Platform platform(){return delegate.platform();}
        public List<MemoryRegion> maps()throws IOException{return delegate.maps();}
        public byte[] readBytes(long address,int length)throws IOException {
            check(address,length);byte[] data=delegate.readBytes(address,length);
            if(data.length!=length)throw new IOException("Short CLUB identity read");return data;
        }
        public void readInto(long address,byte[] target,int offset,int length)throws IOException {
            ProcessMemoryReader.validateRead(address,target,offset,length);check(address,length);delegate.readInto(address,target,offset,length);
        }
        public void close(){} // Caller owns the underlying process reader.
    }
}
