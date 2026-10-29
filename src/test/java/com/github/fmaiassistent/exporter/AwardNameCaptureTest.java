package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.NativeAwardNameSnapshot;
import com.github.fmaiassistent.memory.AwardNameLayout;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

public class AwardNameCaptureTest {
    static final AwardNameLayout L=AwardNameLayout.current();
    static final long BASE=0x7FFE41950000L,D=0x10000000,H=0x10001000,T=0x10010000;
    static tools.jackson.databind.JsonNode json(String file)throws Exception {
        byte[] b;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence",file))){b=in.readNBytes(2*1024*1024+1);}
        assertThat(b.length).isLessThanOrEqualTo(2*1024*1024);return tools.jackson.databind.json.JsonMapper.builder().build().readTree(b);
    }
    static Fixture fixture()throws Exception {
        var f=new Fixture();var live=json("referenced_award_names_checkpoint54_live_20261027.json").get("returnValues").get(0);
        int count=live.get("registryCount").asInt();var table=ByteBuffer.allocate(count*8).order(ByteOrder.LITTLE_ENDIAN);
        for(var a:live.get("rows")){
            long address=hex(a.get("address").asString());int index=a.get("index").asInt();
            f.put(address,HexFormat.of().parseHex(a.get("prefix").asString()));table.putLong(index*8,address);
            for(var name:a.get("names"))if(!name.get("pointer").asString().equals("0"))
                f.put(hex(name.get("pointer").asString()),HexFormat.of().parseHex(name.get("raw").asString()));
        }
        f.put(BASE+L.registryRva(),longs(D));f.put(D+L.registryHeaderRel(),longs(H));f.put(H,longs(T,T+table.capacity(),T+table.capacity()));f.put(T,table.array());return f;
    }
    public static List<Map<String,Object>> ownerRows()throws Exception {
        var grouped=new LinkedHashMap<Long,List<ContractSnapshot.Term>>();
        for(var r:json("stored_award_reference_census_with_frames_checkpoint54_20261027.json").get("rawFrames")){
            grouped.computeIfAbsent(r.get("personUid").asLong(),u->new ArrayList<>()).add(new ContractSnapshot.Term(
                    "FULL_98",r.get("ordinal").asInt(),r.get("rawHex").asString(),null,null,null,"AWARD",r.get("awardUid").asLong()));
        }
        var rows=new ArrayList<Map<String,Object>>();for(var e:grouped.entrySet()){
            var c=new ContractSnapshot("FULL",-1,e.getKey(),null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,
                    "",null,e.getValue(),null,null);
            var row=new HashMap<String,Object>();row.put("unique_id",e.getKey());row.put("_contract_profile",ContractExporter.MODULE_SHA256);row.put("_contracts",List.of(c));rows.add(row);
        }return rows;
    }
    public static List<Map<String,Object>> capturedRows()throws Exception {
        var rows=ownerRows();new AwardNameExporter(fixture(),BASE,L).captureRows(rows);return rows;
    }
    @SuppressWarnings("unchecked") public static List<NativeAwardNameSnapshot> names(Map<String,Object> row){return (List<NativeAwardNameSnapshot>)row.get("_award_names");}

    @Test void all43IndependentNamesJoinAll1154RowsAndEachPrefixIsReadOnlyTwice()throws Exception {
        var f=fixture();var rows=ownerRows();new AwardNameExporter(f,BASE,L).captureRows(rows);
        var expected=json("referenced_award_names_checkpoint54_verified_20261027.json").get("rows");var actual=new HashMap<Long,NativeAwardNameSnapshot>();
        for(var row:rows)for(var n:names(row))actual.put(n.uniqueId(),n);assertThat(actual).hasSize(43);
        for(var r:expected){var n=actual.get(r.get("uid").asLong());assertThat(n.recordIndex()).isEqualTo(r.get("index").asInt());
            assertThat(n.name()).isEqualTo(r.get("names").get(0).get("value").asString());assertThat(n.shortName()).isEqualTo(r.get("names").get(1).get("value").asString());
            assertThat(n.nameHex()).isEqualToIgnoringCase(r.get("names").get(0).get("raw").asString());}
        for(var a:json("referenced_award_names_checkpoint54_live_20261027.json").get("returnValues").get(0).get("rows"))assertThat(f.reads.get(hex(a.get("address").asString()))).isEqualTo(2);
        assertThat(rows.stream().mapToLong(r->((List<ContractSnapshot>)r.get("_contracts")).getFirst().terms().size()).sum()).isEqualTo(1154);
        assertThat(actual.get(102406L).recordIndex()).isZero();
    }
    @Test void everyShiftedComponentRejectsTheSameCaptureBeforeAnyMemoryRead()throws Exception {
        var f=fixture();var parts=AwardNameLayout.class.getRecordComponents();var types=Arrays.stream(parts).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        var ctor=AwardNameLayout.class.getDeclaredConstructor(types);
        for(int i=0;i<parts.length;i++){Object[] args=new Object[parts.length];for(int j=0;j<args.length;j++)args[j]=parts[j].getAccessor().invoke(L);
            args[i]=types[i]==long.class?((Long)args[i])+1L:((Integer)args[i])+1;
            if(types[i]==int.class)args[i]=((Number)args[i]).intValue();
            var shifted=ctor.newInstance(args);assertThatThrownBy(()->new AwardNameExporter(f,BASE,shifted).captureRows(ownerRows())).as(parts[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");}
        assertThat(f.reads).isEmpty();
    }
    @Test void changedPrefixOrRegistrySlotAbortsWithoutPublishingAnyMetadata()throws Exception {
        var source=json("referenced_award_names_checkpoint54_live_20261027.json").get("returnValues").get(0).get("rows").get(0);
        var f=fixture();long award=hex(source.get("address").asString());f.mutateAfterRead=award;var rows=ownerRows();var changing=f;
        assertThatThrownBy(()->new AwardNameExporter(changing,BASE,L).captureRows(rows)).isInstanceOf(IOException.class).hasMessageContaining("changed");
        assertThat(rows).noneMatch(r->r.containsKey("_award_names"));
        f=fixture();var table=f.blocks.get(T).clone();ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).putLong(source.get("index").asInt()*8,award+8);f.put(T,table);var corrupted=f;
        assertThatThrownBy(()->new AwardNameExporter(corrupted,BASE,L).captureRows(ownerRows())).isInstanceOf(IOException.class).hasMessageContaining("registry anchor");
    }
    @Test void nullFullNameKeepsShortNameAndOversizedStringsFailBeforePayloadRead()throws Exception {
        var source=json("referenced_award_names_checkpoint54_live_20261027.json").get("returnValues").get(0).get("rows").get(0);
        long address=hex(source.get("address").asString());var f=fixture();var prefix=f.blocks.get(address).clone();ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).putLong(L.nameRel(),0);f.put(address,prefix);
        var rows=ownerRows();new AwardNameExporter(f,BASE,L).captureRows(rows);var n=rows.stream().flatMap(r->names(r).stream()).filter(x->x.uniqueId()==source.get("uid").asLong()).findFirst().orElseThrow();
        assertThat(n.nameHex()).isNull();assertThat(n.name()).isNull();assertThat(n.shortName()).isNotBlank();
        var bad=fixture();long p=hex(source.get("names").get(0).get("pointer").asString());bad.put(p,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Integer.MAX_VALUE).array());
        assertThatThrownBy(()->new AwardNameExporter(bad,BASE,L).captureRows(ownerRows())).isInstanceOf(IOException.class).hasMessageContaining("length anchor");
    }
    static long hex(String s){return Long.parseUnsignedLong(s,16);}
    static byte[] longs(long...v){var b=ByteBuffer.allocate(v.length*8).order(ByteOrder.LITTLE_ENDIAN);for(long n:v)b.putLong(n);return b.array();}
    static class Fixture extends ClubUefaCoefficientCaptureTest.Blocks {
        final Map<Long,Integer> reads=new HashMap<>();long mutateAfterRead=-1;
        @Override public byte[] readBytes(long a,int n)throws IOException {
            var b=super.readBytes(a,n);int count=reads.merge(a,1,Integer::sum);
            if(a==mutateAfterRead&&count==1){var copy=blocks.get(a).clone();copy[16]^=1;put(a,copy);}return b;
        }
    }
}
