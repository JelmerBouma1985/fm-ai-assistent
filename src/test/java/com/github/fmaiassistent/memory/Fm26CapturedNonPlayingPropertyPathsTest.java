package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Raw instruction/table/member replay; no game calls or invented names for opaque query codes. */
class Fm26CapturedNonPlayingPropertyPathsTest {
    private static final Path ROOT=Path.of("docs/reverse-engineering/fm26/evidence");
    @Test void rawMemberBaseClassIsAtZeroWhileItsProfileStartsAfterTheVptr()throws Exception {
        var rtti=json("nonplaying_member_hierarchy_live_20261025.json").get("returnValues").get(0);
        assertThat(rtti.get("name").asString()).isEqualTo(".?AVNON_PLAYING_DATA@db@@");
        assertThat(rtti.get("colOffset").asInt()).isZero();assertThat(rtti.get("bases").size()).isEqualTo(2);
        var base=rtti.get("bases").get(1);assertThat(base.get("name").asString()).isEqualTo(".?AVBASE_NON_PLAYING_DATA@simatchshared@@");
        assertThat(base.get("memberDisplacement").asInt()).isZero();assertThat(base.get("vbPointerDisplacement").asInt()).isEqualTo(-1);
        // The inherited base view aliases member+0. Member+8 is the profile, not another base object.
    }

    @Test void classPredicatesAndContractVirtualFieldsRemainDistinctAcrossNativeTypes()throws Exception {
        var capture=json("nonplayer_query_virtual_slots_live_20261025.json").get("returnValues").get(0);var rows=capture.get("rows");
        assertThat(byLabel(rows,"standalone_person_1e8").get("raw24Hex").asString()).startsWith("31c0c3");
        assertThat(byLabel(rows,"combined_person_1e8").get("raw24Hex").asString()).startsWith("31c0c3");
        assertThat(byLabel(rows,"human_person_1e8").get("raw24Hex").asString()).startsWith("b001c3");
        assertThat(byLabel(rows,"full_contract_50").get("raw24Hex").asString()).startsWith("0fb64126c3");
        assertThat(byLabel(rows,"basic_contract_50").get("targetRva").asString()).isEqualTo(byLabel(rows,"full_contract_50").get("targetRva").asString());
        var secondary=hex(byLabel(rows,"full_contract_68").get("raw24Hex").asString());
        assertThat(Arrays.copyOfRange(secondary,0,8)).isEqualTo(hex("0fb681c2000000c3"));
        assertThat(ByteBuffer.wrap(secondary).order(ByteOrder.LITTLE_ENDIAN).getInt(3)).isEqualTo(0xc2);
        assertThat(0xc2).isGreaterThanOrEqualTo(0x28); // Outside BASIC; its virtual68 returns zero instead of reading this offset.
        assertThat(byLabel(rows,"basic_contract_68").get("raw24Hex").asString()).startsWith("31c0c3");
    }
    @Test void all38RawMemberPropertiesHaveSignedLoadOpcodesAndGuardedLocalKeyPaths()throws Exception {
        var model=json("nonplaying_member_properties_live_checked_20261025.json");
        var live=json("nonplaying_member_property_code_live_20261025.json").get("returnValues").get(0);
        byte[] code=null;for(var range:live.get("ranges"))if(range.get("kind").asString().equals("code"))code=hex(range.get("rawHex").asString());
        assertThat(code).hasSize(0x2BACC49-0x2BABC70);var ids=new HashSet<Long>();var offsets=new HashSet<Integer>();
        for(var row:model.get("rows")) {
            assertThat(ids.add(row.get("propertyId").asLong())).isTrue();assertThat(offsets.add(row.get("memberByteOffset").asInt())).isTrue();
            int offset=row.get("memberByteOffset").asInt(),pc=Integer.parseInt(row.get("nativeLoadRva").asString(),16)-0x2BABC70;
            assertThat(Arrays.copyOfRange(code,pc,pc+4)).isEqualTo(new byte[]{0x0f,(byte)0xbe,0x7b,(byte)offset});
            var witness=row.get("dispatchWitness");assertThat(witness.get("propertyId").asLong()).isEqualTo(row.get("propertyId").asLong());
            assertThat(offset).isBetween(8,59);assertThat(row.get("profileByteOffset").asInt()).isEqualTo(offset-8);
        }
        assertThat(ids).hasSize(38);assertThat(offsets).hasSize(38);
        // Synthetic signed sentinel: the MOVSX property view is -1, not255. This is not a live -1 observation.
        assertThat((int)(byte)0xff).isEqualTo(-1);
    }

    @Test void relativeTablesRejectOutOfRangeKeysAndKeepCaseSensitivePropertiesSeparate()throws Exception {
        var paths=json("nonplayer_property_paths_tables_checked_20261025.json");
        for(var table:paths.get("tables")) {
            long base=Long.parseLong(table.get("propertyBase").asString(),16);int count=table.get("count").asInt();
            var raw=ByteBuffer.wrap(hex(table.get("rawHex").asString())).order(ByteOrder.LITTLE_ENDIAN);
            for(var entry:table.get("entries")) {
                int index=entry.get("index").asInt();assertThat(entry.get("propertyId").asLong()).isEqualTo(base+index);
                assertThat(Long.parseLong(entry.get("targetRva").asString(),16)).isEqualTo(Long.parseLong(table.get("tableRva").asString(),16)+raw.getInt(index*4));
            }
            assertThat(accepted(base-1,base,count)).isFalse();assertThat(accepted(base+count,base,count)).isFalse();
        }
        var fields=json("nonplayer_native_property_field_catalogue_checked_20261025.json").get("rows");
        var direct=byTag(fields,"npmm");var scaled=byTag(fields,"Npmm");
        assertThat(direct.get("nativePropertyId").asLong()).isNotEqualTo(scaled.get("nativePropertyId").asLong());
        assertThat(direct.get("profileByteOffset").asInt()).isEqualTo(7);assertThat(scaled.get("profileByteOffset").asInt()).isEqualTo(30);
    }

    @Test void sharedPersonByteAndDerivedQueryDoNotBecomeInventedProfileOffsets()throws Exception {
        var fields=json("nonplayer_native_property_field_catalogue_checked_20261025.json");
        var shared=fields.get("sharedBaseRelation");assertThat(shared.get("resolvesToPerson").asInt()).isEqualTo(0x70);
        for(var table:shared.get("vbTables")) {
            var raw=ByteBuffer.wrap(hex(table.get("rawHex").asString())).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(raw.getInt(8)+0x48-raw.getInt(4)).isEqualTo(0x70);
        }
        var npad=byTag(fields.get("rows"),"Npad");assertThat(npad.get("profileByteOffset").isNull()).isTrue();
        assertThat(npad.get("personByteOffset").asInt()).isEqualTo(0x70);
        var derived=byTag(fields.get("rows"),"npcs");assertThat(derived.get("rawWidthBits").isNull()).isTrue();
        assertThat(derived.get("componentByteOffset").isNull()).isTrue();
        var raw=json("nonplaying_member_properties_live_checked_20261025.json");
        assertThat(raw.get("uniqueProfileBytePositionsAcrossBothLayers").size()).isEqualTo(39);
        assertThat(raw.get("remainingSerializedProfileBytePositions").size()).isEqualTo(13);
    }

    @Test void capturedMemberAnchorAndMachineDisplacementRejectPlausibleShiftedFieldOffsets()throws Exception {
        var model=json("nonplaying_member_properties_live_checked_20261025.json");
        byte[] file;try(var in=Files.newInputStream(ROOT.resolve("all_combined_profiles_live_20261025.csv"))){file=in.readNBytes(1048577);}
        assertThat(file.length).isLessThanOrEqualTo(1048576);
        var line=new String(file,java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).findFirst().orElseThrow();byte[] member=hex(line.split(",",-1)[7]);
        assertThat(member).hasSize(64);assertThat(ByteBuffer.wrap(member).order(ByteOrder.LITTLE_ENDIAN).getLong()).isEqualTo(0x7FF988310000L+0x449B2E8);
        for(var field:model.get("rows")) {
            int expected=field.get("memberByteOffset").asInt();assertThat(decode(member,field,expected)).isEqualTo((int)member[expected]);
            assertThatThrownBy(()->decode(member,field,expected+1)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    private static int decode(byte[] frame,JsonNode field,int offset){byte[] instruction=hex(field.get("nativeLoadHex").asString());
        if(frame.length!=64||offset<8||offset>=60||Byte.toUnsignedInt(instruction[3])!=offset)throw new IllegalArgumentException("Member field drift");return frame[offset];}
    private static boolean accepted(long key,long base,int count){return Long.compareUnsigned((key-base)&0xffff_ffffL,count)<0;}
    private static JsonNode byTag(JsonNode rows,String key){for(var row:rows)if(row.get("numericTagDisplay").asString().equals(key))return row;throw new AssertionError("Missing tag");}
    private static JsonNode byLabel(JsonNode rows,String key){for(var row:rows)if(row.get("label").asString().equals(key))return row;throw new AssertionError("Missing label");}
    private static byte[] hex(String s){return HexFormat.of().parseHex(s);}
    private static JsonNode json(String name)throws Exception {try(var in=Files.newInputStream(ROOT.resolve(name))){var raw=in.readNBytes(262145);assertThat(raw.length).isLessThanOrEqualTo(262144);return JsonMapper.builder().build().readTree(raw);}}
}
