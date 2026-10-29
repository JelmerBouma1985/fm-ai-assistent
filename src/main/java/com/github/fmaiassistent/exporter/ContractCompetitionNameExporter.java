package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot;
import com.github.fmaiassistent.memory.*;
import java.io.IOException;
import java.nio.*;
import java.util.*;

/** Referenced COMP names, deduplicated within one capture and published after final guard rereads. */
public final class ContractCompetitionNameExporter {
    private final ProcessMemoryReader reader;
    private final long base;
    private final CompetitionNameLayout namesLayout;
    private final ClubFinancialCompetitionLayout identity=ClubFinancialCompetitionLayout.current();
    private final CompetitionBonusLayout bonusLayout=CompetitionBonusLayout.current();
    private final Map<Long,NativeCompetitionNameSnapshot> names=new LinkedHashMap<>();
    private final Map<Long,byte[]> guards=new LinkedHashMap<>();
    private long begin,count,readBytes;

    public ContractCompetitionNameExporter(ProcessMemoryReader reader,long base,CompetitionNameLayout layout)throws IOException {
        PlayerSnapshotValidator.validateCompetitionNameLayout(layout);
        PlayerSnapshotValidator.validateClubFinancialCompetitionLayout(identity);
        PlayerSnapshotValidator.validateCompetitionBonusLayout(bonusLayout);
        this.reader=reader;this.base=base;this.namesLayout=layout;
    }

    static void capture(ProcessMemoryReader reader,Long suppliedBase,List<Map<String,Object>> rows)throws IOException {
        if(reader.platform()!=ProcessMemoryReader.Platform.WINDOWS||rows.stream().noneMatch(r->ContractExporter.MODULE_SHA256.equals(r.get("_contract_profile"))))return;
        long base=suppliedBase==null?com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader):suppliedBase;
        new ContractCompetitionNameExporter(reader,base,CompetitionNameLayout.current()).captureRows(rows);
    }

    public void captureRows(List<Map<String,Object>> rows)throws IOException {
        names.clear();guards.clear();begin=0;count=0;readBytes=0;
        var staged=new ArrayList<List<NativeCompetitionNameSnapshot>>(rows.size());
        try {
            for(var owner:rows) {
                if(Thread.currentThread().isInterrupted())throw new IOException("Contract competition name capture interrupted");
                var selected=new LinkedHashMap<Long,NativeCompetitionNameSnapshot>();
                if(ContractExporter.MODULE_SHA256.equals(owner.get("_contract_profile"))) {
                    var contracts=new ArrayList<ContractSnapshot>();
                    Object active=owner.get("_contracts");if(active instanceof List<?> list)for(var item:list) {
                        if(!(item instanceof ContractSnapshot c))throw new IOException("Invalid competition contract source");contracts.add(c);
                    }
                    Object future=owner.get("_future_contracts");if(future instanceof List<?> list)for(var item:list) {
                        if(!(item instanceof FutureTransferSnapshot f))throw new IOException("Invalid future competition source");contracts.add(f.employment().contract());
                    }
                    for(var c:contracts) {
                        if(!(owner.get("unique_id") instanceof Number uid)||uid.longValue()!=c.ownerUid())throw new IOException("Competition contract owner mismatch");
                        for(var term:c.terms())if(term.competitionReferences()!=null) {
                            PlayerSnapshotValidator.validateCompetitionReferences(term);
                            var row=bonusLayout.decode(term.rawHex());var refs=term.competitionReferences();
                            if(refs.subjectUid()!=null)selected.put(refs.subjectUid(),readName(refs.subjectUid(),row.subjectCompetitionIndex()));
                            if(refs.conditionUid()!=null)selected.put(refs.conditionUid(),readName(refs.conditionUid(),row.conditionCompetitionIndex()));
                        }
                    }
                }
                staged.add(List.copyOf(selected.values()));
            }
            for(var guard:guards.entrySet())if(!Arrays.equals(guard.getValue(),read(guard.getKey(),guard.getValue().length)))
                throw new IOException("Contract competition name data changed during capture");
            for(int i=0;i<rows.size();i++)if(ContractExporter.MODULE_SHA256.equals(rows.get(i).get("_contract_profile")))
                rows.get(i).put("_contract_competition_names",staged.get(i));
        }finally{names.clear();guards.clear();}
    }

    private NativeCompetitionNameSnapshot readName(long uid,int index)throws IOException {
        if(names.containsKey(uid)) {
            var known=names.get(uid);if(known.recordIndex()!=index)throw new IOException("COMP UID/index conflict");return known;
        }
        if(names.size()>=100000)throw new IOException("Contract competition name count exceeds bound");
        if(begin==0) {
            long descriptor=buffer(keep(base+identity.competitionRootRva(),8)).getLong();
            long header=buffer(keep(descriptor+identity.registryHeaderRel(),8)).getLong();
            var h=buffer(keep(header,identity.headerBytes()));begin=h.getLong(identity.beginRel());
            long end=h.getLong(identity.endRel()),capacity=h.getLong(identity.capacityRel());
            if(begin<0x10000||end<begin||capacity<end||capacity>ProcessMemoryReader.MAX_USER_ADDRESS
                    ||(end-begin)%8!=0||(capacity-begin)%8!=0||(end-begin)/8>100000)
                throw new IOException("Contract COMP registry bounds anchor failed");
            count=(end-begin)/8;
        }
        if(index<0||index>=count)throw new IOException("Contract COMP index anchor failed");
        long address=buffer(keep(begin+(long)index*8,8)).getLong();
        byte[] prefix=keep(address,namesLayout.shortNameRel()+Long.BYTES);var p=buffer(prefix);
        if(p.getLong()!=base+identity.competitionVtableRva()||p.getInt(identity.competitionIndexRel())!=index
                ||Integer.toUnsignedLong(p.getInt(identity.competitionUidRel()))!=uid)
            throw new IOException("Contract COMP type/index/UID anchor failed");
        String full=string(p.getLong(namesLayout.nameRel())),shortName=string(p.getLong(namesLayout.shortNameRel()));
        final CompetitionNameLayout.Names decoded;
        try{decoded=namesLayout.decode(full,shortName);}catch(IllegalArgumentException invalid){throw new IOException("Contract COMP string anchor failed",invalid);}
        var result=new NativeCompetitionNameSnapshot(uid,index,address,HexFormat.of().formatHex(prefix,0,identity.competitionPrefixBytes()),
                full,shortName,decoded.name(),decoded.shortName());
        names.put(uid,result);return result;
    }

    private String string(long pointer)throws IOException {
        if(pointer==0)return null;
        int length=buffer(keep(pointer,namesLayout.dataRel())).getInt(namesLayout.lengthRel());
        if(length<0||length>namesLayout.maximumBytes())throw new IOException("Contract COMP name length anchor failed");
        return HexFormat.of().formatHex(keep(pointer,namesLayout.dataRel()+length));
    }
    private byte[] keep(long p,int n)throws IOException {
        byte[] raw=read(p,n);var old=guards.get(p);
        if(old!=null) {
            int shared=Math.min(old.length,raw.length);
            if(!Arrays.equals(old,0,shared,raw,0,shared))throw new IOException("Contract COMP data changed within capture");
            if(old.length>=raw.length)return raw;
        }
        guards.put(p,raw);return raw;
    }
    private byte[] read(long p,int n)throws IOException {
        PlayerSnapshotValidator.validateContractPointer(p,n);
        if(n>2048||(readBytes+=n)>16L*1024*1024)throw new IOException("Contract COMP name byte budget exceeded");
        var raw=reader.readBytes(p,n);if(raw.length!=n)throw new IOException("Short contract COMP name read");return raw;
    }
    private static ByteBuffer buffer(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
}
