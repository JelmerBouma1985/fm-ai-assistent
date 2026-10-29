package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NonPlayingProfileExporterTest {
    private static final long BASE=0x7FF988310000L;
    private static final NonPlayingProfileLayout L=NonPlayingProfileLayout.current();

    @Test void capturedCombinedMemberPreservesAll52BytesIncludingUnknown45And46()throws Exception {
        var f=fixture();var row=f.row();
        NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(row),L);
        assertThat(row.get("non_playing_profile_hex")).isEqualTo(HexFormat.of().formatHex(f.raw(),8,60));
        assertThat(row.get("non_playing_profile_sha256")).isEqualTo(ContractExporter.MODULE_SHA256);
        assertThat(f.memory().memberReads).isEqualTo(2);
        assertThat(row).doesNotContainKey("job_id"); // Profile availability does not create employment.
    }

    @Test void unsupportedPersonViewsNeverUseTheProfileShortcut()throws Exception {
        for(long type:new long[]{0x12345}) {
            var f=fixture();f.memory().put(f.person(),ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(BASE+type).array());
            NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),L);
            assertThat(f.memory().memberReads).isZero();assertThat(f.row()).doesNotContainKey("non_playing_profile_hex");
        }
    }

    @Test void ownerUidAndMemberTypeAreIndependentAnchors()throws Exception {
        var wrongUid=fixture();wrongUid.row().put("unique_id",123L);
        assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(wrongUid.memory(),BASE,List.of(wrongUid.row()),L)).isInstanceOf(IOException.class);
        var wrongType=fixture();wrongType.memory().put(wrongType.person()+L.personToMemberRel(),new byte[8]);
        assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(wrongType.memory(),BASE,List.of(wrongType.row()),L)).isInstanceOf(IOException.class);
        assertThat(wrongType.row()).doesNotContainKey("non_playing_profile_hex");
    }

    @Test void shiftedLayoutWithPlausibleCapturedValuesFailsBeforeReadingOrAllocating()throws Exception {
        var f=fixture();var shifted=new NonPlayingProfileLayout(L.personToMemberRel(),L.constructionRel(),L.memberBytes(),
                L.profileOffset()+1,L.profileBytes(),L.memberVtableRva(),L.standalonePersonVtableRva(),L.combinedPersonVtableRva(),L.humanPersonToMemberRel(),L.humanPersonVtableRva(),L.fields());
        assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),shifted)).isInstanceOf(IOException.class);
        assertThat(f.memory().reads).isZero();assertThat(f.row()).doesNotContainKey("non_playing_profile_hex");
    }

    @Test void changedMemberAbortsBeforePublishingAnyProfile()throws Exception {
        var f=fixture();f.memory().changeOnReread=true;
        assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),L)).isInstanceOf(IOException.class).hasMessageContaining("changed");
        assertThat(f.row()).doesNotContainKeys("non_playing_profile_hex","non_playing_profile_sha256");
    }

    @Test void fieldModelNamesAndScalesMatchNativeBoundIndicesWithoutInventingUnknownBytes()throws Exception {
        var f=fixture();String raw=HexFormat.of().formatHex(f.raw(),8,60);
        var values=NonPlayingProfileLayout.values(raw,NonPlayingProfileLayout.MODULE_SHA256);
        assertThat(values).hasSize(50);
        assertThat(values).anySatisfy(v->{assertThat(v.name()).isEqualTo("Eccentricity");assertThat(v.nativeValue()).isEqualTo(f.raw()[8+48]);assertThat(v.scale()).isEqualTo(100);});
        assertThat(values).anySatisfy(v->{assertThat(v.name()).isEqualTo("Coaching Technique");assertThat(v.nativeValue()).isEqualTo(f.raw()[8+2]);assertThat(v.scale()).isEqualTo(20);});
        assertThat(L.fields()).noneMatch(v->v.index()==45||v.index()==46||v.index()==52);
        assertThat(NonPlayingProfileLayout.values(raw,"other-build")).isEmpty();
        assertThat(NonPlayingProfileLayout.values("x".repeat(104),NonPlayingProfileLayout.MODULE_SHA256)).isEmpty();
        assertThat(NonPlayingProfileLayout.values(null,null)).isEmpty();
    }

    @Test void all50ProductionOffsetsNamesAndScalesAgreeWithTheIndependentNativeBridge()throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/nonplaying_named_native_field_model_checked_20261025.json"))){bytes=in.readNBytes(131073);}
        assertThat(bytes.length).isLessThanOrEqualTo(131072);var nativeRows=JsonMapper.builder().build().readTree(bytes).get("rows");
        for(var field:L.fields()) {
            var nativeField=nativeRows.get(field.index());
            assertThat(nativeField.get("profileByteOffset").asInt()).isEqualTo(field.index());
            assertThat(nativeField.get("nativeName").get(0).asString()).isEqualTo(field.name());
            assertThat(nativeField.get("storageMode").asString().equals("SCALED_BY_FIVE")).isEqualTo(field.scaledByFive());
        }
        assertThat(L.fields()).hasSize(50);
    }

    @Test void everyNewNamedFieldRejectsItsOwnShiftAgainstTheSameCapturedFixture()throws Exception {
        for(int at=0;at<L.fields().size();at++) {
            var f=fixture();var fields=new ArrayList<>(L.fields());var field=fields.get(at);
            fields.set(at,new NonPlayingProfileLayout.Field(field.index()+1,field.name(),field.scaledByFive()));
            var shifted=new NonPlayingProfileLayout(L.personToMemberRel(),L.constructionRel(),L.memberBytes(),L.profileOffset(),
                    L.profileBytes(),L.memberVtableRva(),L.standalonePersonVtableRva(),L.combinedPersonVtableRva(),L.humanPersonToMemberRel(),L.humanPersonVtableRva(),fields);
            assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),shifted)).isInstanceOf(IOException.class);
            assertThat(f.memory().reads).isZero();
        }
    }

    @Test void bothCapturedHumansResolveTheSameMemberThroughTheirOwn450BaseView()throws Exception {
        for(int i=0;i<2;i++) {
            var f=humanFixture(i);NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),L);
            assertThat(f.row().get("non_playing_profile_hex")).isEqualTo(HexFormat.of().formatHex(f.raw(),8,60));
            assertThat(f.memory().member).isEqualTo(f.person()-0x448);assertThat(f.memory().memberReads).isEqualTo(2);
            assertThat(NonPlayingProfileLayout.values((String)f.row().get("non_playing_profile_hex"),(String)f.row().get("non_playing_profile_sha256"))).hasSize(50);
        }
    }

    @Test void humanMemberAndTypeDriftAreRejectedBeforeUsingTheCapturedBytes()throws Exception {
        for(boolean shiftMember:new boolean[]{true,false}) {
            var f=humanFixture(0);var shifted=new NonPlayingProfileLayout(L.personToMemberRel(),L.constructionRel(),L.memberBytes(),L.profileOffset(),L.profileBytes(),L.memberVtableRva(),
                    L.standalonePersonVtableRva(),L.combinedPersonVtableRva(),L.humanPersonToMemberRel()+(shiftMember?1:0),L.humanPersonVtableRva()+(shiftMember?0:8),L.fields());
            assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),shifted)).isInstanceOf(IOException.class);assertThat(f.memory().reads).isZero();
        }
    }

    private static Fixture humanFixture(int index)throws Exception {
        byte[] data;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/human_nonplaying_profiles_live_20261025.json"))){data=in.readNBytes(65537);}assertThat(data.length).isLessThanOrEqualTo(65536);
        var sample=JsonMapper.builder().build().readTree(data).get("returnValues").get(0).get("samples").get(index);
        long person=Long.parseUnsignedLong(sample.get("person").asString(),16),member=Long.parseUnsignedLong(sample.get("member").asString(),16);var raw=HexFormat.of().parseHex(sample.get("memberRaw").asString());
        var memory=new FakeMemory(member);memory.put(person,HexFormat.of().parseHex(sample.get("personPrefix").asString()));memory.put(person-4,new byte[4]);memory.put(member,raw);
        var row=new HashMap<String,Object>();row.put("record_address","0x"+sample.get("person").asString());row.put("staff_index",sample.get("index").asInt());row.put("unique_id",sample.get("uid").asLong());row.put("_contract_profile",ContractExporter.MODULE_SHA256);
        return new Fixture(memory,row,person,raw);
    }

    private static Fixture fixture()throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/nonplaying_named_bridge_live_20261025.json"))){bytes=in.readNBytes(65537);}
        assertThat(bytes.length).isLessThanOrEqualTo(65536);
        var sample=JsonMapper.builder().build().readTree(bytes).get("returnValues").get(0).get("samples").get(0);
        long p=Long.parseUnsignedLong(sample.get("person").asString(),16);byte[] raw=HexFormat.of().parseHex(sample.get("memberRaw").asString());
        var memory=new FakeMemory(p+L.personToMemberRel());memory.put(p,HexFormat.of().parseHex(sample.get("personPrefix").asString()));memory.put(p+L.constructionRel(),new byte[4]);memory.put(p+L.personToMemberRel(),raw);
        var row=new HashMap<String,Object>();row.put("record",p);row.put("index",sample.get("index").asInt());row.put("unique_id",sample.get("uid").asLong());row.put("_contract_profile",ContractExporter.MODULE_SHA256);
        return new Fixture(memory,row,p,raw);
    }
    private record Fixture(FakeMemory memory,Map<String,Object> row,long person,byte[] raw) { }
    private static final class FakeMemory implements ProcessMemoryReader {
        final Map<Long,Byte> bytes=new HashMap<>();final long member;int reads,memberReads;boolean changeOnReread;
        FakeMemory(long member){this.member=member;}
        void put(long p,byte[] data){for(int i=0;i<data.length;i++)bytes.put(p+i,data[i]);}
        public int pid(){return 1;}public List<MemoryRegion> maps(){return List.of();}public void close(){}
        public byte[] readBytes(long p,int n)throws IOException {assertThat(n).isBetween(0,64);reads++;byte[] out=new byte[n];for(int i=0;i<n;i++){var b=bytes.get(p+i);if(b==null)throw new IOException("Unmapped");out[i]=b;}
            if(p==member&&++memberReads==2&&changeOnReread)out[8]++;return out;}
    }
}
