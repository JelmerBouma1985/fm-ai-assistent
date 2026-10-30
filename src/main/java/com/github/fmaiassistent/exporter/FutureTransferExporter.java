package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.FutureTransferLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** One capture-scoped manager table, bounded reads, no native calls or stale-index cleanup writes. */
final class FutureTransferExporter {
    record Result(List<FutureTransferSnapshot> contracts, boolean complete, boolean agreed, String clubName) { }
    private final ProcessMemoryReader reader;
    private final long base;
    private final FutureTransferLayout layout;
    private final ContractExporter contracts;
    private long manager;
    private byte[] rootBytes,managerType,header,table;
    private long tableAddress;
    private long bytesRead;

    FutureTransferExporter(ProcessMemoryReader reader,long base,FutureTransferLayout layout) {
        this.reader=reader;this.base=base;this.layout=layout;
        this.contracts=new ContractExporter(reader,base,layout.equals(FutureTransferLayout.fm27())
                ? ContractRecordLayout.fm27() : ContractRecordLayout.current());
    }

    Result read(long person,long uid) throws IOException {
        byte[] prefix=bytes(person,0xB8);
        PlayerSnapshotValidator.validateFutureTransferPerson(prefix,uid,layout);
        long indices=buffer(prefix).getLong(layout.personIndices());
        List<FutureTransferSnapshot> found=new ArrayList<>();boolean complete=true,agreed=false;
        String clubName="";
        if(indices!=0) {
            byte[] bounds=bytes(indices,24);
            long start=buffer(bounds).getLong();int length=vectorLength(bounds,4,2048);
            byte[] data=length==0?new byte[0]:bytes(start,length);
            if(length>0)manager();
            var seen=new HashSet<Integer>();
            for(int at=0;at<length;at+=4) {
                int index=buffer(data).getInt(at);
                if(index<0||!seen.add(index)||index>=table.length/8)continue;
                long offer=buffer(table).getLong(index*8);if(offer==0)continue;
                long type=buffer(bytes(offer,8)).getLong();
                if(type!=base+layout.fullOfferVtable()&&type!=base+layout.loanOfferVtable()){complete=false;continue;}
                byte[] core=bytes(offer,layout.offerPrefixSize());
                if(buffer(core).getLong()!=type)throw new IOException("Future offer type changed");
                PlayerSnapshotValidator.validateFutureTransferOffer(core,index,layout);
                if(core[layout.status()]!=19){unchanged(offer,core);continue;}
                if(type!=base+layout.fullOfferVtable()){complete=false;unchanged(offer,core);continue;}
                if(!member(offer,core,person)){complete=false;unchanged(offer,core);continue;}
                agreed=true;
                var employment=contracts.readFutureEmployment(person,uid,index);
                if(employment.isEmpty()){complete=false;unchanged(offer,core);continue;}
                var snapshot=employment.orElseThrow();
                var cl=ContractRecordLayout.current();
                byte[] contractCore=java.util.HexFormat.of().parseHex(snapshot.contract().coreHex());
                long team=buffer(contractCore).getLong(cl.links().team());
                if(team!=buffer(core).getLong(layout.buyerTeam()))throw new IOException("Future buyer/contract TEAM anchor failed");
                LocalDate date=null;
                if(Byte.toUnsignedInt(core[layout.dateKind()])!=1) {
                    int ordinal=Short.toUnsignedInt(buffer(core).getShort(layout.date()))&511;
                    int year=Short.toUnsignedInt(buffer(core).getShort(layout.date()+2));
                    if(year==1900&&ordinal==1)complete=false; // Native manager virtual50 computes this date.
                    else {
                        if(year<1900||year>3000||ordinal<1||ordinal>java.time.Year.of(year).length())
                            throw new IOException("Future transfer date anchor failed");
                        date=LocalDate.ofYearDay(year,ordinal);
                    }
                } else complete=false;
                if(found.isEmpty()&&"CLUB".equals(snapshot.contract().teamOwnerKind())) {
                    long club=reader.readU64(team+cl.teamOwner().reference());
                    if(reader.readU32(club+cl.links().uid())!=snapshot.contract().teamOwnerUid())
                        throw new IOException("Future club identity changed");
                    clubName=FmMemoryStrings.clubDisplayName(reader,club).orElse("");
                }
                found.add(new FutureTransferSnapshot(date,snapshot));
                unchanged(offer,core);
                if(reader.readU64(tableAddress+index*8L)!=offer)throw new IOException("Future offer slot changed");
            }
            if(length>0)unchanged(start,data);unchanged(indices,bounds);
        }
        unchanged(person,prefix);
        return new Result(List.copyOf(found),complete,agreed,clubName);
    }

    private boolean member(long offer,byte[] core,long person) throws IOException {
        if(buffer(core).getLong(layout.person())==person)return true;
        byte[] header=bytes(offer+layout.exchangeVector(),24);
        int length=vectorLength(header,layout.exchangeStride(),256);
        long address=buffer(header).getLong();byte[] data=length==0?new byte[0]:bytes(address,length);
        boolean found=false;
        for(int at=0;at<length;at+=layout.exchangeStride())if(buffer(data).getLong(at)==person)found=true;
        if(length>0)unchanged(address,data);unchanged(offer+layout.exchangeVector(),header);
        // Unresolved lazy PERSON handles are deliberately not invoked or rewritten. Caller reports incomplete.
        return found;
    }

    private void manager() throws IOException {
        if(table!=null)return;
        rootBytes=bytes(base+layout.managerRoot(),8);manager=buffer(rootBytes).getLong();
        managerType=bytes(manager,8);
        if(buffer(managerType).getLong()!=base+layout.managerVtable())throw new IOException("Future manager type anchor failed");
        header=bytes(manager+layout.tableOffset(),24);int length=vectorLength(header,8,1_000_000);
        tableAddress=buffer(header).getLong();table=new byte[length];
        for(int at=0;at<length;at+=65536) {
            int n=Math.min(65536,length-at);System.arraycopy(bytes(tableAddress+at,n),0,table,at,n);
        }
    }

    void verify() throws IOException {
        if(table==null)return;
        unchanged(base+layout.managerRoot(),rootBytes);unchanged(manager,managerType);
        unchanged(manager+layout.tableOffset(),header);
        for(int at=0;at<table.length;at+=65536) {
            int n=Math.min(65536,table.length-at);
            if(!Arrays.equals(Arrays.copyOfRange(table,at,at+n),bytes(tableAddress+at,n)))
                throw new IOException("Future offer table changed during capture");
        }
    }

    private int vectorLength(byte[] header,int stride,int maxCount) throws IOException {
        long a=buffer(header).getLong(),z=buffer(header).getLong(8),cap=buffer(header).getLong(16);
        if(a<0||z<a||cap<z||cap>ProcessMemoryReader.MAX_USER_ADDRESS||(z-a)%stride!=0||(cap-a)%stride!=0
                ||(z-a)/stride>maxCount||a==0&&cap!=0)throw new IOException("Future vector count/range anchor failed");
        if(a!=0)PlayerSnapshotValidator.validateContractPointer(a,0);
        return Math.toIntExact(z-a);
    }
    private byte[] bytes(long address,int length)throws IOException {
        PlayerSnapshotValidator.validateContractPointer(address,length);
        if((bytesRead+=length)>64L*1024*1024)throw new IOException("Future transfer capture byte budget exceeded");
        byte[] data=reader.readBytes(address,length);if(data.length!=length)throw new IOException("Short future transfer read");return data;
    }
    private void unchanged(long address,byte[] old)throws IOException {
        if(!Arrays.equals(old,bytes(address,old.length)))throw new IOException("Future transfer data changed during capture");
    }
    private static ByteBuffer buffer(byte[] raw){return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);}
}
