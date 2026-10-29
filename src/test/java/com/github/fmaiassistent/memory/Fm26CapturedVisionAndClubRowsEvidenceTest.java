package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** Golden native/wire fixtures; does not establish visible vision labels or reader execution. */
class Fm26CapturedVisionAndClubRowsEvidenceTest {
    @Test
    void fourByteElementStoresTwoVersionedWordsAndPreservesModernHighBits() throws Exception {
        var report = read("club_context_packed_words_and_tail_20261021.json");
        var vector = report.get("clubContextFinalCollectionsPrefix").get("vector118");
        var encoded = vector.get("rows").get(0);
        byte[] nativeWords = hex(vector.get("nativeRawHex").asString());
        assertThat(nativeWords).containsExactly((byte) 0xFA, (byte) 0x1B, (byte) 0x3F, (byte) 0x3E);
        assertThat(encoded.get("firstVersion").asInt()).isEqualTo(5);
        assertThat(encoded.get("secondVersion").asInt()).isEqualTo(5);
        assertThat(encoded.get("first").asInt()).isEqualTo(0x1BFA);
        assertThat(encoded.get("second").asInt()).isEqualTo(0x3E3F);
        assertThat(encoded.get("second").asInt() & 0x2000).isNotZero();
        var complete = report.get("firstClubContextComplete");
        byte[] wire = hex(complete.get("wireHex").asString());
        int offset = encoded.get("wireOffset").asInt() - complete.get("recordStart").asInt();
        assertThat(Arrays.copyOfRange(wire, offset, offset + 7))
                .containsExactly((byte) 1, (byte) 5, (byte) 0xFA, (byte) 0x1B, (byte) 5, (byte) 0x3F, (byte) 0x3E);
    }

    @Test
    void clubReferenceRowsRetainNullsDuplicatesAndThirtyTwoBitIndexes() throws Exception {
        var decoded = read("club_context_packed_words_and_tail_20261021.json").get("clubContextLastRows").get("rows");
        var nativeRows = read("club_context_club_rows_live_20261021.json").get("returnValues").get(0).get("rows");
        assertThat(decoded.size()).isEqualTo(8);
        int nulls = 0;
        for (int i = 0; i < decoded.size(); i++) {
            var row = decoded.get(i);
            var nativeRow = nativeRows.get(i);
            byte[] wire = hex(row.get("wireHex").asString()), raw = hex(nativeRow.get("raw").asString());
            assertThat(wire).hasSize(27);
            assertThat(raw).hasSize(32);
            assertThat(little(wire).getInt(14)).isEqualTo(nativeRow.get("clubIndex").asInt());
            assertThat(little(raw).getLong()).isEqualTo(Long.parseUnsignedLong(nativeRow.get("clubAddress").asString(), 16));
            assertThat(little(wire).getInt(1)).isEqualTo(little(raw).getInt(0xC));
            assertThat(little(wire).getInt(5)).isEqualTo(little(raw).getInt(8));
            assertThat(wire[9]).isEqualTo(raw[0x1C]);
            assertThat(little(wire).getInt(10)).isEqualTo(little(raw).getInt(0x10));
            assertThat(little(wire).getInt(18)).isEqualTo(little(raw).getInt(0x14));
            assertThat(wire[22]).isEqualTo(raw[0x1D]);
            assertThat(little(wire).getInt(23)).isEqualTo(little(raw).getInt(0x18));
            if (row.get("clubIndex").asInt() == -1) nulls++;
        }
        assertThat(nulls).isEqualTo(3);
        assertThat(decoded.get(5).get("clubIndex").asInt()).isEqualTo(16);
        assertThat(decoded.get(6).get("clubIndex").asInt()).isEqualTo(16);
        assertThat(decoded.get(5).get("clubUid").asLong()).isEqualTo(85);
    }

    @Test
    void typedVisionObjectivesCompleteOnlyTheSelectedClubRecord() throws Exception {
        var report = read("club_context_packed_words_and_tail_20261021.json");
        var vision = report.get("clubContextVisionObjectives");
        var capture = read("club_context_vision_objectives_live_20261021.json").get("returnValues").get(0);
        assertThat(capture.get("type").get("name").asString()).isEqualTo(".?AVVISION_OBJECTIVE@db@@");
        assertThat(vision.get("rows").size()).isEqualTo(3);
        long moduleBase = Long.parseUnsignedLong(capture.get("moduleBase").asString(), 16);
        int[] kinds = {150, 120, 155}, flags = {7, 8, 5};
        for (int i = 0; i < 3; i++) {
            var row = vision.get("rows").get(i);
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(capture.get("rows").get(i).get("raw").asString());
            assertThat(wire).hasSize(20);
            assertThat(raw).hasSize(48);
            assertThat(little(raw).getLong()).isEqualTo(moduleBase + 0x4342878L);
            assertThat(wire[0]).isEqualTo((byte) 2);
            assertThat(Byte.toUnsignedInt(wire[1])).isEqualTo(kinds[i]);
            assertThat(Byte.toUnsignedInt(wire[2])).isEqualTo(flags[i]);
            assertThat(wire[1]).isEqualTo(raw[0x2C]);
            assertThat(wire[2]).isEqualTo(raw[0x2D]);
            assertThat(little(wire).getInt(4)).isEqualTo(little(raw).getInt(8));
            assertThat(little(wire).getInt(4) & 0x800000).isZero();
            for (int field = 0; field < 3; field++)
                assertThat(little(wire).getInt(8 + field * 4)).isEqualTo(little(raw).getInt(0x20 + field * 4));
        }
        var complete = report.get("firstClubContextComplete");
        assertThat(complete.get("clubUid").asInt()).isEqualTo(94);
        assertThat(complete.get("serializedBytes").asInt()).isEqualTo(1421);
        assertThat(complete.get("end").asInt()).isEqualTo(838214);
        assertThat(hex(complete.get("wireHex").asString())).hasSize(1421);
    }

    @Test
    void selectedClubScalarBytesSurviveTheObservedProcessRestart() throws Exception {
        var report = read("next_club_context_frontier_20261022.json");
        var old = read("club_context_packed_words_and_tail_20261021.json").get("firstClubContextComplete");
        var fresh = read("next_present_club_context_live_20261022.json").get("returnValues").get(0);
        byte[] before = hex(old.get("nativeParentRawHex").asString());
        byte[] after = hex(fresh.get("rows").get(0).get("raw").asString());
        assertThat(fresh.get("pid").asInt()).isEqualTo(20884);
        assertThat(report.get("scalarChecks").size()).isEqualTo(74);
        int bytes = 0;
        for (var check : report.get("scalarChecks")) {
            int offset = Integer.parseInt(check.get("offsetHex").asString(), 16);
            int width = check.get("width").asInt();
            assertThat(Arrays.copyOfRange(after, offset, offset + width))
                    .containsExactly(Arrays.copyOfRange(before, offset, offset + width));
            bytes += width;
        }
        assertThat(bytes).isEqualTo(147);
        // Pointer-bearing bytes change; equal scalars do not prove complete save identity.
        assertThat(little(before).getLong(0x68)).isNotEqualTo(little(after).getLong(0x68));
    }

    @Test
    void nextClubSubrecordUsesATypeMarkerReplacementAndZeroTerminatedValue() throws Exception {
        var report = read("next_club_context_frontier_20261022.json").get("nextContextPrefix");
        var child = report.get("subrecord28").get("child");
        byte[] wire = hex(child.get("wireHex").asString());
        byte[] raw = hex(read("club132_subrecord28_live_20261022.json")
                .get("returnValues").get(0).get("rows").get(0).get("raw").asString());
        assertThat(wire).hasSize(22);
        assertThat(raw).hasSize(32);
        assertThat(wire[0]).isEqualTo((byte) 2);
        assertThat(little(wire).getShort(1)).isEqualTo(little(raw).getShort(0x1C));
        assertThat(wire[3]).isEqualTo((byte) 0x66);
        assertThat(wire[4]).isEqualTo(raw[0x1E]).isEqualTo((byte) 0xFF);
        assertThat(wire[5]).isEqualTo((byte) 2);
        assertThat(little(wire).getInt(6)).isEqualTo(little(raw).getInt(0));
        assertThat(little(wire).getInt(10)).isEqualTo(little(raw).getInt(4));
        assertThat(little(wire).getInt(14)).isZero(); // Terminates the typed sequence; no extra pair.
        assertThat(Arrays.copyOfRange(raw, 8, 24)).containsOnly((byte) 0);
        assertThat(little(wire).getInt(18)).isEqualTo(little(raw).getInt(24)).isEqualTo(9);
        assertThat(report.get("clubUid").asInt()).isEqualTo(321);
        assertThat(report.get("end").asInt()).isEqualTo(838400);
        assertThat(report.get("count50").asInt()).isEqualTo(1);
    }

    @Test
    void club132Vector50AndOrderedPersonReferencesMatchIndependentCaptures() throws Exception {
        var report = read("club132_vector50_and_people_20261022.json").get("peopleContinuation");
        var capture = read("club132_vector50_people_live_20261022.json").get("returnValues").get(0);
        var row = report.get("vector50Rows").get(0);
        byte[] wire = hex(row.get("wireHex").asString());
        byte[] raw = hex(capture.get("vector50").get("rows").get(0).get("raw").asString());
        assertThat(raw).hasSize(32);
        assertThat(wire).hasSize(6);
        assertThat(wire[1]).isEqualTo(raw[0x18]);
        assertThat(little(wire).getInt(2)).isZero();
        assertThat(little(raw).getLong(8)).isEqualTo(little(raw).getLong());
        for (int i = 0; i < 2; i++) {
            var refs = report.get("personVectors").get(i).get("rows");
            var nativeRefs = capture.get("personVectors").get(i).get("rows");
            assertThat(refs.size()).isEqualTo(i == 0 ? 3 : 1);
            for (int j = 0; j < refs.size(); j++) {
                byte[] prefix = hex(nativeRefs.get(j).get("prefix").asString());
                assertThat(refs.get(j).get("index").asInt()).isEqualTo(little(prefix).getInt(8));
                assertThat(refs.get(j).get("uid").asLong()).isEqualTo(Integer.toUnsignedLong(little(prefix).getInt(12)));
            }
        }
        assertThat(report.get("end").asInt()).isEqualTo(838438);
    }

    @Test
    void club132Version17LeavesUseTheNativeReadOrderAndPreserveTheNextHeader() throws Exception {
        var report = read("club132_children_and_leaves_20261022.json").get("childrenContinuation");
        var captured = read("club132_children_teams_live_20261022.json").get("returnValues").get(0)
                .get("childVectors").get(0).get("rows");
        assertThat(report.get("a0Rows").size()).isEqualTo(11);
        var leaves = report.get("a0Rows").get(1).get("leaves");
        assertThat(leaves.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            byte[] wire = hex(leaves.get(i).get("wireHex").asString());
            byte[] raw = hex(captured.get(1).get("leaves").get(i).get("raw").asString());
            assertThat(wire).hasSize(28);
            assertThat(raw).hasSize(28);
            assertThat(little(wire).getShort()).isEqualTo((short) 17);
            assertThat(Arrays.copyOfRange(wire, 2, 6)).containsExactly(Arrays.copyOfRange(raw, 0, 4));
            assertThat(wire[6]).isEqualTo(raw[0x18]);
            assertThat(Arrays.copyOfRange(wire, 7, 19)).containsExactly(Arrays.copyOfRange(raw, 4, 16));
            assertThat(little(wire).getInt(19)).isEqualTo(little(raw).getInt(0x14));
            assertThat(wire[23]).isEqualTo(raw[0x19]);
            assertThat(little(wire).getInt(24)).isEqualTo(little(raw).getInt(0x10));
        }
        assertThat(report.get("a0Rows").get(1).get("end").asInt()
                - report.get("a0Rows").get(1).get("start").asInt()).isEqualTo(69 + 2 * 28);
        assertThat(report.get("b8Count").asInt()).isEqualTo(2);
        assertThat(report.get("firstB8EntryOffset").asInt()).isEqualTo(839257);
    }

    @Test
    void club132B8RowsReorderFourDwordsWithoutCopyingNativeBytes() throws Exception {
        var rows = read("club132_complete_context_20261022.json").get("completeContinuation").get("b8Rows");
        var capture = read("club132_b8_remaining_headers_live_20261022.json").get("returnValues").get(0)
                .get("headers").get(0).get("rows");
        assertThat(rows.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            byte[] wire = hex(rows.get(i).get("wireHex").asString());
            byte[] raw = hex(capture.get(i).get("raw").asString());
            assertThat(wire).hasSize(18);
            assertThat(raw).hasSize(16);
            assertThat(little(wire).getInt(2)).isEqualTo(little(raw).getInt(4));
            assertThat(little(wire).getInt(6)).isEqualTo(little(raw).getInt(12));
            assertThat(little(wire).getInt(10)).isEqualTo(little(raw).getInt(0));
            assertThat(little(wire).getInt(14)).isEqualTo(little(raw).getInt(8));
        }
    }

    @Test
    void club132CompleteRecordRetainsNonNullCompetitionAndMatchesTheNextPresence() throws Exception {
        var report = read("club132_complete_context_20261022.json");
        var complete = report.get("completeContext");
        assertThat(complete.get("start").asInt()).isEqualTo(838321);
        assertThat(complete.get("end").asInt()).isEqualTo(840216);
        assertThat(hex(complete.get("wireHex").asString())).hasSize(1895);
        var tail = report.get("completeContinuation");
        assertThat(tail.get("packedRows").size()).isEqualTo(4);
        assertThat(tail.get("relatedRows").size()).isEqualTo(24);
        assertThat(tail.get("visionRows").size()).isEqualTo(2);
        byte[] parent = hex(complete.get("nativeParentRawHex").asString());
        var comp = read("club132_competition_next_boundary_live_20261022.json").get("returnValues").get(0);
        byte[] identity = hex(comp.get("competition").get("prefix").asString());
        assertThat(tail.get("compIndex").asInt()).isEqualTo(little(identity).getInt(8)).isEqualTo(124);
        assertThat(Integer.toUnsignedLong(little(identity).getInt(12))).isEqualTo(102423);
        assertThat(little(parent).getLong(0x48))
                .isEqualTo(Long.parseUnsignedLong(comp.get("competition").get("address").asString(), 16));
        assertThat(report.get("nextPresenceCandidate").get("offset").asInt()).isEqualTo(840216);
        assertThat(report.get("nextPresenceCandidate").get("byte").asInt()).isZero();
        assertThat(comp.get("nextClub").get("present").asBoolean()).isFalse();
        assertThat(comp.get("nextClub").get("uid").asInt()).isEqualTo(322);
    }

    @Test
    void club291Optional20UsesReplacementKindAndEmptyTypedSequence() throws Exception {
        var report = read("known_club_variants_with_optional20_wire_walk_20261022.json");
        byte[] wire = hex(report.get("runtimeSubset").get("firstOptional20").get("wireHex").asString());
        var capture = read("club_wire_walk_frontier_live_20261022.json").get("returnValues").get(0);
        byte[] raw = hex(capture.get("frontier").get("optional20Raw").asString());
        assertThat(wire).hasSize(14);
        assertThat(raw).hasSize(32);
        assertThat(wire[0]).isEqualTo((byte) 2);
        assertThat(little(wire).getShort(1)).isEqualTo(little(raw).getShort(0x1C));
        assertThat(wire[3]).isEqualTo((byte) 0x66);
        assertThat(wire[4]).isEqualTo(raw[0x1E]).isEqualTo((byte) 1);
        assertThat(wire[5]).isEqualTo((byte) 2);
        assertThat(little(wire).getInt(6)).isZero(); // Empty typed sequence, not a direct missing payload.
        assertThat(Arrays.copyOfRange(raw, 0, 24)).containsOnly((byte) 0);
        assertThat(little(wire).getInt(10)).isEqualTo(little(raw).getInt(24)).isEqualTo(8);
        assertThat(capture.get("frontier").get("clubUid").asInt()).isEqualTo(600);
    }

    @Test
    void wireWalkerPreservesKnownBoundariesAndStopsBeforeNonemptyVector178() throws Exception {
        var report = read("known_club_variants_with_optional20_wire_walk_20261022.json");
        assertThat(report.get("completedContexts").asInt()).isEqualTo(5);
        assertThat(report.get("contexts").get(0).get("end").asInt()).isEqualTo(838214);
        assertThat(report.get("contexts").get(1).get("end").asInt()).isEqualTo(840216);
        assertThat(report.get("runtimeSubset").get("presenceChecks").size()).isEqualTo(159);
        var live = read("club_wire_walk_frontier_live_20261022.json").get("returnValues").get(0);
        for (int i = 0; i < 159; i++) {
            assertThat(report.get("runtimeSubset").get("presenceChecks").get(i).get("present").asBoolean())
                    .isEqualTo(live.get("rows").get(i).get("present").asBoolean());
        }
        var stop = report.get("stop");
        assertThat(stop.get("stage").asString()).isEqualTo("CLUB291.178");
        assertThat(stop.get("offset").asInt()).isEqualTo(853659);
        assertThat(stop.get("cursor").asInt()).isEqualTo(853663);
        byte[] parent = hex(live.get("rows").get(158).get("raw").asString());
        assertThat(little(parent).getLong(0x180) - little(parent).getLong(0x178))
                .isEqualTo(stop.get("partial").get("counts").get("178").asLong() * 20);
        assertThat(report.get("reachedNationalContainerSection").asBoolean()).isFalse();
    }

    @Test
    void club291Vector178UsesEighteenWireBytesAndTwentyNativeBytesPerElement() throws Exception {
        var report = read("known_club_variants_with_vector178_wire_walk_20261022.json")
                .get("runtimeSubset").get("vector178");
        byte[] all = hex(read("club291_vector178_live_20261022.json").get("returnValues").get(0).get("raw").asString());
        assertThat(all).hasSize(300);
        assertThat(report.get("rows").size()).isEqualTo(15);
        for (int i = 0; i < 15; i++) {
            byte[] wire = hex(report.get("rows").get(i).get("wireHex").asString());
            byte[] raw = Arrays.copyOfRange(all, i * 20, i * 20 + 20);
            assertThat(wire).hasSize(18);
            assertThat(wire[0]).isEqualTo((byte) 5);
            assertThat(little(wire).getShort(1)).isEqualTo(little(raw).getShort(12));
            assertThat(little(wire).getInt(3)).isEqualTo(little(raw).getInt(0));
            assertThat(little(wire).getInt(7)).isEqualTo(little(raw).getInt(4));
            assertThat(little(wire).getInt(11)).isEqualTo(little(raw).getInt(8));
            assertThat(Arrays.copyOfRange(wire, 15, 18)).containsExactly(Arrays.copyOfRange(raw, 14, 17));
            assertThat(Arrays.copyOfRange(raw, 17, 20)).containsOnly((byte) 0);
        }
    }

    @Test
    void club291HasSeparateTypedVisionVectorsAt190And198() throws Exception {
        var report = read("known_club_variants_with_vision190_wire_walk_20261022.json");
        var vectors = report.get("runtimeSubset").get("visionVectors");
        assertThat(vectors.size()).isEqualTo(2);
        int[] expectedCounts = {2, 8};
        String[] offsets = {"190", "198"};
        for (int i = 0; i < 2; i++) {
            var capture = read("club291_vision" + offsets[i] + "_live_20261022.json").get("returnValues").get(0);
            assertThat(vectors.get(i).get("offsetHex").asString()).isEqualTo(offsets[i]);
            assertThat(vectors.get(i).get("rows").size()).isEqualTo(expectedCounts[i]);
            assertThat(capture.get("type").get("name").asString()).isEqualTo(".?AVVISION_OBJECTIVE@db@@");
            for (int j = 0; j < expectedCounts[i]; j++) {
                byte[] wire = hex(vectors.get(i).get("rows").get(j).get("wireHex").asString());
                byte[] raw = hex(capture.get("rows").get(j).get("raw").asString());
                assertThat(wire).hasSize(20);
                assertThat(raw).hasSize(48);
                assertThat(wire[1]).isEqualTo(raw[0x2C]);
                assertThat(wire[2]).isEqualTo(raw[0x2D]);
                assertThat(little(wire).getInt(4)).isEqualTo(little(raw).getInt(8));
                for (int k = 0; k < 3; k++)
                    assertThat(little(wire).getInt(8 + k * 4)).isEqualTo(little(raw).getInt(0x20 + k * 4));
            }
        }
        assertThat(report.get("completedContexts").asInt()).isEqualTo(14);
        assertThat(report.get("stop").get("stage").asString()).isEqualTo("CLUB308.148");
        assertThat(report.get("stop").get("cursor").asInt()).isEqualTo(929863);
    }

    @Test
    void club308Vector148HasASeparateContiguousFourByteValueArray() throws Exception {
        var report = read("club_section_and_vector148_validation_20261022.json").get("vector148");
        var captured = read("club308_vector148_live_20261022.json").get("returnValues").get(0);
        byte[] wire = hex(report.get("rows").get(0).get("wireHex").asString());
        byte[] raw = hex(captured.get("rows").get(0).get("raw").asString());
        byte[] nested = hex(captured.get("rows").get(0).get("nestedRaw").asString());
        assertThat(report.get("clubUid").asInt()).isEqualTo(617);
        assertThat(wire).hasSize(34);
        assertThat(raw).hasSize(56);
        assertThat(nested).hasSize(4);
        int[] nativeOffsets = {12, 0, 4, 8, 16};
        for (int i = 0; i < nativeOffsets.length; i++)
            assertThat(little(wire).getInt(1 + i * 4)).isEqualTo(little(raw).getInt(nativeOffsets[i]));
        assertThat(Arrays.copyOfRange(wire, 21, 26)).containsExactly(Arrays.copyOfRange(raw, 20, 25));
        assertThat(little(wire).getInt(26)).isEqualTo(1);
        assertThat(Arrays.copyOfRange(wire, 30, 34)).containsExactly(nested);
        assertThat(little(raw).getLong(0x28) - little(raw).getLong(0x20)).isEqualTo(4);
    }

    @Test
    void completeClubSectionPresenceMatchesEveryCapturedRegistryEntry() throws Exception {
        var report = read("club_section_and_vector148_validation_20261022.json");
        var live = read("club_context_presence_inventory_v2_live_20261022.json").get("returnValues").get(0);
        byte[] wireBits = hex(report.get("presenceInventory").get("wirePresenceBits").asString());
        byte[] nativeBits = hex(live.get("presenceBits").asString());
        assertThat(wireBits).containsExactly(nativeBits);
        assertThat(live.get("clubUids").size()).isEqualTo(26839);
        int count = 0;
        for (byte value : nativeBits) count += Integer.bitCount(Byte.toUnsignedInt(value));
        assertThat(count).isEqualTo(393);
        assertThat(report.get("visitedPresenceFlags").asInt()).isEqualTo(26839);
        assertThat(report.get("sectionEndExclusive").asInt()).isEqualTo(3029220);
        assertThat(report.get("reachedNationalContainerSection").asBoolean()).isTrue();
    }

    @Test
    void leagueBodyClubsHaveAnIndependentlyCapturedZeroOffsetClubBase() throws Exception {
        var captured = read("club_registry_types_live_20261022.json").get("returnValues").get(0).get("types");
        assertThat(captured.size()).isEqualTo(2);
        int total = 0;
        boolean leagueBody = false;
        for (var type : captured) {
            total += type.get("count").asInt();
            if (type.get("name").asString().equals(".?AVLEAGUE_BODY_CLUB@db@@")) {
                leagueBody = true;
                assertThat(type.get("count").asInt()).isEqualTo(462);
            }
            boolean found = false;
            for (var base : type.get("bases")) {
                if (base.get("name").asString().equals(".?AVCLUB@db@@")) {
                    byte[] raw = hex(base.get("raw").asString());
                    assertThat(little(raw).getInt(8)).isZero();
                    assertThat(little(raw).getInt(12)).isEqualTo(-1);
                    assertThat(little(raw).getInt(16)).isZero();
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
        assertThat(leagueBody).isTrue();
        assertThat(total).isEqualTo(26839);
    }

    @Test
    void nationalTransferSectionContainsTwoEmptyVectorsForEveryCapturedContainer() throws Exception {
        var report = read("national_container_transfer_section_20261022.json");
        var capture = read("national_container_transfer_inventory_v2_live_20261022.json")
                .get("returnValues").get(0);
        byte[] wire = hex(report.get("wireHex").asString());
        assertThat(wire).hasSize(502 * 2 * 4).containsOnly((byte) 0);
        assertThat(capture.get("type").get("name").asString()).isEqualTo(".?AVNATIONAL_TEAM_CONTAINER@db@@");
        assertThat(capture.get("rows").size()).isEqualTo(502);
        for (int i = 0; i < 502; i++) {
            var row = capture.get("rows").get(i);
            byte[] identity = hex(row.get("prefix").asString());
            assertThat(little(identity).getInt(8)).isEqualTo(i);
            assertThat(report.get("rows").get(i).get("uid").asLong())
                    .isEqualTo(Integer.toUnsignedLong(little(identity).getInt(12)));
            for (int j = 0; j < 2; j++) {
                byte[] header = hex(row.get("vectors").get(j).get("headerRaw").asString());
                assertThat(little(header).getLong()).isEqualTo(little(header).getLong(8));
                assertThat(little(wire).getInt((i * 2 + j) * 4)).isZero();
            }
        }
        assertThat(report.get("end").asInt()).isEqualTo(3033236);
    }

    @Test
    void derivedTransferManagerPrefixKeepsBaseAdjustmentAndAllSixtyArrayBuckets() throws Exception {
        var report = read("national_section_and_derived_manager_prefix_20261022.json").get("derivedManagerPrefix");
        var live = read("transfer_manager_derived_prefix_live_20261022.json").get("returnValues").get(0);
        byte[] scalar = hex(live.get("scalarRaw").asString());
        byte[] wire = hex(report.get("wireHex").asString());
        assertThat(Long.parseUnsignedLong(live.get("base").asString(), 16)
                - Long.parseUnsignedLong(live.get("primary").asString(), 16)).isEqualTo(0x18);
        int[] offsets = {4, 12, 16, 8, 0};
        for (int i = 0; i < 5; i++)
            assertThat(little(wire).getInt(i * 4)).isEqualTo(little(scalar).getInt(offsets[i]));
        byte[] headers = hex(live.get("headersRaw").asString());
        int cursor = 20, values = 0;
        for (int i = 0; i < 60; i++) {
            int count = little(wire).getInt(cursor); cursor += 4;
            assertThat(count).isEqualTo(live.get("vectors").get(i).get("count").asInt());
            assertThat(little(headers).getLong(i * 24 + 8) - little(headers).getLong(i * 24)).isEqualTo(count * 4L);
            byte[] nativeValues = hex(live.get("vectors").get(i).get("raw").asString());
            assertThat(Arrays.copyOfRange(wire, cursor, cursor + count * 4)).containsExactly(nativeValues);
            cursor += count * 4; values += count;
        }
        assertThat(values).isEqualTo(118);
        assertThat(cursor).isEqualTo(wire.length).isEqualTo(732);
        assertThat(report.get("end").asInt()).isEqualTo(3033968);
    }

    @Test
    void manager6A0ReusesFourteenExistingOffersRatherThanDecodingNewObjects() throws Exception {
        var report = read("derived_manager_reference_lists_20261022.json").get("managerReferences");
        var capture = read("transfer_manager_6a0_references_live_20261022.json").get("returnValues").get(0);
        byte[] wire = hex(report.get("wireHex").asString());
        assertThat(wire).hasSize(60);
        assertThat(little(wire).getInt()).isEqualTo(14);
        var addresses = new java.util.HashSet<String>();
        for (int i = 0; i < 14; i++) {
            int index = little(wire).getInt(4 + i * 4);
            var actual = capture.get("rows").get(i);
            assertThat(actual.get("sourceSlots").get(0).asInt()).isEqualTo(index);
            assertThat(index).isBetween(0, 12120);
            byte[] core = hex(actual.get("identity").get("coreRaw").asString());
            assertThat(little(core).getInt(0x5C)).isEqualTo(index);
            var person = actual.get("identity").get("person");
            assertThat(little(core).getLong(0x38)).isEqualTo(Long.parseUnsignedLong(person.get("address").asString(), 16));
            byte[] identity = hex(person.get("prefix").asString());
            assertThat(little(identity).getInt(8)).isEqualTo(person.get("index").asInt());
            assertThat(Integer.toUnsignedLong(little(identity).getInt(12))).isEqualTo(person.get("uid").asLong());
            addresses.add(actual.get("address").asString());
        }
        assertThat(addresses).hasSize(14);
        assertThat(report.get("nulls").asInt()).isZero();
        assertThat(report.get("end").asInt()).isEqualTo(3034028);
    }

    @Test
    void manager58RecordsCachePersonIndexesSeparatelyFromTeamPointers() throws Exception {
        var report = read("derived_manager_first58_records_20261022.json").get("first58Records");
        var capture = read("transfer_manager_58_first_records_live_20261022.json").get("returnValues").get(0);
        assertThat(report.get("count").asInt()).isEqualTo(50792);
        assertThat(report.get("rows").size()).isEqualTo(3);
        long[] expectedUids = {2002109875L, 2002109569L, 2000465323L};
        for (int i = 0; i < 3; i++) {
            var row = report.get("rows").get(i);
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(capture.get("rows").get(i).get("raw").asString());
            assertThat(wire).hasSize(73);
            assertThat(raw).hasSize(104);
            assertThat(wire[0]).isEqualTo((byte) 7);
            assertThat(little(wire).getInt(1)).isEqualTo(little(raw).getInt(0x2C));
            var person = capture.get("rows").get(i).get("personIndexes").get(0);
            byte[] prefix = hex(person.get("prefix").asString());
            assertThat(little(prefix).getInt(8)).isEqualTo(little(raw).getInt(0x2C));
            assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(expectedUids[i]);
            assertThat(little(raw).getLong(0x18)).isZero();
            assertThat(little(raw).getLong(0x20)).isZero();
            for (var field : row.get("fields")) {
                int offset = Integer.parseInt(field.get("offsetHex").asString(), 16);
                int wireOffset = field.get("wireOffset").asInt() - row.get("start").asInt();
                int width = field.get("width").asInt();
                assertThat(Arrays.copyOfRange(wire, wireOffset, wireOffset + width))
                        .containsExactly(Arrays.copyOfRange(raw, offset, offset + width));
            }
        }
        assertThat(report.get("end").asInt()).isEqualTo(3034259);
    }

    @Test
    void manager58OptionalPayloadRetainsPeopleWhenOuterPersonIndexIsNull() throws Exception {
        var report = read("manager58_with_optional18_wire_walk_20261022.json");
        var objects = report.get("runtimeOptional18Objects");
        assertThat(objects.size()).isEqualTo(2);
        for (var object : objects) {
            assertThat(object.get("ownerPersonIndex").asInt()).isEqualTo(-1);
            byte[] wire = hex(object.get("payload").get("wireHex").asString());
            byte[] raw = hex(object.get("nativeRawHex").asString());
            assertThat(raw).hasSize(256);
            var stream = little(wire);
            assertThat(stream.get()).isEqualTo((byte) 5);
            int[] vectorOffsets = {0, 0x18, 0x30, 0x48, 0x60, 0x78, 0x90, 0xA8};
            for (int offset : vectorOffsets) {
                int count = stream.getInt();
                assertThat(little(raw).getLong(offset + 8) - little(raw).getLong(offset)).isEqualTo(count * 8L);
                if (offset == 0 || offset == 0x60) {
                    assertThat(count).isEqualTo(1);
                    var actual = object.get("personVectors").get(offset == 0 ? 0 : 2).get("elements").get(0);
                    byte[] prefix = hex(actual.get("prefix").asString());
                    assertThat(stream.getInt()).isEqualTo(little(prefix).getInt(8));
                    assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(actual.get("uid").asLong());
                } else assertThat(count).isZero();
            }
            for (int offset : new int[]{0xF0, 0xF4, 0xF8})
                assertThat(stream.getInt()).isEqualTo(little(raw).getInt(offset));
            for (int i = 0; i < 2; i++) {
                assertThat(stream.getInt()).isEqualTo(1);
                int length = stream.getInt();
                byte[] bytes = new byte[length]; stream.get(bytes);
                var actual = object.get("stringRows").get(i);
                byte[] nativeText = hex(actual.get("bytesWithTerminator").asString());
                byte[] header = hex(actual.get("headerRaw").asString());
                byte[] record = hex(actual.get("recordRaw").asString());
                assertThat(length).isEqualTo(little(header).getInt(4));
                assertThat(bytes).containsExactly(Arrays.copyOf(nativeText, length));
                assertThat(nativeText[length]).isZero();
                assertThat(stream.getInt()).isEqualTo(little(record).getInt(8));
                assertThat(stream.getInt()).isEqualTo(little(record).getInt(12));
            }
            assertThat(stream.get()).isEqualTo(raw[0xFC]);
            assertThat(stream.get()).isEqualTo(raw[0xFD]);
            assertThat(stream.hasRemaining()).isFalse();
        }
    }

    @Test
    void completeManager58TraversalHasNoGapsAndItsLastRecordMatchesNativeFields() throws Exception {
        var report = read("manager58_with_optional18_wire_walk_20261022.json");
        assertThat(report.get("completedRecords").asInt()).isEqualTo(50792);
        assertThat(report.get("optionalTotal").asInt()).isEqualTo(2);
        int cursor = 3034040;
        for (int i = 0; i < 50792; i++) {
            var range = report.get("boundaries").get(i);
            assertThat(range.get(0).asInt()).isEqualTo(cursor);
            int width = i == 5065 ? 229 : i == 43443 ? 228 : 73;
            cursor += width;
            assertThat(range.get(1).asInt()).isEqualTo(cursor);
        }
        assertThat(cursor).isEqualTo(6742167);
        var last = report.get("lastRecord");
        byte[] raw = hex(read("manager58_last_record_live_20261022.json").get("returnValues").get(0)
                .get("rows").get(0).get("raw").asString());
        for (var field : last.get("fields")) {
            int offset = Integer.parseInt(field.get("offsetHex").asString(), 16);
            int width = field.get("width").asInt();
            assertThat(hex(field.get("raw").asString())).containsExactly(Arrays.copyOfRange(raw, offset, offset + width));
        }
        assertThat(report.get("followingCollection").get("countOffset").asInt()).isEqualTo(cursor);
        assertThat(report.get("followingCollection").get("count").asInt()).isEqualTo(1);
        assertThat(report.get("followingCollection").get("payloadDecoded").asBoolean()).isFalse();
    }

    @Test
    void manager6E8UsesThreeWordsBeforeItsPersonAndClubReferences() throws Exception {
        var report = read("manager6e8_700_filtered760_20261022.json").get("list6e8");
        var live = read("manager6e8_record_live_20261022.json").get("returnValues").get(0);
        byte[] wire = hex(report.get("wireHex").asString());
        byte[] raw = hex(live.get("raw").asString());
        assertThat(wire).hasSize(15);
        assertThat(raw).hasSize(24);
        for (int i = 0; i < 3; i++)
            assertThat(little(wire).getShort(i * 2)).isEqualTo(little(raw).getShort(0x10 + i * 2));
        byte[] person = hex(live.get("person").get("prefix").asString());
        byte[] club = hex(live.get("destination").get("prefix").asString());
        assertThat(little(wire).getInt(6)).isEqualTo(little(person).getInt(8)).isEqualTo(34320);
        // A one-byte read at native +14 shifts the reference and yields a plausible wrong index.
        assertThat(little(wire).getInt(5)).isEqualTo(8785927).isNotEqualTo(little(person).getInt(8));
        assertThat(wire[10]).isZero();
        assertThat(little(wire).getInt(11)).isEqualTo(little(club).getInt(8)).isEqualTo(400);
        assertThat(little(raw).getLong(0)).isEqualTo(Long.parseUnsignedLong(live.get("person").get("address").asString(), 16));
        assertThat(little(raw).getLong(8)).isEqualTo(Long.parseUnsignedLong(live.get("destination").get("address").asString(), 16));
        assertThat(Integer.toUnsignedLong(little(person).getInt(12))).isEqualTo(2000124134L);
        assertThat(Integer.toUnsignedLong(little(club).getInt(12))).isEqualTo(710);
    }

    @Test
    void manager700NationRowsAndFiltered760ReuseTheSamePointersInOrder() throws Exception {
        var report = read("manager6e8_700_filtered760_20261022.json");
        var capture = read("manager700_filtered760_live_20261022.json").get("returnValues").get(0);
        var rows = report.get("list700").get("rows");
        assertThat(rows.size()).isEqualTo(1081);
        var addresses = new java.util.HashSet<String>();
        int cursor = 6742190, filtered = 0;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var actual = capture.get("rows").get(i);
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(actual.get("raw").asString());
            assertThat(row.get("start").asInt()).isEqualTo(cursor);
            cursor += 18;
            assertThat(row.get("end").asInt()).isEqualTo(cursor);
            assertThat(wire).hasSize(18);
            assertThat(raw).hasSize(24);
            assertThat(wire[0]).isEqualTo((byte) 2);
            assertThat(little(wire).getInt(1)).isEqualTo(little(raw).getInt(8));
            for (int j = 0; j < 3; j++)
                assertThat(little(wire).getShort(5 + j * 2)).isEqualTo(little(raw).getShort(12 + j * 2));
            var nation = actual.get("reference0");
            byte[] prefix = hex(nation.get("prefix").asString());
            assertThat(little(wire).getShort(11)).isEqualTo((short) little(prefix).getInt(8));
            assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(nation.get("uid").asLong());
            assertThat(nation.get("typeName").asString()).isEqualTo(".?AVNATION@db@@");
            assertThat(little(raw).getLong()).isEqualTo(Long.parseUnsignedLong(nation.get("address").asString(), 16));
            assertThat(little(wire).getInt(13)).isEqualTo(little(raw).getInt(0x12));
            assertThat(wire[17]).isEqualTo(raw[0x16]);
            boolean included = little(raw).getShort(0x14) != 1900 || (little(raw).getShort(0x12) & 0x1FF) != 1;
            if (included) {
                var secondary = capture.get("filtered").get("rows").get(filtered++);
                assertThat(secondary.get("primarySlot").asInt()).isEqualTo(i);
                assertThat(secondary.get("address").asString()).isEqualTo(actual.get("address").asString());
            }
            addresses.add(actual.get("address").asString());
        }
        assertThat(addresses).hasSize(1081);
        assertThat(filtered).isEqualTo(64).isEqualTo(capture.get("filtered").get("count").asInt());
        assertThat(cursor).isEqualTo(6761648);
        assertThat(report.get("nextCollection").get("count").asInt()).isEqualTo(1220);
    }

    @Test
    void managerB8DecodesAllClubPairsWithoutTreatingDescriptorArgumentAsReferenceType() throws Exception {
        var report = read("managerb8_wire_native_20261022.json");
        var live = read("managerb8_records_live_20261022.json").get("returnValues").get(0);
        assertThat(report.get("rows").size()).isEqualTo(1220);
        int cursor = 6761652;
        for (int i = 0; i < 1220; i++) {
            var row = report.get("rows").get(i);
            var actual = live.get("rows").get(i);
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(actual.get("raw").asString());
            assertThat(row.get("start").asInt()).isEqualTo(cursor);
            cursor += 27;
            assertThat(row.get("end").asInt()).isEqualTo(cursor);
            assertThat(wire).hasSize(27);
            assertThat(raw).hasSize(40);
            assertThat(wire[0]).isEqualTo((byte) 3);
            assertThat(little(wire).getInt(1)).isEqualTo(little(raw).getInt(0x18));
            for (int j = 0; j < 2; j++) {
                var club = actual.get(j == 0 ? "club0" : "club8");
                int index = little(wire).getInt(5 + j * 4);
                assertThat(index).isEqualTo(club.get("index").asInt());
                assertThat(little(raw).getLong(j * 8)).isEqualTo(Long.parseUnsignedLong(club.get("address").asString(), 16));
                if (index >= 0) {
                    byte[] prefix = hex(club.get("prefix").asString());
                    assertThat(little(prefix).getInt(8)).isEqualTo(index);
                    assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(club.get("uid").asLong());
                    assertThat(club.get("vtableRva").asString()).isIn("44BA518", "4740AB8");
                }
            }
            assertThat(little(wire).getInt(13)).isEqualTo(little(raw).getInt(0x10));
            assertThat(wire[17]).isEqualTo(raw[0x21]);
            assertThat(little(wire).getInt(18)).isEqualTo(little(raw).getInt(0x1C));
            assertThat(wire[22]).isEqualTo(raw[0x20]);
            assertThat(little(wire).getInt(23)).isEqualTo(little(raw).getInt(0x14));
        }
        assertThat(cursor).isEqualTo(6794592);
        assertThat(report.get("nextUndecodedOffset").asInt()).isEqualTo(cursor);
    }

    @Test
    void managerD0CachesPersonIndexInsteadOfPointerAndKeepsBothClubLinks() throws Exception {
        var report = read("managerd0_wire_native_20261022.json");
        var live = read("managerd0_records_live_20261022.json").get("returnValues").get(0);
        assertThat(report.get("managerVersion").asInt()).isEqualTo(35);
        assertThat(report.get("discardedSection").get("count").asInt()).isZero();
        assertThat(report.get("rows").size()).isEqualTo(55);
        int cursor = 6794600;
        for (int i = 0; i < 55; i++) {
            var row = report.get("rows").get(i);
            var actual = live.get("rows").get(i);
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(actual.get("raw").asString());
            assertThat(row.get("start").asInt()).isEqualTo(cursor);
            cursor += 21;
            assertThat(row.get("end").asInt()).isEqualTo(cursor);
            assertThat(wire).hasSize(21);
            assertThat(raw).hasSize(32);
            assertThat(wire[0]).isEqualTo((byte) 1);
            int personIndex = little(wire).getInt(1);
            assertThat(personIndex).isEqualTo(little(raw).getInt(0x10));
            assertThat(personIndex).isEqualTo(actual.get("person").get("index").asInt());
            if (personIndex >= 0) {
                byte[] prefix = hex(actual.get("person").get("prefix").asString());
                assertThat(little(prefix).getInt(8)).isEqualTo(personIndex);
                assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(actual.get("person").get("uid").asLong());
            }
            for (int j = 0; j < 2; j++) {
                var club = actual.get(j == 0 ? "club0" : "club8");
                assertThat(little(wire).getInt(5 + j * 4)).isEqualTo(club.get("index").asInt());
                assertThat(little(raw).getLong(j * 8)).isEqualTo(Long.parseUnsignedLong(club.get("address").asString(), 16));
            }
            assertThat(little(wire).getInt(13)).isEqualTo(little(raw).getInt(0x14));
            assertThat(little(wire).getInt(17)).isEqualTo(little(raw).getInt(0x18));
        }
        assertThat(cursor).isEqualTo(6795755);
        assertThat(report.get("nextCollection").get("countWidth").asInt()).isEqualTo(2);
    }

    @Test
    void manager748TeamMarkerAnd718PersonListPreserveEntityIdentityAndOrder() throws Exception {
        var report = read("manager748_and718_wire_native_20261022.json");
        var live = read("manager748_and718_live_20261022.json").get("returnValues").get(0);
        assertThat(report.get("list748").get("count").asInt()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            var actual = live.get("list748").get("rows").get(i);
            byte[] wire = hex(report.get("list748").get("rows").get(i).get("wireHex").asString());
            byte[] raw = hex(actual.get("raw").asString());
            assertThat(wire).hasSize(16);
            assertThat(raw).hasSize(24);
            assertThat(wire[1]).isZero(); // TEAM reference has its own marker before the I32 index.
            assertThat(little(wire).getInt(2)).isEqualTo(actual.get("team").get("index").asInt());
            assertThat(little(raw).getLong(8)).isEqualTo(Long.parseUnsignedLong(actual.get("team").get("address").asString(), 16));
            assertThat(wire[6]).isEqualTo(raw[0x15]);
            assertThat(wire[7]).isEqualTo(raw[0x14]);
            assertThat(little(wire).getInt(8)).isEqualTo(actual.get("person").get("index").asInt());
            assertThat(little(raw).getLong()).isEqualTo(Long.parseUnsignedLong(actual.get("person").get("address").asString(), 16));
            assertThat(little(wire).getInt(12)).isEqualTo(little(raw).getInt(0x10));
        }
        byte[] wire = hex(report.get("list718").get("wireHex").asString());
        assertThat(wire).hasSize(4 + 62 * 4);
        assertThat(little(wire).getInt()).isEqualTo(62);
        for (int i = 0; i < 62; i++) {
            var person = live.get("list718").get("rows").get(i).get("person");
            int index = little(wire).getInt(4 + i * 4);
            assertThat(index).isEqualTo(person.get("index").asInt());
            if (index >= 0) {
                byte[] prefix = hex(person.get("prefix").asString());
                assertThat(little(prefix).getInt(8)).isEqualTo(index);
                assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(person.get("uid").asLong());
            }
        }
        assertThat(report.get("nextUndecodedOffset").asInt()).isEqualTo(6796057);
    }

    @Test
    void manager70StorageIndexAndOpaqueBlobLengthReachTheExactMemberBoundary() throws Exception {
        var report = read("manager70_storage_prefix_20261022.json");
        var live = read("manager70_prefix_live_20261022.json").get("returnValues").get(0);
        assertThat(live.get("type").get("typeName").asString())
                .isEqualTo(".?AV?$OBJECT_STORAGE@VPAST_TRANSFER_YEAR@@G@db@@");
        assertThat(report.get("count").asInt()).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            byte[] wire = hex(report.get("entries").get(i).get("wireHex").asString());
            byte[] raw = hex(live.get("rows").get(i).get("raw").asString());
            assertThat(wire).hasSize(10);
            assertThat(raw).hasSize(24);
            assertThat(little(wire).getShort()).isEqualTo(little(raw).getShort(0x10));
            assertThat(little(wire).getInt(2)).isEqualTo(little(raw).getInt(8));
            assertThat(little(wire).getInt(6)).isEqualTo(little(raw).getInt(12));
        }
        byte[] scalarWire = hex(report.get("scalarWireHex").asString());
        byte[] core = hex(live.get("raw").asString());
        assertThat(scalarWire).hasSize(13);
        assertThat(little(scalarWire).getInt()).isEqualTo(little(core).getInt(8));
        assertThat(little(scalarWire).getInt(4)).isEqualTo(little(core).getInt(12));
        assertThat(scalarWire[8]).isEqualTo(core[0x16]);
        int length = little(scalarWire).getInt(9);
        assertThat(length).isEqualTo(little(core).getInt(0x10)).isEqualTo(2220537);
        int start = report.get("blob").get("start").asInt();
        assertThat(start + length).isEqualTo(9016712);
        assertThat(start + length + 1).isEqualTo(report.get("memberLength").asInt()).isEqualTo(9016713);
        assertThat(report.get("remainingMemberBytes").asInt()).isZero();
        assertThat(report.get("blob").get("payloadDecoded").asBoolean()).isFalse();
        assertThat(report.get("blob").get("runtimeBytesCompared").asBoolean()).isFalse();
    }

    @Test
    void yearStorageSegmentsAreFileBackedAndTheirEntriesPartitionTheCompressedBlob() throws Exception {
        var report = read("stored_year_slices_20261022.json");
        var live = read("manager70_segment_backing_interfaces_live_20261022.json").get("returnValues").get(0);
        var streams = live.get("streams");
        assertThat(streams.get(0).get("type").get("typeName").asString()).isEqualTo(".?AVSEGMENT_INPUT_STREAM@sicore@@");
        assertThat(streams.get(1).get("type").get("typeName").asString()).isEqualTo(".?AVSEGMENT_OUTPUT_STREAM@sicore@@");
        assertThat(streams.get(0).get("underlying").get("type").get("typeName").asString()).contains("FILE_INPUT_STREAM_TEMPLATE", "FILE_POINTER_LIBC");
        assertThat(streams.get(1).get("underlying").get("type").get("typeName").asString()).contains("FILE_OUTPUT_STREAM_TEMPLATE", "FILE_POINTER_LIBC");
        byte[] input = hex(streams.get(0).get("prefix").asString());
        byte[] output = hex(streams.get(1).get("prefix").asString());
        assertThat(little(input).getLong(0x20)).isEqualTo(8);
        assertThat(little(input).getLong(0x28)).isEqualTo(-1);
        assertThat(little(output).getLong(0x20)).isEqualTo(8);
        assertThat(little(output).getLong(0x28)).isEqualTo(2220537);
        int cursor = 0;
        for (var row : report.get("rows")) {
            assertThat(row.get("offset").asInt()).isEqualTo(cursor);
            int length = row.get("length").asInt();
            assertThat(length).isPositive();
            cursor += length;
            byte[] prefix = hex(row.get("prefixHex").asString());
            assertThat(little(prefix).getInt()).isEqualTo(0xFD2FB528);
        }
        assertThat(cursor).isEqualTo(2220537);
        assertThat(report.get("mode").asInt()).isEqualTo(3);
        assertThat(report.get("runtimeFileBytesCompared").asBoolean()).isFalse();
    }

    @Test
    void historicalTransferWireTraversalAccountsForEveryRecordAndOptionalPayload() throws Exception {
        var report = read("stored_year_records_traversal_20261022.json");
        var frames = read("stored_year_frame_prefixes_20261022.json");
        assertThat(report.get("years").size()).isEqualTo(10);
        int total = 0, optionalTotal = 0;
        for (int i = 0; i < 10; i++) {
            var year = report.get("years").get(i);
            var frame = frames.get("rows").get(i);
            byte[] prefix = hex(frame.get("prefixHex").asString());
            // The leading U16 is a format header, not the year key (a rejected diagnostic assumption).
            assertThat(Short.toUnsignedInt(little(prefix).getShort())).isEqualTo(frame.get("headerWord").asInt()).isNotEqualTo(frame.get("year").asInt());
            assertThat(prefix[2]).isEqualTo((byte) 1);
            int count = little(prefix).getInt(3);
            assertThat(count).isEqualTo(year.get("declaredRecords").asInt()).isEqualTo(year.get("completedRecords").asInt());
            assertThat(year.get("stop").isNull()).isTrue();
            int accountedRecords = 0, accountedBytes = 7;
            var widths = year.get("recordWidths").properties().iterator();
            while (widths.hasNext()) {
                var entry = widths.next();
                int width = Integer.parseInt(entry.getKey()), n = entry.getValue().asInt();
                accountedRecords += n;
                accountedBytes += width * n;
            }
            assertThat(accountedRecords).isEqualTo(count);
            assertThat(accountedBytes).isEqualTo(year.get("rawLength").asInt()).isEqualTo(year.get("end").asInt());
            var first = year.get("samples").get(0);
            byte[] record = hex(first.get("wireHex").asString());
            assertThat(first.get("start").asInt()).isEqualTo(7);
            assertThat(record[0]).isEqualTo((byte) 7);
            assertThat(little(record).getInt(1)).isEqualTo(first.get("references").get(0).get("index").asInt());
            total += count;
            optionalTotal += year.get("optional18Count").asInt();
        }
        assertThat(total).isEqualTo(176753);
        assertThat(optionalTotal).isEqualTo(5);
        assertThat(report.get("allYearsReachedExactEnd").asBoolean()).isTrue();
        assertThat(report.get("recordsRuntimeCompared").asBoolean()).isFalse();
    }

    @Test
    void historicalReferenceSelectionPreservesNullsDuplicatesAndMixedPersonTypes() throws Exception {
        var plan = read("historical_reference_join_plan_20261022.json");
        var live = read("historical_reference_joins_live_20261022.json").get("returnValues").get(0);
        var report = read("historical_reference_joins_verification_20261022.json");
        var targets = new java.util.HashMap<String, JsonNode>();
        for (var row : live.get("rows")) {
            byte[] prefix = hex(row.get("prefix").asString());
            assertThat(prefix).hasSize(16);
            assertThat(little(prefix).getInt(8)).isEqualTo(row.get("index").asInt());
            assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(row.get("uid").asLong());
            assertThat(row.get("status").asString()).isEqualTo("MATCHED_CURRENT_REGISTRY");
            targets.put(row.get("registryRva").asString() + ":" + row.get("index").asInt(), row);
        }
        assertThat(targets).hasSize(53);
        int nulls = 0, matched = 0, competitionNonNull = 0;
        for (int i = 0; i < plan.get("requests").size(); i++) {
            var request = plan.get("requests").get(i);
            var result = report.get("rows").get(i);
            int index = request.get("index").asInt();
            assertThat(result.get("index").asInt()).isEqualTo(index);
            assertThat(result.get("year").asInt()).isEqualTo(request.get("year").asInt());
            if (index == -1) {
                nulls++;
                assertThat(result.get("status").asString()).isEqualTo("NULL_REFERENCE");
            } else {
                matched++;
                var actual = targets.get(request.get("registryRva").asString() + ":" + index);
                assertThat(actual).isNotNull();
                assertThat(result.get("uid").asLong()).isEqualTo(actual.get("uid").asLong());
                if (request.get("kind").asString().equals("COMP")) competitionNonNull++;
            }
        }
        assertThat(nulls).isEqualTo(51);
        assertThat(matched).isEqualTo(65);
        assertThat(competitionNonNull).isZero(); // This selection provides no nonnull COMP identity evidence.
        assertThat(report.get("typeCountsForUniqueTargets").get(".?AVRETIRED_PERSON_NAME_POINTERS@db@@").asInt()).isEqualTo(6);
        assertThat(report.get("typeCountsForUniqueTargets").get(".?AVACTUAL_NON_PLAYER@db@@").asInt()).isEqualTo(13);
    }

    @Test
    void modernContractAddOnLabelsDistinguishReleaseClausesFromAppearanceFees() throws Exception {
        var report = read("modern_contract_bonus_labels_20261022.json");
        assertThat(report.get("uniqueCodes").asInt()).isEqualTo(45);
        assertThat(report.get("labelCases").size()).isEqualTo(46);
        var labels = new java.util.HashMap<Integer, java.util.List<String>>();
        for (var row : report.get("labelCases")) {
            int code = row.get("code").asInt();
            for (var label : row.get("labels")) {
                assertThat(label.get("id").asInt()).isEqualTo(row.get("localizationId").asInt());
                for (var variant : label.get("variants"))
                    labels.computeIfAbsent(code, ignored -> new java.util.ArrayList<>()).add(variant.get("text").asString());
            }
        }
        assertThat(labels.get(0)).containsExactly("Minimum Fee Release Clause");
        assertThat(labels.get(32)).containsExactly("Appearance Fee");
        assertThat(labels.get(33)).containsExactly("Goal Bonus");
        assertThat(labels.get(38)).containsExactly("Unused Substitute Fee");
        assertThat(labels.get(27)).hasSize(2).anyMatch(s -> s.contains("[%comp#1-short]"))
                .anyMatch(s -> s.contains("Major Continental Competition"));
        assertThat(labels).doesNotContainKey(42);
    }

    @Test
    void typedFullContractAddOnRowsDecodeCodeByteSixAndRawValueWordZero() throws Exception {
        var live = read("full_contract_addon_rows_live_20261022.json").get("returnValues").get(0);
        var report = read("full_contract_addon_bridge_verification_20261022.json");
        assertThat(live.get("rows").size()).isEqualTo(3);
        int total = 0, selected = 0, shiftedSelected = 0;
        for (int i = 0; i < 3; i++) {
            var actual = live.get("rows").get(i);
            var player = report.get("players").get(i);
            byte[] core = hex(actual.get("raw").asString());
            byte[] rows = hex(actual.get("rowsRaw").asString());
            assertThat(core).hasSize(200);
            assertThat(little(core).getLong(8)).isEqualTo(Long.parseUnsignedLong(actual.get("person").asString(), 16));
            assertThat(little(core).getLong(0x70) - little(core).getLong(0x68)).isEqualTo(rows.length);
            assertThat(rows.length).isEqualTo(actual.get("count").asInt() * 8);
            for (int j = 0; j < actual.get("count").asInt(); j++) {
                var row = player.get("rows").get(j);
                int code = Byte.toUnsignedInt(rows[j * 8 + 6]);
                assertThat(code).isEqualTo(row.get("code").asInt());
                assertThat(Integer.toUnsignedLong(little(rows).getInt(j * 8))).isEqualTo(row.get("rawAmountU32").asLong());
                assertThat(little(rows).getShort(j * 8 + 4)).isEqualTo((short) row.get("conditionS16").asInt());
                assertThat(Byte.toUnsignedInt(rows[j * 8 + 7])).isEqualTo(row.get("flags7").asInt());
                if ((code & 0xF8) == 0x20) selected++;
                if ((Byte.toUnsignedInt(rows[j * 8 + 7]) & 0xF8) == 0x20) shiftedSelected++;
                total++;
            }
        }
        assertThat(total).isEqualTo(11);
        assertThat(selected).isEqualTo(9);
        assertThat(shiftedSelected).isNotEqualTo(selected); // One-byte code drift must fail these fixtures.
    }

    @Test
    void cblaFilterMustNotDiscardSierdsMilestoneRowsFromRawDatabaseExtraction() throws Exception {
        var report = read("full_contract_addon_bridge_verification_20261022.json");
        var sierd = report.get("players").get(1);
        assertThat(sierd.get("uid").asLong()).isEqualTo(2002079297L);
        assertThat(sierd.get("rows").size()).isEqualTo(5);
        assertThat(sierd.get("cblaCodeSelectedCount").asInt()).isEqualTo(3);
        for (int i = 3; i < 5; i++) {
            var row = sierd.get("rows").get(i);
            assertThat(row.get("code").asInt()).isEqualTo(10);
            assertThat(row.get("conditionS16").asInt()).isEqualTo(i == 3 ? 10 : 15);
            assertThat(row.get("label").get(0).asString()).isEqualTo("Seasonal Landmark Goal Bonus");
            assertThat(row.get("passesCblaCodeFilter").asBoolean()).isFalse();
        }
        assertThat(report.get("nativeProducer").get("hasEarlierConditionalVisibilityGate").asBoolean()).isTrue();
    }

    @Test
    void clauseConditionWordCarriesGoalCountsAndExtensionYearsRatherThanUniversalMoney() throws Exception {
        var report = read("full_contract_addon_bridge_verification_20261022.json");
        var sierd = report.get("players").get(1);
        for (int i = 3; i < 5; i++) {
            var row = sierd.get("rows").get(i);
            assertThat(row.get("passesStaticCclaCodeFilter").asBoolean()).isTrue();
            assertThat(row.get("clauseFieldsBeforeVisibilityAndContext").get("Ccty").asInt()).isEqualTo(10);
            assertThat(row.get("clauseFieldsBeforeVisibilityAndContext").get("Ccnv").asInt()).isEqualTo(i == 3 ? 10 : 15);
        }
        var templates = read("modern_contract_addon_formats_20261022.json").get("formatTemplates");
        assertThat(templates.get(0).get("labels").get(0).get("variants").get(0).get("text").asString())
                .isEqualTo("[%cash#1-short-roundsmall] after [%number#2] league goals in season");
        assertThat(templates.get(2).get("labels").get(0).get("variants").get(0).get("text").asString()).isEqualTo("One year");
        assertThat(templates.get(3).get("labels").get(0).get("variants").get(0).get("text").asString()).isEqualTo("[%number#2] years");
        // Separate historical combined-player fixture: optional extension stores -1 value and one year.
        var captured = read("combined_contract_rows_20261019.json").get("returnValues").get(0);
        byte[] bytes = hex(captured.get("bytes").asString().replaceAll("\\s", ""));
        assertThat(bytes).hasSize(40);
        assertThat(little(bytes).getInt(32)).isEqualTo(-1);
        assertThat(little(bytes).getShort(36)).isEqualTo((short) 1);
        assertThat(Byte.toUnsignedInt(bytes[38])).isEqualTo(22);
        assertThat(report.get("clauseProducer").get("computedDateProperty").asString()).isEqualTo("Ccxd");
    }

    @Test
    void additionalClauseFixturesKeepPercentagesYearsAndMoneyInTheirCorrectFields() throws Exception {
        var report = read("additional_full_contract_clauses_verification_20261022.json");
        var live = read("additional_full_contract_clause_samples_signed16_live_20261022.json").get("returnValues").get(0);
        assertThat(report.get("examinedSlots").asInt()).isEqualTo(4096);
        assertThat(report.get("selectedContracts").asInt()).isEqualTo(9);
        assertThat(report.get("selectedRows").asInt()).isEqualTo(54);
        assertThat(report.get("missingRequestedCodes").size()).isEqualTo(1);
        assertThat(report.get("missingRequestedCodes").get(0).asInt()).isEqualTo(13);
        boolean sellOn = false, wageRise = false, extension = false, assistLandmark = false;
        for (int i = 0; i < 9; i++) {
            var owner = report.get("selected").get(i);
            byte[] core = hex(live.get("selected").get(i).get("raw").asString());
            byte[] rows = hex(live.get("selected").get(i).get("rowsRaw").asString());
            assertThat(little(core).getLong(0x70) - little(core).getLong(0x68)).isEqualTo(rows.length);
            for (var row : owner.get("fields")) {
                int offset = row.get("slot").asInt() * 8;
                assertThat(Byte.toUnsignedInt(rows[offset + 6])).isEqualTo(row.get("code").asInt());
                assertThat(little(rows).getInt(offset)).isEqualTo(row.get("rawValueS32").asInt());
                assertThat(little(rows).getShort(offset + 4)).isEqualTo((short) row.get("rawConditionS16").asInt());
                if (owner.get("uid").asLong() == 12092871L && row.get("code").asInt() == 8) {
                    assertThat(row.get("rawValueS32").asInt()).isEqualTo(-1);
                    assertThat(row.get("interpretedValue").get("percentage").asInt()).isEqualTo(15);
                    sellOn = true;
                }
                if (owner.get("uid").asLong() == 222292L && row.get("code").asInt() == 14) {
                    assertThat(row.get("rawValueS32").asInt()).isEqualTo(-1);
                    assertThat(row.get("interpretedValue").get("percentage").asInt()).isEqualTo(25);
                    wageRise = true;
                }
                if (owner.get("uid").asLong() == 222138L && row.get("code").asInt() == 22) {
                    assertThat(row.get("rawValueS32").asInt()).isEqualTo(-1);
                    assertThat(row.get("interpretedValue").get("years").asInt()).isEqualTo(1);
                    extension = true;
                }
                if (owner.get("uid").asLong() == 11035674L && row.get("code").asInt() == 40) {
                    assertThat(row.get("rawValueS32").asInt()).isPositive();
                    assertThat(row.get("interpretedValue").get("seasonThreshold").asInt()).isIn(10, 15);
                    assistLandmark = true;
                }
            }
        }
        assertThat(sellOn && wageRise && extension && assistLandmark).isTrue();
        assertThat(report.get("nonzeroByte7Found").asBoolean()).isFalse();
    }

    @Test
    void clauseContextCodesAreNotTermKindsOrAnActiveBoolean() throws Exception {
        var report = read("clause_context_static_replay_20261022.json");
        var labels = read("modern_contract_clause_context_20261022.json");
        assertThat(report.get("nativeResolverInvoked").asBoolean()).isFalse();
        boolean relegationContext = false, excludedRow = false;
        for (var row : report.get("rows")) {
            if (!row.get("passesStaticClauseCodeFilter").asBoolean()) {
                assertThat(row.get("replayedCcci").isNull()).isTrue();
                assertThat(row.get("invokedByProducer").asBoolean()).isFalse();
                excludedRow = true;
            }
            if (row.get("uid").asLong() == 12095040L && row.get("termCode").asInt() == 1) {
                assertThat(row.get("replayedCcci").asInt()).isEqualTo(3);
                assertThat(row.get("contextLabels").get(0).asString()).contains("inactive unless", "relegated");
                relegationContext = true;
            }
        }
        assertThat(relegationContext && excludedRow).isTrue();
        assertThat(labels.get("clauseContextLabels").size()).isEqualTo(6);
        boolean percentageTemplate = false, combinedTemplate = false;
        for (var row : labels.get("formatTemplates")) {
            for (var label : row.get("labels")) {
                for (var variant : label.get("variants")) {
                    String text = variant.get("text").asString();
                    if (row.get("id").asInt() == 0x792F8) {
                        assertThat(text).isEqualTo("[%number#2]%");
                        percentageTemplate = true;
                    }
                    if (row.get("id").asInt() == 0x99D26) {
                        assertThat(text).contains("[%number#2] combined league goals and assists");
                        combinedTemplate = true;
                    }
                }
            }
        }
        assertThat(percentageTemplate && combinedTemplate).isTrue();
    }

    @Test
    void nativePlayingTimeParserKeepsDistinctCodesAndAliases() throws Exception {
        var report = read("playing_time_parser_enum_20261022.json");
        var codes = new java.util.HashMap<String, Integer>();
        for (var row : report.get("rows")) codes.put(row.get("token").asString(), row.get("code").asInt());
        assertThat(codes).hasSize(31);
        assertThat(new java.util.HashSet<>(codes.values())).hasSize(23);
        assertThat(codes.get("NONE")).isZero();
        assertThat(codes.get("STAR_PLAYER")).isEqualTo(1);
        assertThat(codes.get("IMPORTANT_PLAYER")).isEqualTo(2);
        assertThat(codes.get("REGULAR_STARTER")).isEqualTo(3);
        assertThat(codes.get("SQUAD_PLAYER")).isEqualTo(4);
        assertThat(codes.get("SQUAD_PLAYER_COMPETING_FOR_POSITION")).isEqualTo(4);
        assertThat(codes.get("IMPACT_SUBSTITUTE")).isEqualTo(5);
        assertThat(codes.get("SQUAD_PLAYER_FMT")).isEqualTo(6);
        assertThat(codes.get("FRINGE_PLAYER")).isEqualTo(7);
        assertThat(codes.get("FRINGE_PLAYER_FMT")).isEqualTo(8);
        assertThat(codes.get("FIRST_CHOICE_KEEPER")).isEqualTo(15);
        assertThat(codes.get("ALL_CUPS_KEEPER")).isEqualTo(16);
        assertThat(codes.get("CUP_KEEPER_FMT")).isEqualTo(19);
        assertThat(codes.get("SURPLUS_TO_REQUIREMENT")).isEqualTo(22);
        assertThat(codes.values()).doesNotContain(23, 24);
    }

    @Test
    void agreedPlayingTimeSamplesRemainSeparateFromCurrentAndCollapsedPresentationEnum() throws Exception {
        var comparison = read("contract_playing_time_comparison_20261020.json");
        assertThat(comparison.get("nativeRoute").get("objectOffset").asString()).isEqualTo("54");
        assertThat(comparison.get("nativeRoute").get("width").asInt()).isEqualTo(1);
        boolean currentDiffers = false;
        for (var row : comparison.get("rows")) {
            int code = row.get("agreedCandidateRaw").asInt();
            assertThat(row.get("screenshotAgreed").asString())
                    .isEqualTo(code == 3 ? "Regular Starter" : "Important Player");
            if (row.get("uid").asLong() == 2002079297L) {
                assertThat(code).isEqualTo(3);
                assertThat(row.get("screenshotCurrent").asString()).isEqualTo("Important Player");
                currentDiffers = true;
            }
        }
        assertThat(currentDiffers).isTrue();
        var templates = read("playing_time_native_templates_20261022.json");
        boolean modernRegular = false, collapsedRegular = false;
        for (var function : templates.get("functions")) {
            for (var label : function.get("labels")) {
                if (function.get("rva").asString().equals("1701820") && label.get("id").asInt() == 0x9F639) {
                    assertThat(label.get("entries").get(0).get("variants").get(0).asString()).contains("regular starter");
                    modernRegular = true;
                }
                if (function.get("rva").asString().equals("1701f70") && label.get("id").asInt() == 0x75D5E) {
                    assertThat(label.get("entries").get(0).get("variants").get(0).asString()).isEqualTo("Regular Starter");
                    collapsedRegular = true;
                }
            }
        }
        assertThat(modernRegular && collapsedRegular).isTrue();
    }

    private static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    private static JsonNode read(String name) throws Exception {
        try (var input = Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence", name))) {
            byte[] bytes = input.readNBytes(4_194_305);
            if (bytes.length > 4_194_304) throw new IllegalArgumentException("Oversized fixture");
            return JsonMapper.builder().build().readTree(bytes);
        }
    }
}
