package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent captured native registry evidence; does not test production extraction. */
class Fm26CapturedRegistryEvidenceTest {
    private static final Path EVIDENCE = Path.of("docs/reverse-engineering/fm26/evidence");

    @Test
    void capturedFutureEmploymentRecordsSelectNewContractsByOfferIndexAndContainTheirOwnClauses() throws Exception {
        var capture=read("future_employment_records_full_live_20261024.json").get("returnValues").get(0);
        var previous=read("future_transfer_current_contract_links_live_20261024.json").get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        String[] expectedEnd={"2038-06-30","2039-06-30","2038-06-30","2038-06-30","2039-06-30","2038-06-30","2037-12-31","2037-12-31"};
        int totalTerms=0,discardedFirstMatches=0;
        for(int i=0;i<capture.get("rows").size();i++) {
            var row=capture.get("rows").get(i);var old=previous.get("rows").get(i);
            assertThat(row.get("uid").asLong()).isEqualTo(old.get("person").get("uid").asLong());
            JsonNode selected=null;
            for(var record:row.get("records"))if(record.get("offerIndex").asInt()==row.get("slot").asInt()) {
                assertThat(selected).isNull();selected=record;
            }
            assertThat(selected).isNotNull();
            var prefix=little(selected.get("rawPrefix").asString());
            assertThat(prefix.capacity()).isEqualTo(72);
            assertThat(prefix.getInt(0x28)).isEqualTo(row.get("slot").asInt());
            assertThat(Byte.toUnsignedInt(prefix.get(0x3e))).isEqualTo(2);
            assertThat(prefix.getLong(0x10)).isEqualTo(address(selected.get("contract").asString()));
            assertThat(selected.get("contract").asString()).isNotEqualTo(old.get("primaryContract").get("address").asString());
            assertThat(selected.get("ownerMatchesPerson").asBoolean()).isTrue();
            var core=little(selected.get("contractCore").asString());
            assertThat(core.getLong(8)).isEqualTo(address(row.get("person").get("address").asString()));
            assertThat(core.getLong(0x10)).isEqualTo(address(selected.get("team").get("address").asString()));
            assertThat(core.get(0x2b)&8).isZero(); // Fixed-date branch of independently decoded virtualE8.
            assertThat(java.time.LocalDate.ofYearDay(Short.toUnsignedInt(core.getShort(0x4a)),Short.toUnsignedInt(core.getShort(0x48))&511).toString()).isEqualTo(expectedEnd[i]);
            assertThat(selected.get("club").get("uid").asLong()).isEqualTo(old.get("teams").get(0).get("club").get("uid").asLong());
            for(var group:selected.get("collections"))if(group.get("group").asString().equals("FULL_68")) {
                var terms=little(group.get("rawHex").asString());assertThat(terms.capacity()).isEqualTo(group.get("count").asInt()*8);
                totalTerms+=group.get("count").asInt();
            }
            if(row.get("records").get(0).get("offerIndex").asInt()!=row.get("slot").asInt())discardedFirstMatches++;
        }
        assertThat(totalTerms).isEqualTo(43);assertThat(discardedFirstMatches).isEqualTo(3);
    }

    @Test
    void capturedPersonOfferIndicesReachTheStatus19ManagerSlotsWithoutReadingContracts() throws Exception {
        var capture=read("future_transfer_status19_candidates_live_20261024.json").get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("rows").size()).isEqualTo(8);
        var bounds=little(capture.get("headerRaw").asString());
        assertThat((bounds.getLong(8)-bounds.getLong(0))/8).isEqualTo(12121);
        for(var row:capture.get("rows")) {
            long person=address(row.get("person").get("address").asString());
            var prefix=little(row.get("person").get("prefix").asString());
            var core=little(row.get("coreHex").asString());
            assertThat(Integer.toUnsignedLong(prefix.getInt(12))).isEqualTo(row.get("person").get("uid").asLong());
            assertThat(core.getLong(0x38)).isEqualTo(person);
            assertThat(Byte.toUnsignedInt(core.get(0x8d))).isEqualTo(19);
            assertThat(core.getInt(0x5c)).isEqualTo(row.get("slot").asInt());
            var indices=row.get("personIndices");
            int delta=little(indices.get("deltaRaw").asString()).getInt(0);
            int vbOffset=little(indices.get("vbOffsetRaw").asString()).getInt(0);
            // Actual eight examples are pure-player PERSON thunks140D710; combined shape is static-only here.
            long field=person-delta+vbOffset-0x210;
            assertThat(field).isEqualTo(address(indices.get("field").asString())).isEqualTo(person+0x98);
            var header=little(indices.get("headerRaw").asString());var raw=little(indices.get("raw").asString());
            assertThat(header.getLong(8)-header.getLong(0)).isEqualTo(raw.capacity());
            boolean found=false;
            for(int i=0;i<raw.capacity()/4;i++) {
                int index=raw.getInt(i*4);assertThat(index).isEqualTo(indices.get("indices").get(i).asInt());
                if(index==row.get("slot").asInt())found=true;
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void capturedOfferIndexVectorsCannotBeTreatedAsPointersOrFirstMatchLists() throws Exception {
        var rows=read("future_transfer_status19_candidates_live_20261024.json").get("returnValues").get(0).get("rows");
        var row=rows.get(3);assertThat(row.get("slot").asInt()).isEqualTo(79);
        var indices=little(row.get("personIndices").get("raw").asString());
        assertThat(indices.getInt(0)).isEqualTo(78);assertThat(indices.getInt(4)).isEqualTo(79);
        assertThat(indices.getLong(0)).isGreaterThan(12121); // Two32bit indices, not an offer pointer/index64.
        var other=rows.get(5);assertThat(other.get("slot").asInt()).isEqualTo(94);
        assertThat(little(other.get("personIndices").get("raw").asString()).getInt(0)).isEqualTo(12073);
    }

    @Test
    void capturedPatcherSymbolsUseLengthPrefixedStorageAndIdentifySixRegisteredCallbacks() throws Exception {
        var registry = read("late_native_callback_registry_live_20261024.json").get("returnValues").get(0);
        var storage = read("game_patcher_symbol_storage_live_20261024.json").get("returnValues").get(0);
        assertThat(registry.get("managerType").get("typeName").asString()).isEqualTo(".?AVGAME_PATCHER@fm@@");
        var expected = java.util.Map.of("4E4F458", 0x1675770L, "4E4F460", 0x16758c0L,
                "4E4F468", 0x1675950L, "4E4F470", 0x1675b30L, "4E45AC8", 0x9a7db0L, "4E45AD0", 0x9a94e0L);
        long base = Long.parseUnsignedLong(registry.get("pluginBase").asString(), 16);
        assertThat(registry.get("rows").size()).isEqualTo(6);
        for (var row : registry.get("rows")) {
            String key = row.get("globalKeyRva").asString();
            var node = little(row.get("nodeRaw").asString());
            var functor = little(row.get("functorRaw").asString());
            assertThat(row.get("found").asBoolean()).isTrue();
            assertThat(node.getLong(0x20)).isEqualTo(Long.parseUnsignedLong(row.get("key").asString(), 16));
            assertThat(node.getLong(0x60)).isEqualTo(Long.parseUnsignedLong(row.get("functor").asString(), 16));
            assertThat(functor.getLong(8) - base).isEqualTo(expected.get(key));
            JsonNode symbol = null;
            for (var candidate : storage.get("rows")) if (candidate.get("globalRva").asString().equals(key)) symbol = candidate;
            assertThat(symbol).isNotNull();
            byte[] bytes = HexFormat.of().parseHex(symbol.get("storageRaw").asString());
            int length = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertThat(length).isBetween(1, 123);
            assertThat(bytes[4 + length]).isZero();
            String name = new String(bytes, 4, length, java.nio.charset.StandardCharsets.US_ASCII);
            assertThat(name).contains("_").hasSize(length);
            if (key.equals("4E4F460")) assertThat(name).isEqualTo("PLAYER_STATS_MANAGER_patch_played_professionally_flag");
            if (key.equals("4E4F470")) assertThat(name).contains("pay_increase_after_career_league_games_clause");
        }
    }

    @Test
    void capturedPatcherBuildTokenIsDistinctFromTheNativeReloadCounter() throws Exception {
        var registry = read("late_native_callback_registry_live_20261024.json").get("returnValues").get(0);
        var symbols = read("game_patcher_symbols_live_20261024.json").get("returnValues").get(0);
        var patcher = little(registry.get("managerRaw").asString());
        assertThat(patcher.getInt(0x48)).isEqualTo(2329565);
        assertThat(patcher.getInt(0x48)).isEqualTo(little(symbols.get("sourceRaw").asString()).getInt(0x34));
        assertThat(patcher.get(0x4c)).isEqualTo((byte) 1);
        byte[] ui = HexFormat.of().parseHex(registry.get("uiState").asString());
        assertThat(ByteBuffer.wrap(ui).order(ByteOrder.LITTLE_ENDIAN).getLong()).isEqualTo(2);
        // This fixture verifies distinct bytes. Native code, not this test, proves reset-before-work.
    }

    @Test
    void capturedSceneOperationStatusRequiresTheNativeHandleVersionToMatch() throws Exception {
        var receipt = read("scene_loader_state_live_20261024.json");
        assertThat(receipt.get("ok").asBoolean()).isTrue();
        var capture = receipt.get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        var scene = little(capture.get("scenePrefix").asString());
        var operationState = little(capture.get("operationStateRaw").asString()); // Begins at operation+20.
        assertThat(scene.getInt(0x50)).isEqualTo(3);
        assertThat(scene.getLong(0x58)).isEqualTo(Long.parseUnsignedLong(capture.get("operation").asString(), 16));
        assertThat(decodeCapturedSceneOperationStatus(scene, operationState)).isEqualTo(1);
        // A plausible old succeeded status must not survive the native version mismatch check.
        scene.putInt(0x60, scene.getInt(0x60) + 1);
        assertThatThrownBy(() -> decodeCapturedSceneOperationStatus(scene, operationState))
                .isInstanceOf(IOException.class).hasMessageContaining("Stale scene operation");
    }

    private static int decodeCapturedSceneOperationStatus(ByteBuffer scene, ByteBuffer operationState) throws IOException {
        if (scene.getInt(0x60) != operationState.getInt(0x18)) {
            throw new IOException("Stale scene operation handle");
        }
        return operationState.getInt(4);
    }

    @Test
    void capturedLoadingReferenceCountIsAnchoredByTheLiveGetterRatherThanPlausibleZeros() throws Exception {
        var receipt = read("managed_loading_references_live_20261024.json");
        assertThat(receipt.get("ok").asBoolean()).isTrue();
        var capture = receipt.get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        var getter = HexFormat.of().parseHex(capture.get("countFunctionBytes").asString());
        assertThat(java.util.Arrays.copyOf(getter, 4)).containsExactly((byte) 0x8b, (byte) 0x41, (byte) 0x18, (byte) 0xc3);
        var manager = little(capture.get("managerPrefix").asString());
        var counter = little(capture.get("counterPrefix").asString());
        var list = little(capture.get("listPrefix").asString());
        assertThat(manager.getLong(0x10)).isEqualTo(Long.parseUnsignedLong(capture.get("counter").asString(), 16));
        assertThat(counter.getLong(0x10)).isEqualTo(Long.parseUnsignedLong(capture.get("list").asString(), 16));
        assertThat(list.getLong(0)).isEqualTo(Long.parseUnsignedLong(capture.get("listClass").asString(), 16));
        assertThat(list.getInt(Byte.toUnsignedInt(getter[2]))).isZero();
        assertThat(list.getInt(0x20)).isZero(); // Null syncRoot at a wrong offset must not prove a mapping.
        assertThat(list.getInt(0x1c)).isEqualTo(16); // ArrayList version, not active-owner count.
        for (int shifted : new int[]{0x20, 0x1c}) {
            assertThat(shifted).isNotEqualTo(Byte.toUnsignedInt(getter[2]));
        }
    }

    @Test
    void productionPersonIdentityLayoutAcceptsCapturedPrefixesAndRejectsOffsetDrift() throws Exception {
        var capture = read("two_human_identities_after_reload_1792826397.json").get("returnValues").get(0);
        for (var row : capture.get("rows")) {
            byte[] prefix = HexFormat.of().parseHex(row.get("personPrefix").asString());
            int index = row.get("index").asInt();
            assertThat(com.github.fmaiassistent.exporter.PlayerSnapshotValidator.validatePersonIdentity(
                    index, prefix, PersonIdentityLayout.current())).isEqualTo(row.get("uid").asLong());
            for (var shifted : new PersonIdentityLayout[]{new PersonIdentityLayout(12, 12, 16),
                    new PersonIdentityLayout(8, 8, 16), new PersonIdentityLayout(8, 11, 16),
                    new PersonIdentityLayout(8, 12, 24)}) {
                assertThatThrownBy(() -> com.github.fmaiassistent.exporter.PlayerSnapshotValidator.validatePersonIdentity(
                        index, prefix, shifted)).isInstanceOf(IOException.class);
            }
        }
        assertThat(PersonIdentityLayout.current().indexRel()).isEqualTo(ContractRecordLayout.current().links().index());
        assertThat(PersonIdentityLayout.current().uniqueIdRel()).isEqualTo(ContractRecordLayout.current().links().uid());
    }

    @Test
    void observedReloadCounterAdvancesBeforeTheGameClearsItsBusyState() throws Exception {
        var report = read("load_readiness_reload_1792826397_verification.json");
        assertThat(report.get("changeRecords").asInt()).isEqualTo(11);
        assertThat(report.get("counterPrecedesIdleMs").asInt()).isEqualTo(750);
        JsonNode firstIncrement = null;
        for (var row : report.get("rows")) {
            if (row.get("ui").get("counter").asString().equals("2")) {
                firstIncrement = row;
                break;
            }
        }
        assertThat(firstIncrement).isNotNull();
        assertThat(firstIncrement.get("ui").get("statusHex").asString()).isEqualTo("0101");
        assertThat(report.get("rows").get(10).get("ui").get("statusHex").asString()).isEqualTo("0000");
    }

    @Test
    void registryHeadersCanReturnToIdenticalValuesAfterAnObservedEmptyAndRefillCycle() throws Exception {
        var rows = read("load_readiness_reload_1792826397_verification.json").get("rows");
        var before = rows.get(0);
        var after = rows.get(rows.size() - 1);
        for (String registry : new String[]{"person", "club", "team"}) {
            assertThat(after.get(registry)).isEqualTo(before.get(registry));
            boolean sawEmpty = false;
            for (var row : rows) if (row.get(registry).get("slots").asInt() == 0) sawEmpty = true;
            assertThat(sawEmpty).isTrue();
        }
        assertThat(after.get("players")).isEqualTo(before.get("players"));
        assertThat(after.get("nonplayers")).isEqualTo(before.get("nonplayers"));
        // Header equality is useful for bounded reads but cannot prove no intervening reload.
    }

    @Test
    void anActualReloadReusesHumanPersonAddressesForDifferentLogicalIdentities() throws Exception {
        var before = read("human_projection_membership_live_20261024.json").get("returnValues").get(0);
        var after = read("two_human_identities_after_reload_1792826397.json").get("returnValues").get(0);
        assertThat(after.get("guardsUnchanged").asBoolean()).isTrue();
        int reused = 0;
        for (var old : before.get("rows")) {
            for (var current : after.get("rows")) {
                if (!current.get("person").asString().equals(old.get("person").asString())) continue;
                var oldBytes = little(old.get("personPrefix").asString());
                var newBytes = little(current.get("personPrefix").asString());
                assertThat(newBytes.getLong(0)).isEqualTo(oldBytes.getLong(0));
                assertThat(newBytes.getInt(12)).isNotEqualTo(oldBytes.getInt(12));
                assertThat(newBytes.getInt(8)).isNotEqualTo(oldBytes.getInt(8));
                assertThat(current.get("type").get("typeName").asString())
                        .isEqualTo(old.get("type").get("typeName").asString());
                reused++;
            }
        }
        assertThat(reused).isEqualTo(2);
    }

    @Test
    void humanVersionTwelvePayloadMatchesAllNativeReadSpansInTheCapturedRamObject() throws Exception {
        verifyHumanPayload(new CapturedHumanPayloadLayout(16, 0));
    }

    @Test
    void shiftedHumanPayloadAndShiftedRamFieldsFailTheCapturedByteMapping() {
        assertThatThrownBy(() -> verifyHumanPayload(new CapturedHumanPayloadLayout(17, 0)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> verifyHumanPayload(new CapturedHumanPayloadLayout(16, 1)))
                .isInstanceOf(AssertionError.class);
    }

    private static void verifyHumanPayload(CapturedHumanPayloadLayout layout) throws Exception {
        var report = read("human_payload_v12_wire_ram_20261024.json");
        var wire = little(read("humans_member_wire_20261024.json").get("rawHex").asString());
        var capture = read("human_payload_target_live_20261024.json").get("returnValues").get(0);
        var ram = little(capture.get("profileRaw").asString());
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(wire.getShort(14)).isEqualTo((short) 12);
        assertThat(little(capture.get("ownerLinkRaw").asString()).getLong(0))
                .isEqualTo(address(capture.get("profile").asString()));
        assertThat(report.get("rows").size()).isEqualTo(124);
        assertThat(ram.remaining()).isEqualTo(0xf0);
        boolean[] written = new boolean[ram.remaining()];
        int assigned = 0;
        int cursor = layout.fileFieldsStart();
        for (var row : report.get("rows")) {
            int offset = Integer.parseInt(row.get("memoryOffsetHex").asString(), 16) + layout.nativeShift();
            int width = row.get("width").asInt();
            for (int i = 0; i < width; i++) {
                assertThat(written[offset + i]).isFalse();
                assertThat(ram.get(offset + i)).isEqualTo(wire.get(cursor + i));
                written[offset + i] = true;
                assigned++;
            }
            cursor += width;
        }
        assertThat(assigned).isEqualTo(227);
        assertThat(cursor).isEqualTo(243);
        // Byte spans only: business field names and the following objects remain separate evidence.
    }

    @Test
    void humanManagerViewsResolveOnePersonThroughPositiveAndNegativeVbtableDisplacements() throws Exception {
        var capture = read("human_manager_person_identity_live_20261024.json").get("returnValues").get(0);
        verifyCapturedHumanViews(capture, new CapturedHumanViewLayout(4, 0x450));
        assertThat(capture.get("candidates").get(0).get("displacement").asInt()).isEqualTo(0x450);
        assertThat(capture.get("candidates").get(1).get("displacement").asInt()).isEqualTo(-0x30);
        assertThatThrownBy(() -> verifyCapturedHumanViews(capture, new CapturedHumanViewLayout(8, 0x450)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> verifyCapturedHumanViews(capture, new CapturedHumanViewLayout(4, 0x458)))
                .isInstanceOf(AssertionError.class);
    }

    private static void verifyCapturedHumanViews(JsonNode capture, CapturedHumanViewLayout layout) {
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        long human = address(capture.get("human").asString());
        assertThat(capture.get("candidates").size()).isEqualTo(2);
        for (var candidate : capture.get("candidates")) {
            int displacement = little(candidate.get("vbtableRaw").asString()).getInt(layout.displacementRel());
            long person = human + address(candidate.get("vbptrOffset").asString()) + displacement;
            assertThat(person).isEqualTo(address(candidate.get("candidate").asString()))
                    .isEqualTo(human + layout.personRel());
            assertThat(candidate.get("registryMatched").asBoolean()).isTrue();
            var prefix = little(candidate.get("candidatePrefix").asString());
            assertThat(prefix.getInt(8)).isEqualTo(88374);
            assertThat(prefix.getInt(12)).isEqualTo(2002076190);
            var type = candidate.get("type");
            var col = little(type.get("rawCol").asString());
            assertThat(col.getInt(4)).isEqualTo(layout.personRel());
            assertThat(type.get("typeName").asString()).isEqualTo(".?AVHUMAN_NON_PLAYER@db@@");
            assertThat(type.get("completeObject").asString()).isEqualTo(capture.get("human").asString());
        }
    }

    @Test
    void humanManagerPrimaryListIsAFilteredSubsetOfTheCapturedNonPlayerProjection() throws Exception {
        var capture = read("human_projection_membership_live_20261024.json").get("returnValues").get(0);
        var manager = read("human_manager_collections_live_20261024.json").get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("matchedCount").asInt()).isEqualTo(2);
        assertThat(capture.get("primaryCount").asInt()).isEqualTo(1);
        var managerBytes = little(manager.get("managerRaw").asString());
        assertThat(managerBytes.getLong(0x20) - managerBytes.getLong(0x18)).isEqualTo(Long.BYTES);
        long active = address(manager.get("vectors").get(0).get("samples").get(0).get("pointer").asString());
        int included = 0;
        for (var row : capture.get("rows")) {
            var prefix = little(row.get("personPrefix").asString());
            assertThat(Integer.toUnsignedLong(prefix.getInt(12))).isEqualTo(row.get("uid").asLong());
            assertThat(address(row.get("person").asString()) - address(row.get("component").asString()))
                    .isEqualTo(0x450);
            boolean member = row.get("inHumanManagerPrimary").asBoolean();
            assertThat(member).isEqualTo(address(row.get("component").asString()) == active);
            assertThat(little(row.get("flag431to435").asString()).get(0) != 0).isEqualTo(member);
            if (member) included++;
        }
        assertThat(included).isEqualTo(1);
        // Captured subset identity only: no business label or save-generation predicate is inferred.
    }

    @Test
    void nativePostDatabaseCallRootsMatchFourDistinctManagerTypes() throws Exception {
        var capture = read("post_database_manager_roots_windows_live_20261024.json").get("returnValues").get(0);
        var types = java.util.Map.of("4E362A8", ".?AVGAME_RULE_GROUP_MANAGER@@",
                "4E360F8", ".?AVHUMAN_NON_PLAYER_MANAGER@@", "4E369B8", ".?AVNEWS_MANAGER@@",
                "4E36CB8", ".?AVTEAM_CONTAINER_RECORD_MANAGER@@");
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("rows").size()).isEqualTo(4);
        for (var row : capture.get("rows")) {
            verifyCapturedManagerRoot(capture, row, new CapturedManagerRootLayout(0));
            assertThat(row.get("typeName").asString()).isEqualTo(types.get(row.get("rootRva").asString()));
        }
    }

    @Test
    void humanSaveMemberHeaderSeparatesFramingFromItsDeclaredCountAndPersonReference() throws Exception {
        var member = read("humans_member_extract_20261024.json");
        var bytes = little(member.get("prefixHex").asString());
        assertThat(bytes.get(0)).isEqualTo((byte) 3);
        assertThat(bytes.get(1)).isEqualTo((byte) 1);
        assertThat(bytes.getInt(2)).isEqualTo(0x2e646174);
        assertThat(bytes.getShort(6)).isEqualTo((short) 21);
        assertThat(bytes.getShort(8)).isEqualTo((short) 1);
        assertThat(bytes.getShort(0)).isNotEqualTo(bytes.getShort(8));
        var live = read("human_projection_membership_live_20261024.json").get("returnValues").get(0);
        int matches = 0;
        for (var row : live.get("rows")) {
            if (row.get("index").asInt() == bytes.getInt(10)) {
                assertThat(row.get("uid").asInt()).isEqualTo(2002076190);
                assertThat(row.get("inHumanManagerPrimary").asBoolean()).isTrue();
                matches++;
            }
        }
        assertThat(matches).isEqualTo(1);
        // Only the declared count and first reference are parsed, not the following payload.
    }

    @Test
    void shiftedManagerRootCannotReuseTheCapturedObjectIdentity() throws Exception {
        var capture = read("post_database_manager_roots_windows_live_20261024.json").get("returnValues").get(0);
        for (var row : capture.get("rows")) {
            assertThatThrownBy(() -> verifyCapturedManagerRoot(capture, row, new CapturedManagerRootLayout(8)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    private static void verifyCapturedManagerRoot(JsonNode capture, JsonNode row, CapturedManagerRootLayout layout) {
        assertThat(row.get("status").asString()).isEqualTo("TYPED");
        long pointer = address(row.get("pointer").asString());
        assertThat(little(row.get("rootWindowRaw").asString()).getLong(layout.pointerWithinWindow())).isEqualTo(pointer);
        assertThat(little(row.get("objectVptrRaw").asString()).getLong(0))
                .isEqualTo(address(capture.get("moduleBase").asString()) + address(row.get("vtableRva").asString()));
        var col = little(row.get("rawCol").asString());
        assertThat(col.getInt(0)).isEqualTo(1);
        assertThat(col.getInt(4)).isZero();
        assertThat(col.getInt(8)).isZero();
        assertThat(Integer.toUnsignedLong(col.getInt(20))).isEqualTo(address(row.get("colRva").asString()));
        assertThat(row.get("typeNameTruncated").asBoolean()).isFalse();
    }

    @Test
    void gameUiRootsAndThreeInterfacesIdentifyOneObjectAcrossTwoProcesses() throws Exception {
        var older = read("game_ui_ownership_layout_live_20261023.json").get("returnValues").get(0);
        var newer = read("game_ui_ownership_layout_live_20261024.json").get("returnValues").get(0);
        var layout = new CapturedGameUiLayout(0x208, 0x10, 0x200);
        verifyCapturedGameUi(older, layout);
        verifyCapturedGameUi(newer, layout);
        assertThat(newer.get("pid").asInt()).isNotEqualTo(older.get("pid").asInt());
        assertThat(newer.get("moduleBase").asString()).isNotEqualTo(older.get("moduleBase").asString());
        assertThat(newer.get("owner").asString()).isNotEqualTo(older.get("owner").asString());
    }

    @Test
    void shiftedGameUiInterfaceAndWrongAllocationBoundFailNativeAnchors() throws Exception {
        var capture = read("game_ui_ownership_layout_live_20261024.json").get("returnValues").get(0);
        for (var layout : new CapturedGameUiLayout[]{new CapturedGameUiLayout(0x208, 0x18, 0x200),
                new CapturedGameUiLayout(0x208, 0x10, 0x1f8), new CapturedGameUiLayout(0x200, 0x10, 0x200)}) {
            assertThatThrownBy(() -> verifyCapturedGameUi(capture, layout)).isInstanceOf(AssertionError.class);
        }
    }

    private static void verifyCapturedGameUi(JsonNode capture, CapturedGameUiLayout layout) {
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("owner").asString()).isEqualTo(capture.get("active").asString());
        var bytes = little(capture.get("rawAllocation").asString());
        assertThat(bytes.remaining()).isEqualTo(layout.allocationSize());
        long base = address(capture.get("moduleBase").asString());
        long[] vtables = {0x43179c8L, 0x4317c48L, 0x4317c60L};
        int[] offsets = {0, layout.secondaryRel(), layout.tailRel()};
        assertThat(capture.get("interfaces").size()).isEqualTo(3);
        for (int i = 0; i < offsets.length; i++) {
            var entry = capture.get("interfaces").get(i);
            assertThat(entry.get("offset").asInt()).isEqualTo(offsets[i]);
            assertThat(bytes.getLong(offsets[i])).isEqualTo(base + vtables[i]);
            var col = little(entry.get("rawCol").asString());
            assertThat(col.getInt(0)).isEqualTo(1);
            assertThat(col.getInt(4)).isEqualTo(offsets[i]);
            assertThat(col.getInt(8)).isZero();
            assertThat(entry.get("typeName").asString()).isEqualTo(".?AVGAME_UI@game_plugin@@");
        }
    }

    @Test
    void capturedSavesLoadedCounterAgreesWithTheGamesOwnDiagnosticText() throws Exception {
        var capture = read("load_counter_registry_byteflags_live_20261023.json").get("returnValues").get(0);
        verifyCapturedSavesLoadedCounter(capture, new CapturedSavesLoadedLayout(8));
        assertThat(capture.get("nativeHeaderStartedLine").asString()).isEqualTo("Game Started: true");
        var person = capture.get("registries").get("PERSON");
        var descriptor = little(person.get("rawDescriptor").asString());
        assertThat(descriptor.getInt(0x50)).isEqualTo(2002139392)
                .isEqualTo(person.get("signed32At50").asInt()).isNotEqualTo(person.get("slots").asInt());
        assertThat(descriptor.getLong(0x88)).isZero();
        assertThat(descriptor.getLong(0x90)).isZero();
        assertThat(Byte.toUnsignedInt(descriptor.get(0x5c))).isEqualTo(person.get("unsignedFlags5C").asInt());
        // Empty optional UID index coexists with a populated central PERSON registry.
        assertThat(person.get("secondaryCount").asInt()).isZero();
        assertThat(person.get("slots").asInt()).isEqualTo(151577);
    }

    @Test
    void shiftedSavesLoadedCounterFailsTheIndependentNativeTextAnchor() throws Exception {
        var capture = read("load_counter_registry_byteflags_live_20261023.json").get("returnValues").get(0);
        for (int shifted : new int[]{0, 16}) {
            assertThatThrownBy(() -> verifyCapturedSavesLoadedCounter(capture,
                    new CapturedSavesLoadedLayout(shifted))).isInstanceOf(AssertionError.class);
        }
    }

    private static void verifyCapturedSavesLoadedCounter(JsonNode capture, CapturedSavesLoadedLayout layout) {
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        var ui = little(capture.get("uiC0toDF").asString());
        assertThat(ui.getLong(0)).isEqualTo(address(capture.get("headerTextPointer").asString()));
        long counter = ui.getLong(layout.counterWithinC0Block());
        assertThat(counter).isEqualTo(address(capture.get("loadCounterC8Hex").asString()));
        byte[] textBytes = HexFormat.of().parseHex(capture.get("rawHeaderPrefix").asString());
        assertThat(textBytes.length).isEqualTo(capture.get("headerTextLength").asInt());
        var text = new String(textBytes, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text.lines().filter(line -> line.startsWith("Saves Loaded: ")).toList())
                .containsExactly("Saves Loaded: " + Long.toUnsignedString(counter));
    }

    @Test
    void nativeNonPlayerHistoryIsAnOptionalVectorOfSeparateFiftySixByteSnapshots() throws Exception {
        var capture = read("nonplayer_attribute_history_live_20261023.json").get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("checked").asInt()).isEqualTo(8192);
        assertThat(capture.get("nullPointers").asInt()).isEqualTo(8168);
        assertThat(capture.get("nonNullPointers").asInt()).isEqualTo(24);
        assertThat(capture.get("samples").size()).isEqualTo(3);
        for (var sample : capture.get("samples")) {
            verifyCapturedAttributeHistory(sample, new CapturedAttributeHistoryLayout(0x98, 0x38));
            var prefix = little(sample.get("personPrefix").asString());
            assertThat(prefix.getInt(8)).isEqualTo(sample.get("personIndex").asInt());
            assertThat(prefix.getInt(12)).isEqualTo(sample.get("uid").asInt());
            // Native producer suppresses these selector positions, not these raw profile offsets.
            for (var row : sample.get("rows")) {
                var bytes = little(row.get("raw").asString());
                for (int selector : new int[]{22, 28, 29, 30}) assertThat(bytes.get(selector)).isZero();
                assertThat(bytes.getInt(0x30)).isBetween(0, 200);
                assertThat(bytes.getInt(0x34)).isBetween(0, 200);
            }
        }
    }

    @Test
    void shiftedHistoryPointerAndSnapshotExtentFailCapturedAnchors() throws Exception {
        var samples = read("nonplayer_attribute_history_live_20261023.json")
                .get("returnValues").get(0).get("samples");
        for (var sample : samples) {
            assertThatThrownBy(() -> verifyCapturedAttributeHistory(sample,
                    new CapturedAttributeHistoryLayout(0xa0, 0x38))).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> verifyCapturedAttributeHistory(sample,
                    new CapturedAttributeHistoryLayout(0x98, 0x30))).isInstanceOf(AssertionError.class);
        }
    }

    private static void verifyCapturedAttributeHistory(JsonNode sample, CapturedAttributeHistoryLayout layout) {
        var component = little(sample.get("componentRaw").asString());
        assertThat(component.getLong(layout.historyRel())).isEqualTo(address(sample.get("historyPointer").asString()));
        assertThat(address(sample.get("component").asString()) + 0x100)
                .isEqualTo(address(sample.get("person").asString()));
        var header = little(sample.get("headerRaw").asString());
        long begin = header.getLong(0), end = header.getLong(8), capacity = header.getLong(16);
        assertThat(begin).isEqualTo(address(sample.get("begin").asString()));
        assertThat(end).isEqualTo(address(sample.get("ending").asString()));
        assertThat(capacity).isEqualTo(address(sample.get("capacity").asString()));
        assertThat(end - begin).isEqualTo(8L * sample.get("count").asInt());
        assertThat(capacity).isGreaterThanOrEqualTo(end);
        for (var row : sample.get("rows")) {
            assertThat(little(row.get("raw").asString()).remaining()).isEqualTo(layout.snapshotSize());
            assertThat(row.get("index").asInt()).isLessThan(sample.get("count").asInt());
        }
    }

    @Test
    void combinedAndStandaloneObjectsContainTheSameTypedNonPlayingMember() throws Exception {
        var capture = read("combined_player_allocation_live_20261023.json").get("returnValues").get(0);
        assertThat(capture.get("guardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("rows").size()).isEqualTo(4);
        for (var row : capture.get("rows")) {
            verifyCapturedNonPlayingMember(capture, row, new CapturedNonPlayingLayout(8, 0x449B2E8L));
        }
        var combined = capture.get("rows").get(0);
        assertThat(combined.get("uid").asInt()).isEqualTo(222138);
        assertThat(combined.get("componentOffset").asString()).isEqualTo("280");
        assertThat(combined.get("personType").get("name").asString())
                .isEqualTo(".?AVACTUAL_PLAYER_AND_NON_PLAYER@db@@");
    }

    @Test
    void shiftedNonPlayingMemberCannotPassTheCapturedVtableAndAddressAnchors() throws Exception {
        var capture = read("combined_player_allocation_live_20261023.json").get("returnValues").get(0);
        for (var row : capture.get("rows")) {
            assertThatThrownBy(() -> verifyCapturedNonPlayingMember(
                    capture, row, new CapturedNonPlayingLayout(16, 0x449B2E8L)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    private static void verifyCapturedNonPlayingMember(JsonNode capture, JsonNode row,
                                                       CapturedNonPlayingLayout layout) {
        long base = address(capture.get("moduleBase").asString());
        long primary = address(row.get("primary").asString());
        long person = address(row.get("person").asString());
        long component = address(row.get("component").asString());
        var bytes = little(row.get("rawAllocation").asString());
        int componentOffset = Math.toIntExact(component - primary);
        int personOffset = Math.toIntExact(person - primary);
        assertThat(bytes.remaining()).isEqualTo(row.get("staticAllocationSize").asInt());
        assertThat(bytes.remaining() - personOffset).isEqualTo(0x140);
        assertThat(component + layout.memberRel()).isEqualTo(address(row.get("nonPlayingMember").asString()));
        assertThat(bytes.getLong(componentOffset + layout.memberRel()))
                .isEqualTo(base + layout.memberVtableRva());
        assertThat(bytes.getLong(personOffset)).isEqualTo(base + address(row.get("personType").get("vtableRva").asString()));
        assertThat(bytes.getInt(personOffset + 8)).isEqualTo(row.get("personIndex").asInt());
        assertThat(Integer.toUnsignedLong(bytes.getInt(personOffset + 12))).isEqualTo(row.get("uid").asLong());
        assertThat(row.get("memberType").get("name").asString()).isEqualTo(".?AVNON_PLAYING_DATA@db@@");
        assertThat(row.get("memberType").get("offset").asInt()).isZero();
    }

    @Test
    void nationCityRangesDecodeSignedBoundsAndCoverTheDerivedCityIndex() throws Exception {
        var capture = read("city_nation_group_index_live_20261020.json").get("returnValues").get(0);
        var ranges = new ArrayList<int[]>();
        int absent = 0;
        for (var nation : capture.get("nationRanges")) {
            var bytes = little(nation.get("rawRange").asString());
            int first = bytes.getInt(0), last = bytes.getInt(4);
            assertThat(first).isEqualTo(nation.get("storedFirst").asInt());
            assertThat(last).isEqualTo(nation.get("storedLast").asInt());
            if (first == -1) {
                assertThat(last).isEqualTo(-1);
                absent++;
            } else {
                assertThat(first).isEqualTo(nation.get("observedFirst").asInt());
                assertThat(last).isEqualTo(nation.get("observedLast").asInt());
                assertThat(last - first + 1).isEqualTo(nation.get("observedCount").asInt());
                ranges.add(new int[]{first, last});
            }
        }
        ranges.sort(Comparator.comparingInt(row -> row[0]));
        int next = 0;
        for (var range : ranges) {
            assertThat(range[0]).isEqualTo(next);
            next = Math.addExact(range[1], 1);
        }
        assertThat(absent).isEqualTo(22);
        assertThat(ranges).hasSize(229);
        assertThat(next).isEqualTo(58349).isEqualTo(capture.get("groupedCount").asInt());
        assertThat(capture.get("errorCount").asInt()).isZero();
    }

    @Test
    void nationalTeamOwnerIsAContainerRatherThanTheContainerNation() throws Exception {
        var alias = read("national_team_shared_container_edge_20261020.json").get("returnValues").get(0);
        var team = little(alias.get("teamBytes").asString());
        var nations = read("shared_national_team_container_nations_20261020.json").get("returnValues").get(0);
        assertThat(Byte.toUnsignedInt(team.get(0x2f))).isEqualTo(1);
        assertThat(team.getInt(8)).isEqualTo(3107);
        assertThat(team.getInt(12)).isEqualTo(145174);
        long owner = team.getLong(0x30);
        assertThat(owner).isEqualTo(address(alias.get("canonicalOwner").asString()));
        for (var row : nations.get("rows")) {
            assertThat(owner).isNotEqualTo(address(row.get("nation").asString()));
        }
        assertThat(owner).isEqualTo(address(alias.get("containers").get(0).get("container").asString()));
        assertThat(alias.get("containers").get(0).get("uid").asInt()).isEqualTo(765);
    }

    @Test
    void shiftedNationPointerWouldReadASeparateClubReferenceAndMustFail() throws Exception {
        var capture = read("shared_national_team_container_nations_20261020.json").get("returnValues").get(0);
        for (var row : capture.get("rows")) {
            byte[] window = HexFormat.of().parseHex(row.get("raw168Through177").asString());
            long nation = address(row.get("nation").asString());
            assertThat(decodeNation(window, new CapturedContainerLayout(8))).isEqualTo(nation);
            assertThatThrownBy(() -> {
                if (decodeNation(window, new CapturedContainerLayout(0)) != nation) {
                    throw new IllegalArgumentException("Shifted field selects another native object");
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another native object");
        }
    }

    @Test
    void sharedNationalTeamMembershipMustNotBeCollapsedIntoTheOwnerPointer() throws Exception {
        var alias = read("national_team_shared_container_edge_20261020.json").get("returnValues").get(0);
        var graph = read("club_team_owner_graph_verification_live_20261020.json").get("returnValues").get(0);
        assertThat(alias.get("containers").size()).isEqualTo(2);
        assertThat(alias.get("containers").get(0).get("isCanonicalOwner").asBoolean()).isTrue();
        assertThat(alias.get("containers").get(1).get("isCanonicalOwner").asBoolean()).isFalse();
        assertThat(graph.get("nationalTeams").asInt()).isEqualTo(3586);
        assertThat(graph.get("containerTeamMemberships").asInt()).isEqualTo(3587);
        assertThat(graph.get("nationalTeamsWithVerifiedContainer").asInt()).isEqualTo(3586);
        assertThat(graph.get("relationshipDiscrepancies").asInt()).isEqualTo(1);
        assertThat(graph.get("relationshipStatus").asString()).isEqualTo("PARTIALLY VERIFIED");
        assertThat(graph.get("errorCount").asInt()).isZero();
    }

    private static long decodeNation(byte[] window, CapturedContainerLayout layout) {
        return ByteBuffer.wrap(window).order(ByteOrder.LITTLE_ENDIAN).getLong(layout.nationWindowOffset());
    }

    @Test
    void offerVectorHolesPreserveNativeSlotsAndDoNotEndIteration() throws Exception {
        var capture = read("transfer_offer_sparse_window_live_20261021.json").get("returnValues").get(0);
        var occupied = decodeCapturedOfferSlots(capture, 0);
        assertThat(capture.get("unchanged").asBoolean()).isTrue();
        assertThat(capture.get("firstSlot").asInt()).isEqualTo(256);
        assertThat(capture.get("slotCount").asInt()).isEqualTo(128);
        assertThat(occupied).hasSize(126).contains(297, 300, 383).doesNotContain(298, 299);
        assertThat(capture.get("nullSlots").size()).isEqualTo(2);
        // Stopping at the first null or compacting native indices loses later records.
        assertThat(occupied.stream().filter(slot -> slot > 298).count()).isPositive();
        assertThat(occupied.get(42)).isEqualTo(300);
    }

    @Test
    void shiftedOfferPositionSelectsAnotherNativeFieldAndIsRejected() throws Exception {
        var capture = read("transfer_offer_sparse_window_live_20261021.json").get("returnValues").get(0);
        assertThatThrownBy(() -> decodeCapturedOfferSlots(capture, 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("native slot");
    }

    private static ArrayList<Integer> decodeCapturedOfferSlots(JsonNode capture, int positionOffset) {
        var pointers = little(capture.get("rawPointers").asString());
        int first = capture.get("firstSlot").asInt();
        int count = capture.get("slotCount").asInt();
        assertThat(pointers.capacity()).isEqualTo(Math.multiplyExact(count, 8));
        var rows = new java.util.HashMap<Integer, JsonNode>();
        for (var row : capture.get("nonNull")) {
            assertThat(rows.put(row.get("slot").asInt(), row)).isNull();
        }
        var occupied = new ArrayList<Integer>();
        var holes = new ArrayList<Integer>();
        for (int offset = 0; offset < count; offset++) {
            int slot = Math.addExact(first, offset);
            long pointer = pointers.getLong(offset * 8);
            if (pointer == 0) {
                holes.add(slot);
                assertThat(rows).doesNotContainKey(slot);
                continue;
            }
            var row = rows.get(slot);
            assertThat(row).isNotNull();
            assertThat(pointer).isEqualTo(address(row.get("address").asString()));
            if (little(row.get("raw5CThrough67").asString()).getInt(positionOffset) != slot) {
                throw new IllegalArgumentException("Offer position disagrees with native slot " + slot);
            }
            occupied.add(slot);
        }
        var capturedHoles = new ArrayList<Integer>();
        for (var hole : capture.get("nullSlots")) capturedHoles.add(hole.asInt());
        assertThat(holes).containsExactlyElementsOf(capturedHoles);
        assertThat(rows).hasSize(occupied.size());
        return occupied;
    }

    @Test
    void transferManagerGlobalSelectsItsBaseSubobjectAtNativeDisplacement() throws Exception {
        var capture = read("transfer_offer_diverse_team_links_live_20261021.json").get("returnValues").get(0);
        var enclosing = capture.get("enclosingRoot");
        long base = address(capture.get("moduleBase").asString());
        long primary = address(enclosing.get("pointer").asString());
        long adjusted = address(capture.get("root").asString());
        var raw = little(enclosing.get("rawPrefix").asString());
        assertThat(adjusted).isEqualTo(Math.addExact(primary, 0x18));
        assertThat(raw.getLong(0)).isEqualTo(base + 0x4473ee0L);
        assertThat(raw.getLong(0x18)).isEqualTo(base + 0x4473f28L).isNotEqualTo(raw.getLong(0));
        assertThat(enclosing.get("nativeType").get("subobjectOffset").asInt()).isZero();
        assertThat(capture.get("enclosingRootUnchanged").asBoolean()).isTrue();
    }

    @Test
    void offerCommonTeamFieldsRetainTeamIdentityAcrossFiveOfferTypes() throws Exception {
        var capture = read("transfer_offer_diverse_team_links_live_20261021.json").get("returnValues").get(0);
        int nonNull = 0, absent = 0;
        var types = new java.util.HashSet<String>();
        for (var offer : capture.get("offers")) {
            types.add(offer.get("nativeType").get("type").asString());
            var fields = little(offer.get("raw38Through57").asString());
            for (var link : offer.get("teams")) {
                int offset = Integer.parseInt(link.get("fieldOffset").asString(), 16) - 0x38;
                long team = fields.getLong(offset);
                assertThat(team).isEqualTo(address(link.get("address").asString()));
                if (team == 0) {
                    absent++;
                    continue;
                }
                var raw = little(link.get("rawPrefix").asString());
                assertThat(link.get("nativeType").get("type").asString()).isEqualTo(".?AVTEAM@db@@");
                assertThat(raw.getInt(8)).isEqualTo(link.get("index").asInt())
                        .isBetween(0, capture.get("teamCount").asInt() - 1);
                assertThat(raw.getInt(12)).isEqualTo(link.get("uid").asInt());
                assertThat(Byte.toUnsignedInt(raw.get(0x2f))).isEqualTo(link.get("ownerMode").asInt());
                assertThat(raw.getLong(0x30)).isEqualTo(address(link.get("owner").asString()));
                nonNull++;
            }
        }
        assertThat(types).hasSize(5);
        assertThat(nonNull).isEqualTo(40);
        assertThat(absent).isEqualTo(2);
    }

    @Test
    void savedFullOffersDecodeCompleteRecordsWithAndWithoutSeparateTerms() throws Exception {
        var report = read("serialized_transfer_offers_verification_20261021.json");
        assertThat(report.get("actualDecoderInvocationObserved").asBoolean()).isFalse();
        assertThat(report.get("records").size()).isEqualTo(2);
        int expectedStart = 13;
        for (var record : report.get("records")) {
            verifyCapturedFullOffer(record, 0, HexFormat.of().parseHex(record.get("wireHex").asString()));
            assertThat(record.get("fileStart").asInt()).isEqualTo(expectedStart);
            expectedStart = record.get("fileEnd").asInt();
        }
        assertThat(expectedStart).isEqualTo(326);
        assertThat(report.get("records").get(0).get("serializedBytes").asInt()).isEqualTo(166);
        assertThat(report.get("records").get(1).get("serializedBytes").asInt()).isEqualTo(147);
    }

    @Test
    void savedOfferFieldDriftAndTruncatedRecordAreRejected() throws Exception {
        var record = read("serialized_transfer_offers_verification_20261021.json").get("records").get(0);
        byte[] wire = HexFormat.of().parseHex(record.get("wireHex").asString());
        assertThatThrownBy(() -> verifyCapturedFullOffer(record, 4, wire))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("native field");
        byte[] truncated = java.util.Arrays.copyOf(wire, wire.length - 1);
        assertThatThrownBy(() -> verifyCapturedFullOffer(record, 0, truncated))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Truncated");
    }

    private static void verifyCapturedFullOffer(JsonNode record, int positionDrift, byte[] wire) {
        var cursor = new CapturedOfferWireCursor(wire, record);
        cursor.control(1, 2); cursor.control(2, 19);
        cursor.reference(0x38, false); cursor.reference(0x40, true); cursor.reference(0x48, true);
        cursor.field(0x58, 4); cursor.field(0x5c + positionDrift, 4); cursor.field(0x60, 4);
        cursor.field(0x8d, 1); cursor.field(0x64, 4); cursor.field(0x84, 1); cursor.field(0x85, 1);
        cursor.field(0x30, 8); cursor.field(0x86, 1); cursor.field(0x87, 1); cursor.field(0x8b, 1);
        cursor.field(0x68, 4); cursor.field(0x6c, 4); cursor.field(0x88, 1);
        cursor.field(0x78, 4); cursor.field(0x7c, 4); cursor.field(0x80, 4);
        cursor.reference(0x50, true); cursor.field(0x8e, 1); cursor.field(0x89, 1);
        cursor.field(0x74, 4); cursor.field(0x8c, 1); cursor.field(0x70, 4);
        for (int offset : new int[]{0x10, 0x18, 0x20, 0x28}) cursor.field(offset, 8);
        cursor.field(0x8a, 1);
        cursor.control(1, 0); // No exchange-vector rows in these two variants.
        cursor.field(0xc0, 4); cursor.reference(0xb0, false);
        boolean termsPresent = cursor.nativeBytes.getLong(0xa8) != 0;
        cursor.control(1, termsPresent ? 1 : 0);
        if (termsPresent) {
            cursor.control(1, 2);
            var term18 = record.get("term18Rows");
            for (int offset : new int[]{0, 0x18, 0x30, 0x48, 0x60, 0x78, 0x90}) {
                int count = offset == 0x18 && term18 != null ? 2 : 0;
                cursor.control(1, count);
                if (count != 0) cursor.term18Rows(count, little(term18.get("nativeRawHex").asString()));
            }
            cursor.control(4, 0);
            var terms = little(record.get("auxiliaryRawHex").asString());
            cursor.fieldAgainst(terms, 0xc0, 4); cursor.fieldAgainst(terms, 0xc4, 3);
        }
        cursor.field(0xc4, 4); cursor.field(0xc8, 4); cursor.field(0xb8, 8);
        cursor.field(0xcc, 2); cursor.field(0xce, 2);
        assertThat(cursor.wire.remaining()).isZero();
    }

    private static final class CapturedOfferWireCursor {
        private final ByteBuffer wire, nativeBytes;
        private final JsonNode record;

        private CapturedOfferWireCursor(byte[] bytes, JsonNode record) {
            this.wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            this.nativeBytes = little(record.get("nativeRawHex").asString());
            this.record = record;
        }

        private byte[] take(int count) {
            if (wire.remaining() < count) throw new IllegalArgumentException("Truncated FULL_TRANSFER_OFFER record");
            byte[] result = new byte[count]; wire.get(result); return result;
        }

        private void control(int width, long expected) {
            byte[] bytes = take(width); long value = 0;
            for (int i = 0; i < width; i++) value |= Byte.toUnsignedLong(bytes[i]) << (i * 8);
            if (value != expected) throw new IllegalArgumentException("Unsupported selected offer control");
        }

        private void field(int offset, int count) { fieldAgainst(nativeBytes, offset, count); }

        private void fieldAgainst(ByteBuffer reference, int offset, int count) {
            byte[] actual = take(count);
            for (int i = 0; i < count; i++) if (actual[i] != reference.get(offset + i)) {
                throw new IllegalArgumentException("Wire/native field mismatch at " + Integer.toHexString(offset));
            }
        }

        private void reference(int offset, boolean team) {
            if (team) control(1, 0); // Explicit selected indexed-TEAM branch.
            int value = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
            JsonNode expected = null;
            for (var reference : record.get("references")) {
                if (Integer.parseInt(reference.get("nativeOffsetHex").asString(), 16) == offset) expected = reference;
            }
            assertThat(expected).isNotNull();
            assertThat(value).isEqualTo(expected.get("index").asInt());
            assertThat(nativeBytes.getLong(offset)).isEqualTo(address(expected.get("postloadAddress").asString()));
        }

        private void term18Rows(int count, ByteBuffer nativeRows) {
            if (nativeRows.capacity() != Math.multiplyExact(count, 8)) {
                throw new IllegalArgumentException("Unexpected term18 native row size");
            }
            var rows = new ArrayList<CapturedTerm18Row>();
            for (int i = 0; i < count; i++) {
                control(1, 2);
                int value = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
                byte flag = take(1)[0];
                rows.add(new CapturedTerm18Row(value, flag));
            }
            rows.sort(Comparator.comparingInt((CapturedTerm18Row row) -> row.flag())
                    .thenComparing(Comparator.comparingInt(CapturedTerm18Row::value).reversed()));
            for (int i = 0; i < count; i++) {
                if (nativeRows.getInt(i * 8) != rows.get(i).value()
                        || nativeRows.get(i * 8 + 4) != rows.get(i).flag()) {
                    throw new IllegalArgumentException("Meaningful term18 field mismatch");
                }
            }
        }
    }

    private record CapturedTerm18Row(int value, byte flag) { }

    @Test
    void selectedNonemptyTerm18VariantCompletesFourConsecutiveSavedOffers() throws Exception {
        var report = read("serialized_transfer_offers_with_term18_verification_20261021.json");
        assertThat(report.get("records").size()).isEqualTo(4);
        int next = 13;
        for (var record : report.get("records")) {
            assertThat(record.get("fileStart").asInt()).isEqualTo(next);
            verifyCapturedFullOffer(record, 0, HexFormat.of().parseHex(record.get("wireHex").asString()));
            next = record.get("fileEnd").asInt();
        }
        assertThat(next).isEqualTo(651);
        var third = report.get("records").get(2);
        assertThat(third.get("serializedBytes").asInt()).isEqualTo(178);
        var rows = little(third.get("term18Rows").get("nativeRawHex").asString());
        assertThat(rows.getInt(0)).isEqualTo(864206);
        assertThat(rows.getInt(8)).isEqualTo(864206);
        assertThat(rows.get(4)).isEqualTo((byte) 30);
        assertThat(rows.get(12)).isEqualTo((byte) 40);
    }

    @Test
    void term18SignedKeysMatterButOpaqueNativeBytesAreNotSerialized() throws Exception {
        var record = read("serialized_transfer_offers_with_term18_verification_20261021.json").get("records").get(2);
        byte[] allWire = HexFormat.of().parseHex(record.get("wireHex").asString());
        int rowStart = record.get("term18Rows").get("wireRows").get(0).get("valueFileOffset").asInt()
                - 1 - record.get("fileStart").asInt();
        byte[] wireRows = java.util.Arrays.copyOfRange(allWire, rowStart, rowStart + 12);
        byte[] nativeRows = HexFormat.of().parseHex(record.get("term18Rows").get("nativeRawHex").asString());
        for (int offset : new int[]{5, 6, 7, 13, 14, 15}) nativeRows[offset] ^= (byte) 0xa5;
        new CapturedOfferWireCursor(wireRows, record).term18Rows(2,
                ByteBuffer.wrap(nativeRows).order(ByteOrder.LITTLE_ENDIAN));
        nativeRows[0] ^= 1;
        assertThatThrownBy(() -> new CapturedOfferWireCursor(wireRows, record).term18Rows(2,
                ByteBuffer.wrap(nativeRows).order(ByteOrder.LITTLE_ENDIAN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Meaningful term18");

        // Synthetic edge cases exercise the signed SETL/SETG comparator found in native code.
        var syntheticWire = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        syntheticWire.put((byte) 2).putInt(Integer.MIN_VALUE).put((byte) 0);
        syntheticWire.put((byte) 2).putInt(10).put((byte) -1);
        syntheticWire.put((byte) 2).putInt(Integer.MAX_VALUE).put((byte) 0);
        var expected = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        expected.putInt(0, 10).put(4, (byte) -1);
        expected.putInt(8, Integer.MAX_VALUE).put(12, (byte) 0);
        expected.putInt(16, Integer.MIN_VALUE).put(20, (byte) 0);
        new CapturedOfferWireCursor(syntheticWire.array(), record).term18Rows(3, expected);
    }

    @Test
    void observedOfferDecoderConsumesTheCompleteFirstPayloadAndResolvesBeforeCollectionInsertion() throws Exception {
        var capture = read("transfer_load_runtime_20261021.json");
        assertThat(capture.get("active").asBoolean()).isFalse();
        assertThat(capture.get("armed").size()).isZero();
        assertThat(capture.get("reason").asString()).isEqualTo("collection_reader_return");
        var phases = capture.get("records");
        assertThat(phases.size()).isEqualTo(5);
        var entry = phases.get(2);
        var exit = phases.get(3);
        assertThat(entry.get("phase").asString()).isEqualTo("common");
        assertThat(exit.get("phase").asString()).isEqualTo("commonReturn");
        assertThat(exit.get("stackMatches").asBoolean()).isTrue();
        var initial = little(entry.get("offerBytes").asString());
        var decoded = little(exit.get("offerBytes").asString());
        assertThat(initial.getInt(0x5c)).isEqualTo(-1);
        assertThat(initial.getInt(0x60)).isZero();
        assertThat(initial.getLong(0x38)).isZero();
        assertThat(initial.getLong(0xa8)).isZero();
        assertThat(decoded.getInt(0x5c)).isZero();
        assertThat(decoded.getInt(0x60)).isEqualTo(1);
        var before = little(entry.get("streamBytes").asString());
        var after = little(exit.get("streamBytes").asString());
        assertThat(before.getInt(0xc)).isEqualTo(10);
        assertThat(after.getInt(0x10)).isZero();
        assertThat(before.getLong(0x20)).isEqualTo(8);
        assertThat(before.getLong(0x30)).isEqualTo(6);
        assertThat(after.getLong(0x30)).isEqualTo(171);
        var record = read("serialized_transfer_offers_with_term18_verification_20261021.json").get("records").get(0);
        assertThat(after.getLong(0x30) - before.getLong(0x30))
                .isEqualTo(record.get("serializedBytes").asInt() - 1);
        assertThat(after.getLong(0x20) + after.getLong(0x30)).isEqualTo(record.get("fileEnd").asInt());
        var settled = read("full_transfer_offer_postreload_live_20261021.json").get("returnValues").get(0).get("samples").get(0);
        assertThat(decoded.getLong(0x38)).isEqualTo(address(settled.get("person38").get("address").asString()));
        assertThat(settled.get("person38").get("index").asInt()).isEqualTo(44195);
        assertThat(settled.get("person38").get("uid").asInt()).isEqualTo(2000338014);
        for (var field : record.get("fields")) {
            if (!field.get("object").asString().equals("offer")) continue;
            int offset = Integer.parseInt(field.get("offsetHex").asString(), 16);
            byte[] expected = HexFormat.of().parseHex(field.get("bytesHex").asString());
            for (int i = 0; i < expected.length; i++) assertThat(decoded.get(offset + i)).isEqualTo(expected[i]);
        }
    }

    @Test
    void reloadedOfferPointersChangeWhileSelectedPlayerIdentitiesAndNativeRootRemainStable() throws Exception {
        var old = read("full_transfer_offer_wire_targets_live_20261021.json").get("returnValues").get(0);
        var fresh = read("full_transfer_offer_postreload_live_20261021.json").get("returnValues").get(0);
        assertThat(fresh.get("root").asString()).isEqualTo(old.get("root").asString());
        assertThat(fresh.get("headersUnchanged").asBoolean()).isTrue();
        for (int i = 0; i < 4; i++) {
            var previous = old.get("samples").get(i);
            var current = fresh.get("samples").get(i);
            assertThat(current.get("address").asString()).isNotEqualTo(previous.get("address").asString());
            assertThat(current.get("person38").get("index").asInt()).isEqualTo(previous.get("person38").get("index").asInt());
            assertThat(current.get("person38").get("uid").asInt()).isEqualTo(previous.get("person38").get("uid").asInt());
        }
        var phases = read("transfer_load_runtime_20261021.json").get("records");
        for (var phase : phases) {
            assertThat(phase.get("global_4E49CAA").asInt()).isZero();
            assertThat(phase.get("global_4E49CAB").asInt()).isZero();
            assertThat(phase.get("uiD8").asInt()).isEqualTo(257);
        }
        assertThat(phases.get(4).get("spanSlots").asInt()).isEqualTo(12121);
        // The active load has resolved data while UI busy remains set; this is not a readiness test.
    }

    @Test
    void nativeClauseTranslationIdsSelectIndependentlyDecodedEnglishTemplates() throws Exception {
        var live = read("clause_localization_offsets_live_20261021.json").get("returnValues").get(0);
        var decoded = read("clause_localization_semantics_20261021.json");
        assertThat(live.get("headersUnchanged").asBoolean()).isTrue();
        // Historical fixture name: this is an opaque secondary lookup selector, not a proven language ID.
        assertThat(live.get("language").asInt()).isEqualTo(1);
        assertThat(decoded.get("records").size()).isEqualTo(7);
        for (var record : decoded.get("records")) {
            JsonNode nativeEntry = null;
            for (var candidate : live.get("entries")) {
                if (candidate.get("id").asInt() == record.get("id").asInt()) nativeEntry = candidate;
            }
            assertThat(nativeEntry).isNotNull();
            var row = little(nativeEntry.get("tableRaw").asString());
            var nativeBytes = little(nativeEntry.get("recordRaw").asString());
            assertThat(row.getInt(0)).isEqualTo(record.get("id").asInt());
            assertThat(row.getInt(4)).isEqualTo(1);
            assertThat(nativeBytes.getLong(0)).isEqualTo(nativeBytes.getLong(8));
            assertThat(nativeBytes.get(0x1f)).isEqualTo((byte) 1);
            assertThat(nativeBytes.getInt(0x18) + 8).isEqualTo(record.get("memberOffset").asInt());
            var wire = little(record.get("wireHex").asString());
            assertThat(wire.get()).isEqualTo((byte) 1);
            int length = wire.getInt();
            assertThat(length).isEqualTo(wire.remaining()).isLessThanOrEqualTo(4096);
            byte[] text = new byte[length]; wire.get(text);
            assertThat(new String(text, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(record.get("text").asString());
            assertThat(record.get("text").asString()).startsWith("[%cash#1-long-roundlarge]");
        }
        var byId = new java.util.HashMap<String, String>();
        for (var record : decoded.get("records")) byId.put(record.get("idHex").asString(), record.get("text").asString());
        assertThat(byId.get("3FB00")).isEqualTo("[%cash#1-long-roundlarge] after [%number#1] league appearances");
        assertThat(byId.get("3FB01")).isEqualTo("[%cash#1-long-roundlarge] fee per league appearance (max [%number#1])");
        assertThat(byId.get("3FB02")).isEqualTo("[%cash#1-long-roundlarge] after [%number#1] international appearances");
        assertThat(byId.get("3FB03")).isEqualTo("[%cash#1-long-roundlarge] after [%number#1] league goals");
    }

    @Test
    void serializedTranslationIndexSeparatesPackedSelectorAndOffsetFlagFromIdentityAndPosition() throws Exception {
        var index = read("english_translation_index_20261021.json");
        assertThat(index.get("indexAt").asInt()).isEqualTo(21146536);
        assertThat(index.get("count").asInt()).isEqualTo(190530);
        assertThat(index.get("selectedRows").size()).isEqualTo(7);
        for (var row : index.get("selectedRows")) {
            var file = little(row.get("indexWireHex").asString());
            var nativeRow = little(row.get("liveTableRow").asString());
            assertThat(file.getInt(0) & 0x0fffffff).isEqualTo(nativeRow.getInt(0));
            assertThat(file.getInt(0) >>> 28).isEqualTo(nativeRow.getInt(4)).isEqualTo(1);
            assertThat((file.getInt(4) & 0x7fffffff) + 8).isEqualTo(row.get("memberOffset").asInt());
        }
        var control = read("translation_packed_offset_flag_live_20261021.json").get("returnValues").get(0);
        var nativeRecord = little(control.get("recordRaw").asString());
        assertThat(nativeRecord.getInt(0x18)).isEqualTo(377);
        assertThat(nativeRecord.get(0x1e)).isEqualTo((byte) 1);
        assertThat(nativeRecord.get(0x1f)).isEqualTo((byte) 1);
        // Independently extracted on-disk row ordinal23: flagged offset80000179.
        var disk = little(index.get("packedFlagControlRow").get("indexWireHex").asString());
        assertThat(disk.getInt(4) & 0x7fffffff).isEqualTo(nativeRecord.getInt(0x18));
        assertThat(disk.getInt(4) >>> 31).isEqualTo(Byte.toUnsignedInt(nativeRecord.get(0x1e)));
        assertThat(Integer.toUnsignedLong(disk.getInt(4))).isGreaterThan(2_000_000_000L);
    }

    private static ByteBuffer little(String text) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(text)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static long address(String value) { return Long.parseUnsignedLong(value, 16); }

    private static JsonNode read(String name) throws IOException {
        try (var input = Files.newInputStream(EVIDENCE.resolve(name))) {
            byte[] bytes = input.readNBytes(1048577);
            if (bytes.length > 1048576) throw new IOException("Evidence exceeds JSON size bound");
            return JsonMapper.builder().build().readTree(bytes);
        }
    }

    private record CapturedContainerLayout(int nationWindowOffset) { }
    /** Research fixture layout only: no production extraction offsets are introduced. */
    private record CapturedNonPlayingLayout(int memberRel, long memberVtableRva) { }
    private record CapturedAttributeHistoryLayout(int historyRel, int snapshotSize) { }
    private record CapturedSavesLoadedLayout(int counterWithinC0Block) { }
    private record CapturedGameUiLayout(int allocationSize, int secondaryRel, int tailRel) { }
    private record CapturedManagerRootLayout(int pointerWithinWindow) { }
    private record CapturedHumanViewLayout(int displacementRel, int personRel) { }
    private record CapturedHumanPayloadLayout(int fileFieldsStart, int nativeShift) { }
}

