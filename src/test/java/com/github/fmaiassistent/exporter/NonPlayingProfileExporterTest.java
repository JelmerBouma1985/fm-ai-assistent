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
    @Test void fm27CapturedStandaloneCombinedAndHumanMembersKeepAll52NativeBytes() throws Exception {
        var capture=fm27Capture();long base=Long.decode(capture.get("moduleBase").asString());
        var layout=NonPlayingProfileLayout.fm27();
        for(String type:List.of(".?AVACTUAL_NON_PLAYER@db@@",".?AVACTUAL_PLAYER_AND_NON_PLAYER@db@@",".?AVHUMAN_NON_PLAYER@db@@")) {
            assertThat(capture.get("samples").get(type).size()).as("Native samples for %s",type).isPositive();
            for(var sample:capture.get("samples").get(type)) {
                var f=fm27Fixture(sample);
                NonPlayingProfileExporter.captureVerifiedRows(f.memory(),base,List.of(f.row()),layout);
                assertThat(f.row().get("non_playing_profile_hex")).isEqualTo(HexFormat.of().formatHex(f.raw(),8,60));
                assertThat(f.row().get("non_playing_profile_sha256")).isEqualTo(com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256);
                assertThat(NonPlayingProfileLayout.values((String)f.row().get("non_playing_profile_hex"),
                        (String)f.row().get("non_playing_profile_sha256"))).hasSize(50);
                assertThat(sample.get("member").get("relative").asInt()).isEqualTo(type.contains("HUMAN")?-0x438:-0xF8);
            }
        }
    }

    @Test void everyFm27OffsetShiftRejectsTheSameCapturedHumanWithoutReadingIt() throws Exception {
        var capture=fm27Capture();long base=Long.decode(capture.get("moduleBase").asString());
        var sample=capture.get("samples").get(".?AVHUMAN_NON_PLAYER@db@@").get(0);
        var layout=NonPlayingProfileLayout.fm27();var components=NonPlayingProfileLayout.class.getRecordComponents();
        var types=java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        for(int i=0;i<components.length;i++) {
            if(components[i].getType()!=int.class&&components[i].getType()!=long.class)continue;
            Object[] args=new Object[components.length];
            for(int j=0;j<components.length;j++)args[j]=components[j].getAccessor().invoke(layout);
            if(components[i].getType()==int.class)args[i]=(Integer)args[i]+1;else args[i]=(Long)args[i]+1;
            var shifted=NonPlayingProfileLayout.class.getDeclaredConstructor(types).newInstance(args);var f=fm27Fixture(sample);
            assertThatThrownBy(()->NonPlayingProfileExporter.captureVerifiedRows(f.memory(),base,List.of(f.row()),shifted))
                    .as(components[i].getName()).isInstanceOf(IOException.class);
            assertThat(f.memory().reads).isZero();assertThat(f.row()).doesNotContainKey("non_playing_profile_hex");
        }
    }

    private static tools.jackson.databind.JsonNode fm27Capture() throws Exception {
        try(var in=Files.newInputStream(Path.of("src/test/resources/fm27/people-captured.json"))) {
            byte[] bytes=in.readNBytes(1_048_577);assertThat(bytes.length).isLessThanOrEqualTo(1_048_576);
            return JsonMapper.builder().build().readTree(bytes);
        }
    }

    private static Fixture fm27Fixture(tools.jackson.databind.JsonNode sample) {
        long person=Long.decode(sample.get("person").asString());
        long member=person+sample.get("member").get("relative").asInt();
        byte[] raw=HexFormat.of().parseHex(sample.get("member").get("rawHex").asString());
        var memory=new FakeMemory(member);memory.put(person,HexFormat.of().parseHex(sample.get("prefix").asString()));
        memory.put(person-4,new byte[4]);memory.put(member,raw);
        var row=new HashMap<String,Object>();row.put("record_address",sample.get("person").asString());
        row.put("staff_index",sample.get("index").asInt());row.put("unique_id",sample.get("uid").asLong());
        row.put("_contract_profile",com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256);
        return new Fixture(memory,row,person,raw);
    }
    private static final long BASE=0x7FFCCF870000L;
    private static final NonPlayingProfileLayout L=NonPlayingProfileLayout.fm27();

    @Test void capturedCombinedMemberPreservesAll52BytesIncludingUnknown45And46()throws Exception {
        var f=fixture();var row=f.row();
        NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(row),L);
        assertThat(row.get("non_playing_profile_hex")).isEqualTo(HexFormat.of().formatHex(f.raw(),8,60));
        assertThat(row.get("non_playing_profile_sha256")).isEqualTo(NativeModuleProfile.FM27_SHA256);
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
        var values=NonPlayingProfileLayout.values(raw,NativeModuleProfile.FM27_SHA256);
        assertThat(values).hasSize(50);
        assertThat(values).anySatisfy(v->{assertThat(v.name()).isEqualTo("Eccentricity");assertThat(v.nativeValue()).isEqualTo(f.raw()[8+48]);assertThat(v.scale()).isEqualTo(100);});
        assertThat(values).anySatisfy(v->{assertThat(v.name()).isEqualTo("Coaching Technique");assertThat(v.nativeValue()).isEqualTo(f.raw()[8+2]);assertThat(v.scale()).isEqualTo(20);});
        assertThat(L.fields()).noneMatch(v->v.index()==45||v.index()==46||v.index()==52);
        assertThat(NonPlayingProfileLayout.values(raw,"other-build")).isEmpty();
        assertThat(NonPlayingProfileLayout.values("x".repeat(104),NativeModuleProfile.FM27_SHA256)).isEmpty();
        assertThat(NonPlayingProfileLayout.values(null,null)).isEmpty();
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

    @Test void bothFm27CapturedHumansResolveTheirOwnMember()throws Exception {
        for(int i=0;i<2;i++) {
            var f=humanFixture(i);NonPlayingProfileExporter.captureVerifiedRows(f.memory(),BASE,List.of(f.row()),L);
            assertThat(f.row().get("non_playing_profile_hex")).isEqualTo(HexFormat.of().formatHex(f.raw(),8,60));
            assertThat(f.memory().member).isEqualTo(f.person()-0x438);assertThat(f.memory().memberReads).isEqualTo(2);
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

private static Fixture fixture() throws Exception {
        return fm27Fixture(fm27Capture().get("samples").get(".?AVACTUAL_PLAYER_AND_NON_PLAYER@db@@").get(0));
    }
    private static Fixture humanFixture(int index) throws Exception {
        return fm27Fixture(fm27Capture().get("samples").get(".?AVHUMAN_NON_PLAYER@db@@").get(index));
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
