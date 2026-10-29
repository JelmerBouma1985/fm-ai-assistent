package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Independent captured native layout replay; no UI flag or production equivalence claim. */
class Fm26CapturedClubInGameDataEvidenceTest {
    @Test
    @org.junit.jupiter.api.Tag("fm26-live")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="fm26.facilities.database",matches="true")
    void actualStoredValuesPreserveOwnByteAndDoNotClaimToBeTheDerivedNativeContext() throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");
        assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class))
                .isEqualTo("6624");
        var reports=new ArrayList<Map<String,Object>>();
        var graph=receipt("club_facility_relation_counterexamples_live_20261026.json");
        for(var example:graph.get("cases")){
            long uid=example.get("uid").asLong();
            var rows=jdbc.queryForList("SELECT name,source_address,youth_facilities FROM clubs WHERE unique_id=?",uid);
            assertThat(rows).isNotEmpty();
            int own=example.get("ownRaw123").asInt(),derived=example.get("derivedNativeEaff30Flags11").asInt();
            for(var row:rows){
                assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(address(example.get("club").asString()));
                assertThat(((Number)row.get("YOUTH_FACILITIES")).intValue()).isEqualTo(own).isNotEqualTo(derived);
            }
            reports.add(Map.of("uid",uid,"name",rows.getFirst().get("NAME"),"views",rows.size(),
                    "storedOwnRawValue",own,"derivedFlags11Value",derived));
        }
        var report=Map.of("pid",6624,"status","PARTIALLY VERIFIED","examples",reports,
                "limitation","Read-only actual database stores own byte. Native flag1/1 output is derived; active game UI flags/value not observed. This does not establish a production error.");
        String output=System.getProperty("fm26.facilities.database.report");
        assertThat(output).isNotBlank();
        Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }

    @Test
    void nativeTypedOwnersAndSharingFlagsDistinguishOwnAndDerivedFacilityBytes() throws Exception {
        var graph = receipt("club_facility_relation_counterexamples_live_20261026.json");
        assertThat(graph.get("guardsUnchanged").asBoolean()).isTrue();
        long base = address(graph.get("base").asString());
        var nodes = new HashMap<Long,JsonNode>();
        for (var n : graph.get("nodes")) {
            var club = little(n.get("clubRaw").asString());
            var data = little(n.get("childRaw").asString());
            assertThat(club.capacity()).isEqualTo(0x170);
            assertThat(data.capacity()).isEqualTo(0x128);
            assertThat(club.getLong(0)).isEqualTo(base + 0x44BA518);
            assertThat(club.getInt(8)).isEqualTo(n.get("index").asInt());
            assertThat(Integer.toUnsignedLong(club.getInt(12))).isEqualTo(n.get("uid").asLong());
            assertThat(club.getLong(0x100)).isEqualTo(address(n.get("child").asString()));
            assertThat((int)data.get(0x123)).isEqualTo(n.get("value123").asInt());
            nodes.put(address(n.get("address").asString()),n);
        }
        assertThat(nodes).hasSize(4);
        var expected = Map.of(512L,16,531L,10);
        for (var example : graph.get("cases")) {
            long uid = example.get("uid").asLong();
            var owner = nodes.get(address(example.get("club").asString()));
            var club = little(owner.get("clubRaw").asString());
            int own = little(owner.get("childRaw").asString()).get(0x123);
            // Both retained examples skip ancestor traversal but still apply the sharing loop.
            assertThat(club.get(0x163) & 0x10).isEqualTo(0x10);
            int result = Math.max(0,own);
            int contributors = 0;
            for (var relation : owner.get("links")) {
                var row = little(relation.get("raw").asString());
                assertThat(row.capacity()).isEqualTo(0x31);
                int kind = Byte.toUnsignedInt(row.get(0x30));
                if (row.getLong(8) != address(owner.get("address").asString())
                        || kind >= 4 && kind <= 8 || kind == 20
                        || (row.getInt(0x1C) & 0x10400) == 0) continue;
                var other = nodes.get(row.getLong(0));
                assertThat(other).isNotNull();
                result = Math.max(result,little(other.get("childRaw").asString()).get(0x123));
                contributors++;
            }
            assertThat(contributors).isEqualTo(1);
            assertThat(result).isEqualTo(expected.get(uid)).isNotEqualTo(own);
            assertThat(result).isEqualTo(example.get("derivedNativeEaff30Flags11").asInt());
        }
    }

    @Test
    void pointerCollectionCountAndRecordCountWidthAreIndependentNativeLayouts() throws Exception {
        var live = receipt("club_in_game_data_owned_children_complete_live_20261026.json");
        long base = address(live.get("base").asString());
        int checked = 0;
        for (var owner : live.get("samples")) {
            for (var collection : owner.get("collections")) {
                if (collection.get("address").asString().equals("0")) continue;
                assertThat(owner.get("uid").asLong()).isEqualTo(505);
                assertThat(collection.get("childOffset").asInt()).isEqualTo(0x20);
                var child = little(owner.get("childRaw").asString());
                assertThat(child.getLong(0x20)).isEqualTo(address(collection.get("address").asString()));
                var header = little(collection.get("header").asString());
                assertThat(header.capacity()).isEqualTo(24);
                long count = (header.getLong(8)-header.getLong(0))/8;
                assertThat(count).isEqualTo(13).isEqualTo(collection.get("count").asLong());
                assertThat((header.getLong(8)-header.getLong(0))/24).isNotEqualTo(count);
                assertThat(collection.get("sample").size()).isEqualTo(13);
                for (var row : collection.get("sample")) {
                    var bytes = HexFormat.of().parseHex(row.get("raw").asString());
                    assertThat(bytes).hasSize(20);
                    assertThat(little(row.get("raw").asString()).getLong()).isEqualTo(base+0x44BAC58);
                    assertThat(row.get("type").get("name").asString()).isEqualTo(".?AVINT_DUTY_PLAYER_COUNT@db@@");
                    int countByte = Byte.toUnsignedInt(bytes[0x10]);
                    byte[] changedUnassignedBytes = bytes.clone();
                    changedUnassignedBytes[0x11] = 0x7F;
                    assertThat(Byte.toUnsignedInt(changedUnassignedBytes[0x10])).isEqualTo(countByte);
                    assertThat(ByteBuffer.wrap(changedUnassignedBytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0x10))
                            .isNotEqualTo(countByte);
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(13);
        var model = json("club_in_game_data_model_expanded_verified_20261026.json");
        assertThat(model.get("nativeCodeBytesStaticRuntimeEqual").asInt()).isEqualTo(9104);
        assertThat(model.get("physicalFields").get("intDutyCount").get("field10").get("bytes").asInt()).isEqualTo(1);
        assertThat(model.get("physicalFields").get("intDutyCount").get("objectBytes").asInt()).isEqualTo(24);
    }

    private static JsonNode receipt(String name) throws Exception {
        var node = json(name);
        assertThat(node.get("ok").asBoolean()).isTrue();
        return node.get("returnValues").get(0);
    }
    private static JsonNode json(String name) throws Exception {
        byte[] data;
        try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence",name))){
            data=in.readNBytes(1_048_577);
        }
        assertThat(data.length).isLessThanOrEqualTo(1_048_576);
        return JsonMapper.builder().build().readTree(data);
    }
    private static ByteBuffer little(String hex) { return ByteBuffer.wrap(HexFormat.of().parseHex(hex)).order(ByteOrder.LITTLE_ENDIAN); }
    private static long address(String hex) { return Long.parseUnsignedLong(hex,16); }
}

