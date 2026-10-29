package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Offline native-code replay. No FM process, setter invocation, or database required. */
class Fm26CapturedNonPlayingSetterAndScalingTest {
    private static final Path ROOT=Path.of("docs/reverse-engineering/fm26/evidence");

    @Test void writeAndReadPropertiesHaveDifferentCoverageAndNdecIsOneByteAtMember38()throws Exception {
        var model=json("nonplaying_setter_paths_live_checked_20261025.json");
        var code=hex(frame(json("nonplaying_setter_label_code_live_20261025.json"),"setter_code").get("rawHex").asString());
        var getter=json("nonplaying_member_properties_live_checked_20261025.json");
        var ids=new HashSet<Long>();int matched=0;
        for(var row:model.get("rows")) {
            assertThat(ids.add(row.get("propertyId").asLong())).isTrue();
            int pc=Integer.parseInt(row.get("nativeStoreRva").asString(),16)-0x2bad630;
            int offset=row.get("memberByteOffset").asInt();
            assertThat(Arrays.copyOfRange(code,pc,pc+3)).isEqualTo(new byte[]{(byte)0x88,0x46,(byte)offset});
            assertThat(row.get("profileByteOffset").asInt()).isEqualTo(offset-8);
            if(row.get("hasMatchingRawGetter").asBoolean())matched++;
            else {
                assertThat(row.get("numericTagDisplay").asString()).isEqualTo("NDec");
                assertThat(offset).isEqualTo(0x38);
                assertThat(Arrays.copyOfRange(code,0x2bad8ea-0x2bad630,0x2bad8f0-0x2bad630))
                        .isEqualTo(hex("81fa6365444e")); // CMP EDX, 4E446563
                for(var r:getter.get("rows"))assertThat(r.get("propertyId").asLong()).isNotEqualTo(row.get("propertyId").asLong());
            }
        }
        assertThat(ids).hasSize(39);assertThat(matched).isEqualTo(38);
        assertThat(model.get("coveredProfilePositions").size()).isEqualTo(40);
        assertThat(model.get("remainingProfilePositions").size()).isEqualTo(12);
    }

    @Test void native52ByteLoopHas27ScaledPositionsAndKeepsItsBranchAndStorageAnchors()throws Exception {
        var model=json("nonplaying_52_byte_field_model_checked_20261025.json");
        var live=json("nonplaying_scaling_bridge_live_20261025.json");
        byte[] code=hex(frame(live,"profile_scaling_bridge").get("rawHex").asString());
        var indexed=json("nonplaying_indexed_access_live_20261025.json").get("returnValues").get(0);
        byte[] consumer=null;for(var r:indexed.get("frames"))if(r.get("rva").asString().equals("310C230"))consumer=hex(r.get("rawHex").asString());
        assertThat(code).hasSize(642);assertThat(consumer).hasSize(379);
        for(var anchor:model.get("anchors")) {
            int rva=Integer.parseInt(anchor.get("rva").asString(),16);byte[] body=rva<0x310c000?code:consumer;
            int offset=rva-(rva<0x310c000?0x3108960:0x310c230);byte[] expected=hex(anchor.get("hex").asString());
            assertThat(Arrays.copyOfRange(body,offset,offset+expected.length)).isEqualTo(expected);
        }
        var scaled=new HashSet<Integer>();for(var v:model.get("scaledPositions"))scaled.add(v.asInt());
        assertThat(scaled).hasSize(27);assertThat(scaled).contains(13,26,41,42,45,46,51).doesNotContain(0,2,5,12,25,52);
        assertThat(model.get("directPositions").size()).isEqualTo(25);
        assertThat(model.get("currentJavaModeComparisons").asInt()).isEqualTo(21);
        assertThat(model.get("currentJavaModeMismatches").asInt()).isZero();
    }

    @Test void classifierRoundingCannotReplaceTheObservedAttributeGetterRounding()throws Exception {
        var model=json("nonplaying_52_byte_field_model_checked_20261025.json");
        assertThat(model.get("contextSpecificNormalizerDifferences").size()).isEqualTo(19);
        var seven=java.util.stream.StreamSupport.stream(model.get("contextSpecificNormalizerDifferences").spliterator(),false)
                .filter(r->r.get("raw").asInt()==7).findFirst().orElseThrow();
        assertThat(seven.get("attributeGetter").asInt()).isEqualTo(1);
        assertThat(seven.get("classifier").asInt()).isEqualTo(2);
        // Full native algorithms, not an assumption that every consumer divides by five identically.
        assertThat(normalize(7,0xccce)).isEqualTo(1);assertThat(normalize(7,0x13335)).isEqualTo(2);
    }

    @Test void enumNamesRemainCandidatesAndMaxAndSentinelsNeverBecomeProfileFields()throws Exception {
        var model=json("nonplaying_52_byte_field_model_checked_20261025.json");
        assertThat(model.get("rows").size()).isEqualTo(52);
        assertThat(model.get("profileNameCandidates").asInt()).isEqualTo(50);
        var unnamed=new HashSet<Integer>();for(var r:model.get("rows")) {
            assertThat(r.get("profileByteOffset").asInt()).isBetween(0,51);
            assertThat(r.get("nameStatus").asString()).startsWith("UNVERIFIED");
            if(r.get("nameCandidate").isNull())unnamed.add(r.get("profileByteOffset").asInt());
        }
        assertThat(unnamed).containsExactlyInAnyOrder(45,46);
        var labels=json("nonplaying_enum_literal_candidates_20261025.json").get("rows");
        for(var row:labels)if(row.get("enumIndex").asInt()==52)
            assertThat(row.get("candidate").get("labels").get(0).asString()).isEqualTo("MAX");
    }

    private static int normalize(int raw,int bias){int product=(byte)raw*0x6667+bias;return Math.max(1,Math.min(20,(product>>17)+(product>>>31)));}
    private static JsonNode frame(JsonNode root,String kind){for(var r:root.get("returnValues").get(0).get("frames"))if(r.get("kind").asString().equals(kind))return r;throw new AssertionError("Missing frame");}
    private static byte[] hex(String s){return HexFormat.of().parseHex(s);}
    private static JsonNode json(String name)throws Exception {try(var in=Files.newInputStream(ROOT.resolve(name))){byte[] bytes=in.readNBytes(262145);assertThat(bytes.length).isLessThanOrEqualTo(262144);return JsonMapper.builder().build().readTree(bytes);}}
}
