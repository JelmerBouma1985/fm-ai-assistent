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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent retained-wire checks. Does not observe a LOAN_OFFER reader invocation. */
class Fm26CapturedLoanOfferEvidenceTest {
    @Test
    void firstLoanWireMatchesTypedRuntimeOwnerReferencesScalarsAndMeaningfulTermBytes() throws Exception {
        var report = read("transfer_offer_wire_prefix_20261021.json");
        var loan = report.get("firstLoan");
        var current = read("first_loan_offer_wire_target_live_20261021.json").get("returnValues").get(0);
        byte[] wire = hex(loan.get("wireHex").asString());
        byte[] nativeBytes = hex(current.get("raw").asString());
        assertThat(wire).hasSize(231);
        assertThat(nativeBytes).hasSize(0x128);
        var bytes = little(wire);
        assertThat(Byte.toUnsignedInt(wire[0])).isEqualTo(3);
        assertThat(Short.toUnsignedInt(bytes.getShort(1))).isEqualTo(19);
        assertThat(bytes.getInt(3)).isEqualTo(99885);
        assertThat(current.get("personUid").asLong()).isEqualTo(2002087701L);
        assertThat(bytes.getInt(21)).isEqualTo(76);
        assertThat(current.get("type").get("vtableRva").asString()).isEqualTo("45EBC88");
        for (var link : current.get("references")) {
            int field = Integer.parseInt(link.get("offsetHex").asString(), 16);
            assertThat(little(nativeBytes).getLong(field))
                    .isEqualTo(Long.parseUnsignedLong(link.get("address").asString(), 16));
            int index = switch (field) {
                case 0x40 -> bytes.getInt(8);
                case 0x48 -> bytes.getInt(13);
                case 0x50 -> bytes.getInt(69);
                case 0xE0 -> bytes.getShort(179);
                default -> throw new AssertionError("Unexpected reference");
            };
            assertThat(index).isEqualTo(link.get("index").asInt());
        }
        // Derived reader231ACB0, version19. These spans are independent of generated field rows.
        int[][] spans = {{117,0x10C,4},{121,0x110,4},{125,0x114,4},{129,0xF8,8},
                {137,0x125,1},{138,0x118,4},{142,0x90,8},{150,0x11C,4},{154,0x98,8},
                {162,0x120,2},{164,0x108,4},{168,0x122,2},{170,0x100,4},{174,0x104,4},
                {178,0x124,1},{182,0xA4,4},{186,0xA8,4},{190,0xAC,1},{191,0xA0,4},{227,0xF0,4}};
        for (int[] span : spans) {
            assertThat(Arrays.copyOfRange(wire, span[0], span[0] + span[2]))
                    .isEqualTo(Arrays.copyOfRange(nativeBytes, span[1], span[1] + span[2]));
        }
        byte[] term = hex(current.get("terms").get("vectors").get(0).get("raw").asString());
        assertThat(term).hasSize(12);
        assertThat(wire[203]).isEqualTo((byte) 2);
        assertThat(bytes.getInt(204)).isEqualTo(little(term).getInt(0));
        assertThat(wire[208]).isEqualTo(term[4]);
        assertThat(wire[209]).isEqualTo(term[8]);
        // Unserialized high bytes differ from a naive 32-bit condition interpretation.
        assertThat(little(term).getInt(4)).isNotEqualTo(Byte.toUnsignedInt(wire[208]));
        assertThat(report.get("actualLoanDecoderInvocationObserved").asBoolean()).isFalse();
    }

    @Test
    void implicitCompetitionIndexHasNoModeByteAndExtendedIndexRemainsBounded() throws Exception {
        byte[] wire = hex(read("transfer_offer_wire_prefix_20261021.json")
                .get("firstLoan").get("wireHex").asString());
        var reference = little(wire); reference.position(179);
        assertThat(index(reference)).isEqualTo(7);
        assertThat(reference.position()).isEqualTo(181);
        assertThat(reference.get()).isEqualTo((byte) 1); // Next subrecord's own version.
        var wrong = little(wire); wrong.position(180); // Erroneously consume leading mode byte.
        assertThat(index(wrong)).isNotEqualTo(7);
        var extended = little(new byte[]{(byte) 0xFE,(byte) 0xFF,0x70,0x11,0x01,0x00});
        assertThat(index(extended)).isEqualTo(70000);
        assertThat(extended.remaining()).isZero();
        assertThat(index(little(new byte[]{(byte) 0xFF,(byte) 0xFF}))).isEqualTo(-1);
        assertThatThrownBy(() -> index(little(new byte[]{(byte) 0xFE,(byte) 0xFF,1})))
                .isInstanceOf(java.nio.BufferUnderflowException.class);
    }

    private static int index(ByteBuffer bytes) {
        int value = bytes.getShort();
        return value == -2 ? bytes.getInt() : value;
    }

    @Test
    void completeFirstCollectionRetainsEveryNativeIdentityAndHole() throws Exception {
        var nativeCollection = read("transfer_offer_identity_inventory_live_20261021.json")
                .get("returnValues").get(0).get("collections").get(0);
        var prefix = read("transfer_offer_extended_prefix_20261021.json").get("completedPrefix");
        assertThat(prefix.size()).isEqualTo(12121);
        Map<Integer, JsonNode> nativeRows = new HashMap<>();
        for (var row : nativeCollection.get("rows")) {
            assertThat(nativeRows.put(row.get(0).asInt(), row)).isNull();
        }
        assertThat(nativeRows).hasSize(5111);
        int holes = 0;
        long previousEnd = 13;
        for (var record : prefix) {
            assertThat(record.get("start").asLong()).isEqualTo(previousEnd);
            previousEnd = record.get("end").asLong();
            int slot = record.get("ordinal").asInt();
            var row = nativeRows.get(slot);
            if (record.has("nullFactoryResult") && record.get("nullFactoryResult").asBoolean()) {
                holes++;
                assertThat(row).isNull();
                assertThat(record.get("tag").asInt()).isZero();
                assertThat(record.get("bytes").asInt()).isEqualTo(1);
            } else {
                assertThat(row).isNotNull();
                assertThat(record.get("registryIndex").asInt()).isEqualTo(slot).isEqualTo(row.get(5).asInt());
                assertThat(record.get("tag").asInt()).isEqualTo(row.get(1).asInt());
                assertThat(record.get("personIndex").asInt()).isEqualTo(row.get(2).asInt());
                assertThat(record.get("personUid").asLong()).isEqualTo(row.get(3).asLong());
                assertThat(record.get("field60").asLong()).isEqualTo(row.get(4).asLong());
            }
        }
        assertThat(holes).isEqualTo(7010);
        assertThat(previousEnd).isEqualTo(829092);
        assertThat(prefix.get(298).get("nullFactoryResult").asBoolean()).isTrue();
        assertThat(prefix.get(12120).get("nativeIdentityMatched").asBoolean()).isTrue();
    }

    @Test
    void nonemptyOfferAwardsRetainTypedIdentityAndAmountsWithoutWideningCondition() throws Exception {
        JsonNode offer = null;
        for (var record : read("transfer_offer_extended_prefix_20261021.json").get("subtypeRecords")) {
            if (record.get("registryIndex").asInt() == 117) offer = record;
        }
        assertThat(offer).isNotNull();
        assertThat(offer.get("personUid").asLong()).isEqualTo(2000288527L);
        assertThat(offer.get("bytes").asInt()).isEqualTo(258);
        byte[] wire = hex(offer.get("wireHex").asString());
        int[] expectedIndexes = {3825, 369, 8};
        int[] expectedAmounts = {825000, 474375, 272766};
        for (int i = 0; i < 3; i++) {
            // First row starts at absolute19348, record starts19148. Each wire row is9bytes,
            // whereas retained native award rows have16bytes and contain an8byte entity pointer.
            int offset = 200 + i * 9;
            assertThat(little(wire).getShort(offset)).isEqualTo((short) 1);
            assertThat(little(wire).getShort(offset + 2)).isEqualTo((short) expectedIndexes[i]);
            assertThat(little(wire).getInt(offset + 4)).isEqualTo(expectedAmounts[i]);
            assertThat(wire[offset + 8]).isZero();
            assertThat(offer.get("awardRows").get(i).get("index").asInt()).isEqualTo(expectedIndexes[i]);
        }
        JsonNode actual = null;
        for (var row : read("transfer_offer_subtype_award_identities_live_20261021.json")
                .get("returnValues").get(0).get("samples")) {
            if (row.get("slot").asInt() == 117) actual = row;
        }
        assertThat(actual).isNotNull();
        byte[] nativeRows = hex(actual.get("awards").get("raw").asString());
        assertThat(nativeRows).hasSize(48);
        for (int i = 0; i < 3; i++) {
            var identity = actual.get("awardIdentities").get(i);
            assertThat(identity.get("type").get("name").asString()).isEqualTo(".?AVAWARD@db@@");
            assertThat(identity.get("index").asInt()).isEqualTo(expectedIndexes[i]);
            assertThat(little(nativeRows).getLong(i * 16))
                    .isEqualTo(Long.parseUnsignedLong(identity.get("address").asString(), 16));
            assertThat(little(nativeRows).getInt(i * 16 + 8)).isEqualTo(expectedAmounts[i]);
            assertThat(nativeRows[i * 16 + 12]).isZero();
        }
    }

    @Test
    void secondOfferCollectionUsesItsOwnIndexAndKeepsMultipleOffersForOnePerson() throws Exception {
        var section = read("transfer_manager_offer_and_tail_records_20261021.json").get("secondCollection");
        var captured = read("transfer_offer_identity_inventory_live_20261021.json")
                .get("returnValues").get(0).get("collections").get(1);
        Map<Integer, JsonNode> nativeRows = new HashMap<>();
        for (var row : captured.get("rows")) nativeRows.put(row.get(0).asInt(), row);
        int occupied = 0, holes = 0;
        long end = 829096;
        for (var record : section.get("records")) {
            assertThat(record.get("start").asLong()).isEqualTo(end);
            end = record.get("end").asLong();
            int ordinal = record.get("ordinal").asInt();
            var actual = nativeRows.get(ordinal);
            if (record.has("nullFactoryResult")) {
                holes++;
                assertThat(actual).isNull();
            } else {
                occupied++;
                assertThat(actual).isNotNull();
                assertThat(record.get("registryIndex").asInt()).isEqualTo(actual.get(6).asInt());
                assertThat(record.get("firstCollectionIndex").asInt()).isEqualTo(actual.get(5).asInt());
                assertThat(record.get("personUid").asLong()).isEqualTo(actual.get(3).asLong());
                assertThat(record.get("field60").asLong()).isEqualTo(actual.get(4).asLong());
            }
        }
        assertThat(occupied).isEqualTo(21);
        assertThat(holes).isEqualTo(10);
        assertThat(end).isEqualTo(833227);
        var first = section.get("records").get(0);
        var next = section.get("records").get(1);
        assertThat(first.get("registryIndex").asInt()).isZero();
        assertThat(first.get("firstCollectionIndex").asInt()).isEqualTo(1262);
        assertThat(first.get("personUid").asLong()).isEqualTo(next.get("personUid").asLong());
        assertThat(first.get("field60").asLong()).isNotEqualTo(next.get("field60").asLong());
    }

    @Test
    void managerTailRowsExpandThirteenWireBytesIntoSixteenNativeBytes() throws Exception {
        var section = read("transfer_manager_offer_and_tail_records_20261021.json").get("tailRecords");
        assertThat(section.get("rows").size()).isEqualTo(272);
        assertThat(section.get("start").asLong()).isEqualTo(833227);
        assertThat(section.get("end").asLong()).isEqualTo(836767);
        for (var row : section.get("rows")) {
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(row.get("nativeRawHex").asString());
            assertThat(wire).hasSize(13);
            assertThat(raw).hasSize(16);
            assertThat(little(wire).getInt(1)).isEqualTo(little(raw).getInt(8));
            assertThat(little(wire).getInt(5)).isEqualTo(little(raw).getInt(12));
            assertThat(little(wire).getInt(9)).isEqualTo(row.get("personIndex").asInt());
        }
        var first = section.get("rows").get(0);
        assertThat(first.get("personUid").asLong()).isEqualTo(2002104136L);
        assertThat(first.get("value").asInt()).isEqualTo(451);
    }

    @Test
    void clubOwnedPlainContextPrefixMatchesWithoutTreatingItsFirstWordAsVtable() throws Exception {
        var report = read("transfer_manager_offer_and_tail_records_20261021.json").get("firstClubContextPrefix");
        var selected = report.get("selectedClub");
        var capture = read("first_club_transfer_member_context_live_20261021.json")
                .get("returnValues").get(0).get("rows");
        assertThat(selected.get("index").asInt()).isEqualTo(25);
        assertThat(selected.get("uid").asInt()).isEqualTo(94);
        assertThat(selected.get("localVersion").asInt()).isEqualTo(22);
        byte[] object = hex(capture.get(25).get("contextRaw").asString());
        assertThat(object).hasSize(528);
        assertThat(little(object).getInt()).isEqualTo(0x07F48248);
        for (var run : selected.get("scalarRuns")) {
            int offset = Integer.parseInt(run.get("offsetHex").asString(), 16);
            assertThat(hex(run.get("raw").asString())).isEqualTo(Arrays.copyOfRange(object, offset, offset + 4));
        }
        assertThat(selected.get("scalarRuns").size()).isEqualTo(9);
        assertThat(selected.get("subrecord").get("count").asInt()).isZero();
        for (int i = 0; i < 25; i++) assertThat(capture.get(i).get("contextAddress").asString()).isEqualTo("0");
    }

    @Test
    void clubContextPersonVectorsKeepEveryOrderedNativeReferenceAndSeparateScalarReference() throws Exception {
        var decoded = read("club_context_first_people_vectors_20261021.json").get("clubContextContinuation");
        var captured = read("club_context_people_children_links_live_20261021.json").get("returnValues").get(0);
        assertThat(decoded.get("byteCount").asInt()).isZero();
        assertThat(decoded.get("byteVectorVersion").asInt()).isEqualTo(1);
        int total = 0;
        for (int vector = 0; vector < 2; vector++) {
            var parsed = decoded.get("personVectors").get(vector);
            var nativeVector = captured.get("personVectors").get(vector);
            assertThat(parsed.get("count").asInt()).isEqualTo(vector == 0 ? 23 : 1);
            byte[] pointers = hex(nativeVector.get("pointers").asString());
            for (int i = 0; i < parsed.get("rows").size(); i++) {
                var row = parsed.get("rows").get(i);
                var nativeRow = nativeVector.get("rows").get(i);
                byte[] identity = hex(nativeRow.get("prefix").asString());
                assertThat(little(pointers).getLong(i * 8))
                        .isEqualTo(Long.parseUnsignedLong(nativeRow.get("address").asString(), 16));
                assertThat(row.get("index").asInt()).isEqualTo(little(identity).getInt(8));
                assertThat(row.get("uid").asLong()).isEqualTo(Integer.toUnsignedLong(little(identity).getInt(12)));
                total++;
            }
        }
        assertThat(total).isEqualTo(24);
        assertThat(decoded.get("person98").get("index").asInt()).isEqualTo(25710);
        assertThat(decoded.get("person98").get("uid").asLong()).isEqualTo(78092366L);
        assertThat(decoded.get("childVectorA0").get("count").asInt()).isEqualTo(11);
    }

    @Test
    void firstClubChildHasPlainVectorStorageAndOnlyItsProvenThirtyByteWirePrefix() throws Exception {
        var decoded = read("club_context_first_people_vectors_20261021.json")
                .get("clubContextContinuation").get("firstChildPrefix");
        byte[] raw = hex(decoded.get("nativeRawHex").asString());
        byte[] wire = hex(decoded.get("wireHex").asString());
        assertThat(raw).hasSize(96);
        assertThat(wire).hasSize(30);
        assertThat(little(wire).getShort()).isEqualTo((short) 11);
        assertThat(wire[2]).isEqualTo(raw[0x59]).isEqualTo((byte) 1);
        assertThat(wire[3]).isZero();
        assertThat(little(wire).getInt(4)).isEqualTo(76);
        assertThat(decoded.get("team28").get("uid").asInt()).isEqualTo(94);
        assertThat(Arrays.copyOfRange(wire, 8, 24)).isEqualTo(Arrays.copyOfRange(raw, 0x40, 0x50));
        assertThat(wire[24]).isEqualTo(raw[0x5A]);
        assertThat(little(wire).getInt(25)).isEqualTo(little(raw).getInt(0x30));
        assertThat(wire[29]).isZero();
        assertThat(little(raw).getLong(0x20)).isZero();
        long begin = little(raw).getLong(0), end = little(raw).getLong(8), capacity = little(raw).getLong(16);
        assertThat(begin).isPositive();
        assertThat(end).isGreaterThanOrEqualTo(begin);
        assertThat(capacity).isGreaterThanOrEqualTo(end);
    }

    @Test
    void twelveModernClubChildrenConsumeSixtyNineBytesEachWithoutSerializingDefaultByte5E() throws Exception {
        var report = read("club_context_complete_a0_children_20261021.json");
        var a0 = report.get("clubContextChildren");
        assertThat(a0.get("count").asInt()).isEqualTo(11);
        assertThat(a0.get("start").asInt()).isEqualTo(836961);
        assertThat(a0.get("end").asInt()).isEqualTo(837720);
        var rows = new java.util.ArrayList<JsonNode>();
        for (var row : a0.get("rows")) rows.add(row);
        for (var row : report.get("clubContextAfterA0").get("d0Rows")) rows.add(row);
        assertThat(rows).hasSize(12);
        for (var row : rows) {
            byte[] wire = hex(row.get("wireHex").asString());
            byte[] raw = hex(row.get("raw").asString());
            assertThat(wire).hasSize(69);
            assertThat(raw).hasSize(96);
            assertThat(little(wire).getShort()).isEqualTo((short) 11);
            assertThat(little(wire).getInt(31)).isEqualTo(little(raw).getInt(0x50));
            assertThat(wire[35]).isEqualTo(raw[0x5B]);
            assertThat(little(wire).getInt(37)).isEqualTo(little(raw).getInt(0x54));
            assertThat(wire[41]).isEqualTo(raw[0x5C]);
            assertThat(wire[42]).isEqualTo(raw[0x5D]);
            assertThat(Arrays.copyOfRange(wire, 43, 55)).isEqualTo(Arrays.copyOfRange(raw, 0x34, 0x40));
            assertThat(little(wire).getLong(55)).isEqualTo(little(raw).getLong(0x18));
            assertThat(wire[63]).isEqualTo(raw[0x5F]);
            assertThat(wire[64]).isEqualTo(raw[0x58]);
            assertThat(little(wire).getInt(65)).isZero();
            assertThat(raw[0x5E]).isEqualTo((byte) 0x0A); // Constructor default, no wire byte in this branch.
        }
    }

    @Test
    void clubParentResumesAfterChildrenWithEmptyVectorsAndFourByteElementVector() throws Exception {
        var section = read("club_context_complete_a0_children_20261021.json").get("clubContextAfterA0");
        byte[] raw = hex(read("club_context_nested_leaf_records_live_20261021.json")
                .get("returnValues").get(0).get("contextRaw").asString());
        assertThat(section.get("start").asInt()).isEqualTo(837720);
        assertThat(section.get("end").asInt()).isEqualTo(837847);
        assertThat(section.get("b8Count").asInt()).isZero();
        assertThat(section.get("d0Count").asInt()).isEqualTo(1);
        assertThat(section.get("e8Count").asInt()).isZero();
        assertThat(section.get("compIndex").asInt()).isEqualTo(-1);
        assertThat(little(raw).getLong(0x48)).isZero();
        for (var run : section.get("parentRuns")) {
            int offset = Integer.parseInt(run.get("offsetHex").asString(), 16);
            int width = run.get("width").asInt();
            assertThat(hex(run.get("raw").asString())).isEqualTo(Arrays.copyOfRange(raw, offset, offset + width));
        }
        long vectorBytes = little(raw).getLong(0x120) - little(raw).getLong(0x118);
        assertThat(vectorBytes).isEqualTo(4);
        assertThat(section.get("vector118Count").asInt()).isEqualTo(1);
        assertThat(vectorBytes % Long.BYTES).isNotZero();
    }

    @Test
    void onePersonKeepsPrimaryAndLoanPlayingTimeSeparateWithIndependentWireBytes() throws Exception {
        var capture = read("typed_loan_playing_time_live_20261022.json").get("returnValues").get(0);
        var report = read("typed_loan_playing_time_verification_20261022.json");
        Map<Long, int[]> expected = Map.of(12081410L, new int[]{7, 4},
                12094355L, new int[]{21, 15}, 2000091868L, new int[]{2, 4});
        assertThat(capture.get("unchanged").asBoolean()).isTrue();
        assertThat(capture.get("rows").size()).isEqualTo(3);
        for (var row : capture.get("rows")) {
            byte[] primary = hex(row.get("primaryRaw").asString()), loan = hex(row.get("loanRaw").asString());
            int[] codes = expected.get(row.get("uid").asLong());
            assertThat(primary).hasSize(200);
            assertThat(loan).hasSize(248);
            assertThat(Byte.toUnsignedInt(primary[0x54])).isEqualTo(codes[0]);
            assertThat(Byte.toUnsignedInt(loan[0x54])).isEqualTo(codes[1]).isNotEqualTo(codes[0]);
            assertThat(Byte.toUnsignedInt(loan[0x55])).isNotEqualTo(codes[1]);
            long owner = Long.parseUnsignedLong(row.get("person").asString(), 16);
            assertThat(little(primary).getLong(8)).isEqualTo(owner);
            assertThat(little(loan).getLong(8)).isEqualTo(owner);
        }
        assertThat(report.get("serializedFieldComparisons").asInt()).isEqualTo(46);
        assertThat(report.get("serializedFieldMismatches").size()).isZero();
        for (var row : report.get("rows")) {
            for (var field : row.get("wireMatches")) {
                assertThat(hex(field.get("wireHex").asString())).isEqualTo(hex(field.get("currentHex").asString()));
            }
        }
    }

    @Test
    void twelveByteLoanTermPreservesSemanticFieldsDespiteDifferentCopiedPadding() throws Exception {
        var control = read("typed_loan_playing_time_verification_20261022.json").get("paddingControl");
        byte[] oldRaw = hex(control.get("oldRaw").asString()), newRaw = hex(control.get("newRaw").asString());
        assertThat(oldRaw).hasSize(12);
        assertThat(newRaw).hasSize(12).isNotEqualTo(oldRaw);
        assertThat(little(newRaw).getInt()).isEqualTo(little(oldRaw).getInt()).isEqualTo(2_437_080);
        assertThat(newRaw[4]).isEqualTo(oldRaw[4]).isEqualTo((byte) 12);
        assertThat(Byte.toUnsignedInt(newRaw[8])).isEqualTo(Byte.toUnsignedInt(oldRaw[8])).isEqualTo(6);
        assertThat(little(newRaw).getInt(8)).isNotEqualTo(6); // DWORD here consumes nonserialized bytes9..11.
        assertThat(Byte.toUnsignedInt(newRaw[6])).isNotEqualTo(6); // FULL eight-byte term's kind offset is invalid here.
        assertThat(Arrays.copyOfRange(newRaw, 9, 12)).isNotEqualTo(Arrays.copyOfRange(oldRaw, 9, 12));
    }

    @Test
    void futureFeeKindUsesSignedLoanEfSentinelRatherThanInstallmentPeriod() throws Exception {
        var selection = read("loan_future_fee_selection_20261023.json");
        Map<Long, String> expected = Map.of(12081410L, "optional", 12094355L, "optional", 2000091868L, "mandatory");
        assertThat(selection.get("nativeRoutes").get("thresholdOffsetHex").asString()).isEqualTo("EF");
        assertThat(selection.get("nativeRoutes").get("signed").asBoolean()).isTrue();
        for (var row : selection.get("rows")) {
            String kind = expected.get(row.get("uid").asLong());
            assertThat(row.get("selection").get("kind").asString()).isEqualTo(kind);
            assertThat(row.get("uiKind").asString()).isEqualTo(kind);
            assertThat(row.get("clauseTypeByte8").asInt()).isEqualTo(6); // Same period, different future-fee kinds.
            assertThat(row.get("nativeFormatterInvoked").asBoolean()).isFalse();
            if (kind.equals("mandatory")) {
                assertThat(row.get("clMmSigned").asInt()).isEqualTo(100);
                assertThat(row.get("combinedFutureFeeRaw").asInt()).isEqualTo(698_277);
            } else {
                assertThat(row.get("clMmSigned").asInt()).isEqualTo(-2);
                assertThat(row.get("signednessNegativeControl").get("kind").asString()).isEqualTo("after-matches");
                assertThat(row.get("wireEF").get("rawHex").asString()).isEqualTo("fe00");
            }
        }
        var controls = selection.get("controls");
        assertThat(controls.get(0).get("kind").asString()).isEqualTo("absent"); // Fee gate precedes100 sentinel.
        assertThat(controls.get(1).get("kind").asString()).isEqualTo("mandatory");
        assertThat(controls.get(2).get("kind").asString()).isEqualTo("after-matches");
        assertThat(controls.get(4).get("threshold").asInt()).isEqualTo(101);
        assertThat(controls.get(4).get("kind").asString()).isEqualTo("after-matches");
        assertThat(controls.get(6).get("kind").asString()).isEqualTo("competition-qualified-unresolved");
    }

    @Test
    void installmentDurationAndIntervalProduceCountWithoutInventingMissingEnglishLabels() throws Exception {
        var report = read("shared_installments_and_future_fee_templates_20261022.json");
        assertThat(report.get("missingEnglishIds").get(0).asInt()).isEqualTo(0xABF6C);
        assertThat(report.get("missingEnglishIds").get(1).asInt()).isEqualTo(0xABF6D);
        assertThat(report.get("replay").size()).isEqualTo(3);
        for (var row : report.get("replay")) {
            assertThat(row.get("termMonths").asInt()).isEqualTo(12);
            assertThat(row.get("periodMonths").asInt()).isEqualTo(6);
            assertThat(row.get("paymentCount").asInt()).isEqualTo(2);
            assertThat(row.get("nativeFormatterInvoked").asBoolean()).isFalse();
        }
        boolean sixMonth = false, annual = false;
        for (var template : report.get("cases")) {
            if (!template.get("englishAvailable").asBoolean()) {
                assertThat(template.get("labels").size()).isZero();
            } else if (template.get("id").asInt() == 0x6BC67) {
                assertThat(template.get("labels").get(0).get("variants").get(0).asString())
                        .isEqualTo("[%cash#1-long-roundlarge] over [%number#1] Instalments (6 monthly)");
                sixMonth = true;
            } else if (template.get("id").asInt() == 0x6BC68) {
                assertThat(template.get("labels").get(0).get("variants").get(0).asString())
                        .contains("Instalments (12 monthly)");
                annual = true;
            }
        }
        assertThat(sixMonth && annual).isTrue();
        boolean matches = false;
        for (var label : report.get("feeLabels")) {
            if (label.get("id").asInt() == 0x67C56) {
                assertThat(label.get("labels").get(0).get("variants").get(0).asString())
                        .isEqualTo("After [%number#1] matches ([%number#2] played)");
                matches = true;
            }
        }
        assertThat(matches).isTrue();
    }

    @Test
    void conditionalLoanMatchProgressCanExceedItsThreshold() throws Exception {
        var report = read("conditional_loan_samples_verification_20261023.json");
        var capture = read("conditional_loan_samples_live_20261023.json").get("returnValues").get(0);
        assertThat(capture.get("examinedSlots").asInt()).isEqualTo(8192);
        assertThat(capture.get("loanContractsExamined").asInt()).isEqualTo(169);
        assertThat(capture.get("feeBearingLoans").asInt()).isEqualTo(133);
        Map<Long, Integer> progress = Map.of(29219080L, 5, 43390774L, 36, 49048469L, 25);
        int found = 0, exceeds = 0;
        for (var row : report.get("rows")) {
            if (!row.get("kind").asString().equals("after-matches")) continue;
            long uid = row.get("uid").asLong();
            assertThat(row.get("threshold").asInt()).isEqualTo(20);
            assertThat(row.get("played").asInt()).isEqualTo(progress.get(uid));
            for (var rawRow : capture.get("selected")) {
                if (rawRow.get("uid").asLong() != uid) continue;
                byte[] bytes = hex(rawRow.get("raw").asString());
                assertThat(bytes[0xEF]).isEqualTo((byte) 20);
                assertThat(Byte.toUnsignedInt(bytes[0xF0])).isEqualTo(progress.get(uid));
            }
            assertThat(row.get("uiObserved").asBoolean()).isFalse();
            assertThat(row.get("nativeFormatterInvoked").asBoolean()).isFalse();
            if (row.get("playedExceedsThreshold").asBoolean()) exceeds++;
            found++;
        }
        assertThat(found).isEqualTo(3);
        assertThat(exceeds).isEqualTo(2); // These valid captured bytes must not be clamped/rejected by played<=threshold.
    }

    @Test
    void competitionQualifiedLoanUsesTypedExternalCompetitionAndReorderedConditionTuple() throws Exception {
        var report = read("conditional_loan_samples_verification_20261023.json");
        var capture = read("conditional_loan_samples_live_20261023.json").get("returnValues").get(0);
        int found = 0;
        for (var row : report.get("rows")) {
            var competition = row.get("competition");
            if (competition.get("absent").asBoolean()) continue;
            assertThat(row.get("threshold").asInt()).isEqualTo(-1);
            assertThat(competition.get("typeName").asString()).isEqualTo(".?AVCOMP@db@@");
            assertThat(competition.get("uid").asLong()).isIn(11L, 33L);
            byte[] prefix = hex(competition.get("prefix").asString());
            assertThat(little(prefix).getInt(8)).isEqualTo(competition.get("index").asInt());
            assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(competition.get("uid").asLong());
            assertThat(row.get("condition").get("competitionIndex90").asInt()).isEqualTo(-1);
            int condition = row.get("condition").get("kind9C").asInt();
            assertThat(condition).isIn(3, 6);
            String text = row.get("templates").get(0).asString();
            assertThat(text).isEqualTo(condition == 3 ? "Promotion from the [%comp#1-short]"
                    : "Avoiding relegation from the [%comp#1-short]");
            for (var rawRow : capture.get("selected")) {
                if (rawRow.get("uid").asLong() != row.get("uid").asLong()) continue;
                byte[] bytes = hex(rawRow.get("raw").asString());
                assertThat(little(bytes).getInt(0xB4)).isEqualTo(competition.get("index").asInt()).isNotEqualTo(-1);
                assertThat(little(bytes).getInt(0x90)).isEqualTo(-1); // Separate internal tuple ref, not externalB4COMP.
                assertThat(Byte.toUnsignedInt(bytes[0x9C])).isEqualTo(condition);
            }
            found++;
        }
        assertThat(found).isEqualTo(3);
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
