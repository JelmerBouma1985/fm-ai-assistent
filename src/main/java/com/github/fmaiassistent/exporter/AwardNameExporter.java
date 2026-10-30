package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.domain.NativeAwardNameSnapshot;
import com.github.fmaiassistent.memory.AwardNameLayout;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Reads only referenced AWARDs; scoped to one capture, with final raw/root/slot rereads before publication. */
public final class AwardNameExporter {
    private final ProcessMemoryReader reader;
    private final long base;
    private final AwardNameLayout layout;
    private final List<Guard> guards=new ArrayList<>();
    private final Map<Long,NativeAwardNameSnapshot> names=new LinkedHashMap<>();
    private final Map<Long,Long> addresses=new HashMap<>();
    private long begin,count,readBytes;
    private record Guard(long address,byte[] bytes) { }

    public AwardNameExporter(ProcessMemoryReader reader,long base,AwardNameLayout layout)throws IOException {
        PlayerSnapshotValidator.validateAwardNameLayout(layout);this.reader=reader;this.base=base;this.layout=layout;
    }
    static void capture(ProcessMemoryReader reader,Long suppliedBase,List<Map<String,Object>> rows)throws IOException {
        if(reader.platform()!=ProcessMemoryReader.Platform.WINDOWS)return;
        var profile=ContractExporter.capturedProfile(rows);if(profile.isEmpty())return;
        long base=suppliedBase==null?com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader):suppliedBase;
        new AwardNameExporter(reader,base,NativeModuleProfile.fm27(profile.orElseThrow())
                ? AwardNameLayout.fm27() : AwardNameLayout.current()).captureRows(rows);
    }
    public void captureRows(List<Map<String,Object>> rows)throws IOException {
        guards.clear();names.clear();addresses.clear();begin=0;count=0;readBytes=0;
        var staged=new ArrayList<List<NativeAwardNameSnapshot>>(rows.size());
        try {
            for(var owner:rows){
                if(Thread.currentThread().isInterrupted())throw new IOException("Award name capture interrupted");
                var selected=new LinkedHashMap<Long,NativeAwardNameSnapshot>();
                if(moduleProfile().equals(owner.get("_contract_profile"))){
                    var contracts=new ArrayList<ContractSnapshot>();
                    Object active=owner.get("_contracts");if(active instanceof List<?> list)for(var item:list){
                        if(!(item instanceof ContractSnapshot c))throw new IOException("Invalid award contract source");contracts.add(c);}
                    Object future=owner.get("_future_contracts");if(future instanceof List<?> list)for(var item:list){
                        if(!(item instanceof FutureTransferSnapshot f))throw new IOException("Invalid future award source");contracts.add(f.employment().contract());}
                    for(var c:contracts){
                        if(!(owner.get("unique_id") instanceof Number uid)||uid.longValue()!=c.ownerUid())throw new IOException("Award contract owner UID mismatch");
                        for(var term:c.terms())if("AWARD".equals(term.referenceKind())&&term.referenceUid()!=null){
                            byte[] raw;try{raw=HexFormat.of().parseHex(term.rawHex());}catch(IllegalArgumentException bad){throw new IOException("Invalid award term bytes",bad);}
                            if(raw.length!=layout.rowBytes())throw new IOException("Award term extent anchor failed");
                            selected.put(term.referenceUid(),readName(term.referenceUid(),buffer(raw).getLong()));
                        }
                    }
                }
                staged.add(List.copyOf(selected.values()));
            }
            verify();
            for(int i=0;i<rows.size();i++)if(moduleProfile().equals(rows.get(i).get("_contract_profile")))
                rows.get(i).put("_award_names",staged.get(i));
        }finally{guards.clear();names.clear();addresses.clear();}
    }
    private String moduleProfile(){return layout.equals(AwardNameLayout.fm27())
            ? NativeModuleProfile.FM27_SHA256 : NativeModuleProfile.LEGACY_SHA256;}

    private NativeAwardNameSnapshot readName(long uid,long address)throws IOException {
        if(uid<=0||uid>0xffffffffL)throw new IOException("Invalid AWARD UID");
        if(names.containsKey(uid)){if(addresses.get(uid)!=address)throw new IOException("AWARD UID has conflicting pointers");return names.get(uid);}
        if(names.size()>=65536)throw new IOException("Referenced award count exceeds bound");
        if(begin==0){
            long descriptor=buffer(keep(base+layout.registryRva(),8)).getLong();
            long header=buffer(keep(descriptor+layout.registryHeaderRel(),8)).getLong();
            var bounds=buffer(keep(header,24));begin=bounds.getLong();long end=bounds.getLong(),capacity=bounds.getLong();
            if(begin<0x10000||end<begin||capacity<end||capacity>ProcessMemoryReader.MAX_USER_ADDRESS
                    ||(end-begin)%8!=0||(capacity-begin)%8!=0||(end-begin)/8>100000)throw new IOException("AWARD registry bounds anchor failed");
            count=(end-begin)/8;
        }
        byte[] prefix=keep(address,layout.prefixBytes());var p=buffer(prefix);int index=p.getInt(layout.indexRel());
        if(p.getLong()!=base+layout.vtableRva()||Integer.toUnsignedLong(p.getInt(layout.uidRel()))!=uid||index<0||index>=count
                ||buffer(keep(begin+(long)index*8,8)).getLong()!=address)throw new IOException("AWARD type/index/UID/registry anchor failed");
        String full=readString(p.getLong(layout.nameRel())),shortName=readString(p.getLong(layout.shortNameRel()));
        final com.github.fmaiassistent.memory.CompetitionNameLayout.Names decoded;
        try{decoded=layout.decode(full,shortName);}catch(IllegalArgumentException bad){throw new IOException("AWARD string encoding anchor failed",bad);}
        var result=new NativeAwardNameSnapshot(uid,index,HexFormat.of().formatHex(prefix,0,layout.identityBytes()),full,shortName,decoded.name(),decoded.shortName());
        names.put(uid,result);addresses.put(uid,address);return result;
    }
    private String readString(long pointer)throws IOException {
        if(pointer==0)return null;byte[] header=keep(pointer,layout.dataRel());int length=buffer(header).getInt(layout.lengthRel());
        if(length<0||length>layout.maximumBytes())throw new IOException("AWARD name length anchor failed");
        return HexFormat.of().formatHex(keep(pointer,layout.dataRel()+length));
    }
    private void verify()throws IOException {for(var g:guards)if(!Arrays.equals(g.bytes(),read(g.address(),g.bytes().length)))throw new IOException("Award name data changed during capture");}
    private byte[] keep(long p,int n)throws IOException {byte[] b=read(p,n);guards.add(new Guard(p,b));return b;}
    private byte[] read(long p,int n)throws IOException {
        PlayerSnapshotValidator.validateContractPointer(p,n);if(n>2048||(readBytes+=n)>16L*1024*1024)throw new IOException("Award name byte budget exceeded");
        byte[] b=reader.readBytes(p,n);if(b.length!=n)throw new IOException("Short AWARD read");return b;
    }
    private static ByteBuffer buffer(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
}
