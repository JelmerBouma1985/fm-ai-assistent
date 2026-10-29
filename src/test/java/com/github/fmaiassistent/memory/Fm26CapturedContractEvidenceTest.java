package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Independent structural checks for the native decoder capture and bounded linked records.
 * This does not validate currency conversion or production extraction against a running game.
 */
class Fm26CapturedContractEvidenceTest {
    private static final Path EVIDENCE = Path.of("docs/reverse-engineering/fm26/evidence");
    private static final String MEMBER_SHA256 =
            "32078DC5B6E5A7E433B51E9780A9412FE496C83084E4646E270DF6349DE700F0";

    @Test
    void nativeReadConsumesExactlyTheSaveRecordAndMatchesReturnedObject() throws Exception {
        var fixture = fixture();
        var result = verify(fixture, ContractLayout.captured());
        assertThat(result.consumed()).isEqualTo(163);
        assertThat(result.rowCount()).isEqualTo(5);
        assertThat(result.rows()).isEqualTo(hex(fixture.returned().get("vector68Bytes").asString()));
        assertThat(result.rows()[6]).isEqualTo((byte) 0x20);
        assertThat(little(result.rows()).getShort(4)).isEqualTo((short) -1);
        assertThat(little(result.rows()).getInt(32)).isEqualTo(-1);
        assertThat(Byte.toUnsignedInt(result.rows()[38])).isEqualTo(0x16);
    }

    @Test
    void capturedRegistryPublicationUsesTheSameContractAndPerson() throws Exception {
        var capture = fixture().capture();
        var reader = phase(capture, "reader");
        var store = phase(capture, "store");
        var finish = phase(capture, "finish");
        var contract = phase(capture, "contract");
        var returned = phase(capture, "contractReturn");
        assertThat(contract.get("contract").asString()).isEqualTo(returned.get("contract").asString())
                .isEqualTo(finish.get("linkedContract").asString());
        assertThat(reader.get("entity").asString()).isEqualTo(store.get("entity").asString())
                .isEqualTo(finish.get("published").asString());
        assertThat(store.get("previous").asString()).isEqualTo("0");
        assertThat(store.get("destination").asString()).isEqualTo(finish.get("slot").asString());
        assertThat(returned.get("returnValue").asInt()).isEqualTo(1);
        assertThat(finish.get("returnValue").asInt()).isEqualTo(1);
        byte[] person = hex(finish.get("entityBytes").asString());
        assertThat(little(person).getInt(8)).isEqualTo(1417);
        assertThat(little(person).getInt(12)).isEqualTo(222138);
        assertThat(finish.get("variant").asString()).isEqualTo("ACTUAL_PLAYER_AND_NON_PLAYER");
    }

    @Test
    void shiftedObjectLayoutFailsAgainstIndependentRawBytes() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> verify(fixture, new ContractLayout(1)))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsExcessiveSerializedCountBeforeReadingOrAllocatingRows() throws Exception {
        var fixture = fixture();
        byte[] raw = fixture.raw().clone();
        raw[28] = (byte) 0xff;
        assertThatThrownBy(() -> verify(new Fixture(fixture.capture(), fixture.returned(), raw),
                ContractLayout.captured())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row count");
    }

    @Test
    void rejectsTruncatedInputAndUnsupportedNonemptyBranches() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> verify(new Fixture(fixture.capture(), fixture.returned(),
                Arrays.copyOf(fixture.raw(), 162)), ContractLayout.captured()))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] raw = fixture.raw().clone();
        raw[69] = 1;
        assertThatThrownBy(() -> verify(new Fixture(fixture.capture(), fixture.returned(), raw),
                ContractLayout.captured())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported branch");
    }

    @Test
    void auxiliaryRowsLinkToIndependentlyCapturedAwardsIncludingValidIndexZero() throws Exception {
        var players = read("contract_award_rows_live_20261020.json").get("returnValues").get(0).get("players");
        var entities = read("contract_award_entities_named_live_20261020.json")
                .get("returnValues").get(0).get("rows");
        int[] expectedCounts = {2, 3, 0};
        int[] expectedIds = {2000180166, 150937, 102407, 102408, 102406};
        int[] expectedAmounts = {74543, 67696, 190421, 152584, 190421};
        String[] expectedNames = {"UEFA Champions League Player of the Season",
                "UEFA Champions League Dream Team", "World Footballer of the Year",
                "World Player of the Year", "World Golden Ball"};
        int entityIndex = 0;
        for (int playerIndex = 0; playerIndex < players.size(); playerIndex++) {
            var player = players.get(playerIndex);
            int count = player.get("count").asInt();
            assertThat(count).isEqualTo(expectedCounts[playerIndex]);
            byte[] rows = hex(player.get("records").asString());
            assertThat(rows).hasSize(count * 16);
            if (count != 0) {
                var header = little(hex(player.get("header").asString()));
                assertThat(header.getLong(8) - header.getLong(0)).isEqualTo(count * 16L);
                assertThat(header.getLong(16)).isGreaterThanOrEqualTo(header.getLong(8));
            }
            for (int row = 0; row < count; row++, entityIndex++) {
                var entity = entities.get(entityIndex);
                assertThat(entity.get("index").asInt()).isEqualTo(player.get("index").asInt());
                assertThat(entity.get("row").asInt()).isEqualTo(row);
                assertThat(little(rows).getLong(row * 16))
                        .isEqualTo(Long.parseUnsignedLong(entity.get("entity").asString(), 16));
                assertThat(entity.get("rtti").asString()).isEqualTo(".?AVAWARD@db@@");
                assertThat(little(hex(entity.get("bytes").asString())).getInt(12))
                        .isEqualTo(expectedIds[entityIndex]);
                assertThat(little(rows).getInt(row * 16 + 8)).isEqualTo(expectedAmounts[entityIndex]);
                var fullName = entity.get("names").get(0);
                assertThat(fullName.get("offset").asString()).isEqualTo("30");
                byte[] text = hex(fullName.get("bytes").asString());
                assertThat(little(text).getInt(0)).isEqualTo(text.length - 4);
                assertThat(new String(text, 4, text.length - 4, java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo(expectedNames[entityIndex]);
            }
        }
        assertThat(entityIndex).isEqualTo(5);
        assertThat(little(hex(entities.get(4).get("bytes").asString())).getInt(8)).isZero();
    }

    @Test
    void competitionBonusUsesThirtyTwoByteRowAndSeparateCompetitionRegistry() throws Exception {
        var player = read("contract_competition_named_live_20261020.json")
                .get("returnValues").get(0).get("players").get(0);
        assertThat(player.get("uid").asInt()).isEqualTo(2002070983);
        byte[] row = hex(player.get("records").asString());
        assertThat(row).hasSize(32);
        var data = little(row);
        assertThat(data.getInt(0x14)).isEqualTo(637);
        assertThat(data.getInt(0x18)).isEqualTo(108779);
        var competition = player.get("references").get(0);
        assertThat(competition.get("rtti").asString()).isEqualTo(".?AVCOMP@db@@");
        assertThat(little(hex(competition.get("bytes").asString())).getInt(8)).isEqualTo(637);
        assertThat(little(hex(competition.get("bytes").asString())).getInt(12)).isEqualTo(1301394);
        var name = competition.get("names").get(0);
        assertThat(name.get("offset").asString()).isEqualTo("40");
        byte[] text = hex(name.get("bytes").asString());
        assertThat(little(text).getInt(0)).isEqualTo(text.length - 4);
        assertThat(new String(text, 4, text.length - 4, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("UEFA Champions League");
    }

    @Test
    void currentAwardRowsMatchIndependentCeIdentityCaptureAndProductionRawSnapshot() throws Exception {
        var independent = read("contract_award_references_live_20261023.json").get("returnValues").get(0);
        var production = read("contract_pipeline_selected_direct_live_20261023.json").get("rows");
        tools.jackson.databind.JsonNode matched = null;
        for (var player : production) if (player.get("unique_id").asLong() == independent.get("uid").asLong()) matched = player;
        assertThat(matched).isNotNull();
        var terms = matched.get("_contracts").get(0).get("terms");
        int[] awardUids = {150936,150937}; int[] rawAmounts = {282558,217149};
        int row = 0;
        for (var term : terms) {
            if (!term.get("group").asString().equals("FULL_98")) continue;
            var nativeRow = independent.get("rows").get(row);
            assertThat(hex(term.get("rawHex").asString())).isEqualTo(hex(nativeRow.get("raw").asString()));
            assertThat(nativeRow.get("typeName").asString()).isEqualTo(".?AVAWARD@db@@");
            assertThat(little(hex(nativeRow.get("prefix").asString())).getInt(12)).isEqualTo(awardUids[row]);
            assertThat(little(hex(term.get("rawHex").asString())).getInt(8)).isEqualTo(rawAmounts[row]);
            assertThat(little(hex(term.get("rawHex").asString())).getLong())
                    .isEqualTo(Long.parseUnsignedLong(nativeRow.get("target").asString(),16));
            row++;
        }
        assertThat(row).isEqualTo(2);
        assertThat(independent.get("unchanged").asBoolean()).isTrue();
    }

    @Test
    void historicalHeaderBytesPreserveAllPointerDigits() throws Exception {
        var header = read("ce_live_contract_auxiliary_vectors_20261019.json")
                .get("players").get(0).get("vectors").get("+0x98");
        var bytes = little(hex(header.get("header").asString()));
        assertThat(bytes.getLong(0)).isEqualTo(0x2a0faa957b0L)
                .isNotEqualTo(Long.decode(header.get("begin").asString()));
        assertThat(bytes.getLong(8)).isEqualTo(0x2a0faa957d0L);
        assertThat((bytes.getLong(8) - bytes.getLong(0)) / 16).isEqualTo(2);
    }

    @Test
    void teamOwnerFlagSelectsClubOrNationalContainerWithDifferentStableUid() throws Exception {
        var capture=read("contract_team_owner_branches_live_20261023.json").get("returnValues").get(0);
        assertThat(capture.get("unchanged").asBoolean()).isTrue();
        assertThat(capture.get("rows").size()).isEqualTo(2);
        long[] expected={1744L,765L};int index=0;
        for(var row:capture.get("rows")) {
            var team=little(hex(row.get("team").get("raw").asString()));
            var owner=little(hex(row.get("owner").get("raw").asString()));
            assertThat(Byte.toUnsignedInt(team.get(0x2F))).isEqualTo(index);
            assertThat(team.getLong(0x30)).isEqualTo(Long.parseUnsignedLong(row.get("owner").get("address").asString(),16));
            assertThat(Integer.toUnsignedLong(owner.getInt(12))).isEqualTo(expected[index]);
            assertThat(row.get("owner").get("typeName").asString()).isEqualTo(index==0?".?AVCLUB@db@@":".?AVNATIONAL_TEAM_CONTAINER@db@@");
            if(index==1)assertThat(team.getInt(12)).isEqualTo(145174).isNotEqualTo(owner.getInt(12));
            index++;
        }
    }

    @Test
    void bClubRawPlayingTimeDiffersFromTheProvenPrimaryDelegationPath() throws Exception {
        var capture=read("bclub_contract_graphs_link_bytes_live_20261023.json").get("returnValues").get(0);
        assertThat(capture.get("unchanged").asBoolean()).isTrue();
        int[] expected={20,14}; int index=0;
        for(var row:capture.get("selected")) {
            var bclub=little(hex(row.get("core").asString()));
            var full=little(hex(row.get("primaryCore").asString()));
            assertThat(bclub.capacity()).isEqualTo(104);assertThat(full.capacity()).isEqualTo(200);
            assertThat(bclub.getLong(8)).isEqualTo(full.getLong(8));
            assertThat(Byte.toUnsignedInt(bclub.get(0x54))).isEqualTo(14);
            assertThat(Byte.toUnsignedInt(full.get(0x54))).isEqualTo(expected[index]);
            var link=little(hex(row.get("links").get(0).get("raw").asString()));
            assertThat(link.getLong(8)).isEqualTo(Long.parseUnsignedLong(row.get("club").get("address").asString(),16));
            assertThat(link.getLong()).isEqualTo(Long.parseUnsignedLong(row.get("relatedClub").get("address").asString(),16));
            assertThat(link.get(0x30)).isEqualTo((byte)4);
            assertThat((0x1001F0>>>Byte.toUnsignedInt(link.get(0x30)))&1).isEqualTo(1);
            assertThat(row.get("primaryClub").get("address").asString()).isEqualTo(row.get("requestedClub").get("address").asString());
            assertThat(row.get("relatedClub").get("typeName").asString()).isEqualTo(".?AVCLUB@db@@");
            assertThat(row.get("prediction").get("nativeInvoked").asBoolean()).isFalse();
            assertThat(row.get("prediction").get("code").asInt()).isEqualTo(expected[index++]);
        }
        assertThat(index).isEqualTo(2);
    }

    @Test
    void distinctPlayerThunksResolveTheSamePersonRelativeContractView() throws Exception {
        var capture=read("person_contract_views_link_bytes_live_20261023.json").get("returnValues").get(0);
        assertThat(capture.get("rows").size()).isEqualTo(2);
        assertThat(capture.get("unchanged").asBoolean()).isTrue();
        int[] viewRels={-0x160,-0x258}; int[] vbOffsets={0x2A8,0x3A0};
        int count=0;
        for(var row:capture.get("rows")) {
            long person=Long.parseUnsignedLong(row.get("person").get("address").asString(),16);
            int delta=little(hex(row.get("deltaRaw").asString())).getInt();
            int offset=little(hex(row.get("vbOffsetRaw").asString())).getInt();
            long view=person-delta+offset+viewRels[count];
            assertThat(offset).isEqualTo(vbOffsets[count]);
            assertThat(view).isEqualTo(person+0x148).isEqualTo(Long.parseUnsignedLong(row.get("view").asString(),16));
            if(count==1)assertThat(person-delta+offset+viewRels[0]).isNotEqualTo(view);
            var links=little(hex(row.get("viewLinksRaw").asString()));
            for(var contract:row.get("contracts")) {
                var core=little(hex(contract.get("core").asString()));
                assertThat(core.getLong(8)).isEqualTo(person);
                long address=Long.parseUnsignedLong(contract.get("address").asString(),16);
                if(contract.get("slot").asInt()==-1)assertThat(links.getLong()).isEqualTo(address);
                else assertThat(little(hex(row.get("additionalRaw").asString())).getLong(contract.get("slot").asInt()*8)).isEqualTo(address);
                var owner=little(hex(contract.get("team").get("ownerLinkRaw").asString()));
                assertThat(owner.get(0)).isZero();
                assertThat(owner.getLong(1)).isEqualTo(Long.parseUnsignedLong(contract.get("club").get("address").asString(),16));
            }
            count++;
        }
    }

    @Test
    void contractVirtualTypeCodesComeFromSixConstantGetters() throws Exception {
        var rows=read("contract_type_getters_live_20261023.json").get("returnValues").get(0).get("rows");
        var expected=java.util.Map.of("FULL_CONTRACT",0,"LOAN_CONTRACT",1,"TRIAL_CONTRACT",2,
                "B_CLUB_CONTRACT",3,"YOUTH_TRIAL_CONTRACT",4,"BASIC_CONTRACT",5);
        assertThat(rows.size()).isEqualTo(6);
        for(var row:rows) {
            byte[] code=hex(row.get("entry").asString());
            int value;
            if(code[0]==0x31){assertThat(code[1]).isEqualTo((byte)0xC0);assertThat(code[2]).isEqualTo((byte)0xC3);value=0;}
            else{assertThat(code[0]).isEqualTo((byte)0xB0);assertThat(code[2]).isEqualTo((byte)0xC3);value=Byte.toUnsignedInt(code[1]);}
            assertThat(value).isEqualTo(expected.get(row.get("retainedClass").asString())).isEqualTo(row.get("constantByteReturn").asInt());
        }
    }

    @Test
    void competitionBonusHasDistinctCompetitionContextsAndQualificationCondition() throws Exception {
        var sample=read("competition_bonus_contexts_live_20261023.json").get("returnValues").get(0);
        var raw=little(hex(sample.get("rowRaw").asString()));
        assertThat(raw.capacity()).isEqualTo(32);
        assertThat(raw.getInt(4)).isEqualTo(637);
        assertThat(raw.getInt(0x14)).isEqualTo(22);
        assertThat(raw.get(0x10)).isEqualTo((byte)10);
        assertThat(raw.getInt(0x18)).isEqualTo(35072);
        assertThat(raw.getShort(0x1C)).isEqualTo((short)-1);
        long[] uids={1301394L,32L}; int[] offsets={4,0x14};
        for(int i=0;i<2;i++) {
            var ref=sample.get("competitionReferences").get(i);
            assertThat(ref.get("typeName").asString()).isEqualTo(".?AVCOMP@db@@");
            var prefix=little(hex(ref.get("prefix").asString()));
            assertThat(prefix.getInt(8)).isEqualTo(raw.getInt(offsets[i]));
            assertThat(Integer.toUnsignedLong(prefix.getInt(12))).isEqualTo(uids[i]);
        }
        var templates=read("competition_bonus_condition_templates_20261023.json").get("labels");
        String label=null;
        for(var entry:templates)if(entry.get("idHex").asString().equals("5b83b"))label=entry.get("variants").get(0).asString();
        assertThat(label).isEqualTo("Qualifying for the [%comp#2-short]");
        assertThat(sample.get("unchanged").asBoolean()).isTrue();
    }

    @Test
    void registryPublishesSerializedReferencesBeforeContractFixupConvertsThem() throws Exception {
        var capture = read("contract_fixup_capture_20261020.json").get("returnValues").get(0);
        assertThat(capture.get("active").asBoolean()).isFalse();
        assertThat(capture.get("count").asInt()).isEqualTo(8);
        assertThat(capture.get("armed").size()).isZero();
        var decoded = phase(capture, "contractReturn");
        var published = phase(capture, "finish");
        var before = phase(capture, "fixup");
        var after = phase(capture, "fixupReturn");
        assertThat(decoded.get("sequence").asInt()).isLessThan(published.get("sequence").asInt());
        assertThat(published.get("sequence").asInt()).isLessThan(before.get("sequence").asInt());
        assertThat(before.get("sequence").asInt()).isLessThan(after.get("sequence").asInt());
        assertThat(decoded.get("contract").asString()).isEqualTo(published.get("linkedContract").asString())
                .isEqualTo(before.get("contract").asString()).isEqualTo(after.get("contract").asString());
        byte[] raw = hex(before.get("contractBytes").asString());
        byte[] fixed = hex(after.get("contractBytes").asString());
        assertThat(raw).hasSize(0xc8).isEqualTo(hex(decoded.get("contractBytes").asString()));
        assertThat(fixed).hasSize(0xc8);
        assertThat(little(raw).getLong(8)).isEqualTo(1417);
        assertThat(little(raw).getLong(0x10)).isEqualTo(2780);
        assertThat(little(raw).getLong(0xa0)).isEqualTo(-1);
        assertThat(little(fixed).getLong(8))
                .isEqualTo(Long.parseUnsignedLong(after.get("linkedPerson").asString(), 16));
        assertThat(little(fixed).getLong(0x10)).isEqualTo(little(fixed).getLong(0x18)).isPositive();
        assertThat(little(fixed).getLong(0xa0)).isZero();
        for (int offset = 0; offset < raw.length; offset++) {
            if ((offset < 8 || offset >= 0x20) && (offset < 0xa0 || offset >= 0xa8)) {
                assertThat(fixed[offset]).as("unchanged contract byte +0x%x", offset).isEqualTo(raw[offset]);
            }
        }
        assertThat(published.get("flag49cab").asInt()).isEqualTo(1);
        assertThat(before.get("flag49cab").asInt()).isZero();
        assertThat(after.get("returnValue").asInt()).isEqualTo(1);
        assertThat(after.get("personId").asInt()).isEqualTo(222138);
        assertThat(streamPosition(decoded) - streamPosition(phase(capture, "contract"))).isEqualTo(163);
    }

    @Test
    void loadBusyStateSpansBothNativePassesAndReloadInvalidatesHeapAddresses() throws Exception {
        var capture = read("load_gate_capture_20261020.json").get("returnValues").get(0);
        var cleanup = read("load_gate_cleanup_20261020.json").get("read").get("returnValues").get(0);
        var phases = List.of("start", "started", "loader", "people", "first", "firstReturn",
                "second", "secondReturn", "clear", "cleared");
        assertThat(capture.get("records").size()).isEqualTo(phases.size());
        for (int index = 0; index < phases.size(); index++) {
            var row = capture.get("records").get(index);
            assertThat(row.get("phase").asString()).isEqualTo(phases.get(index));
            assertThat(row.get("sequence").asInt()).isEqualTo(index + 1);
            assertThat(row.get("uiByteD8").asInt()).isEqualTo(index == 0 || index == 9 ? 0 : 1);
        }
        var before = phase(capture, "first");
        var fixed = phase(capture, "firstReturn");
        var completed = phase(capture, "cleared");
        assertThat(phase(capture, "people").get("peopleCount").asInt()).isZero();
        assertThat(before.get("peopleCount").asInt()).isEqualTo(151577);
        assertThat(before.get("flag49cab").asInt()).isZero();
        assertThat(little(hex(before.get("contractBytes").asString())).getLong(8)).isEqualTo(1417);
        assertThat(little(hex(fixed.get("contractBytes").asString())).getLong(8))
                .isEqualTo(Long.parseUnsignedLong(fixed.get("samplePerson").asString(), 16));
        assertThat(fixed.get("sampleContract").asString()).isEqualTo(completed.get("sampleContract").asString());
        assertThat(fixed.get("contractBytes").asString()).isEqualTo(completed.get("contractBytes").asString());
        assertThat(phase(capture, "start").get("sampleUid").asInt()).isEqualTo(222138);
        assertThat(completed.get("sampleUid").asInt()).isEqualTo(222138);
        assertThat(phase(capture, "start").get("samplePerson").asString())
                .isNotEqualTo(completed.get("samplePerson").asString());
        assertThat(phase(capture, "start").get("sampleContract").asString())
                .isNotEqualTo(completed.get("sampleContract").asString());
        assertThat(completed.get("uiWordD8").asInt()).isZero();
        assertThat(completed.get("loadResult").asInt()).isZero();
        assertThat(capture.get("active").asBoolean()).isFalse();
        assertThat(cleanup.get("debugging").asBoolean()).isFalse();
        assertThat(cleanup.get("owner").asString()).isEqualTo(completed.get("samplePerson").asString());
    }

    @Test
    void additionalContractSlotsHoldDistinctLoanAndBClubObjectsForTheSamePerson() throws Exception {
        var capture = read("player_contract_type_inventory_live_20261020.json").get("returnValues").get(0);
        long base = Long.parseUnsignedLong(capture.get("moduleBase").asString(), 16);
        assertThat(capture.get("primaryNonNull").asInt()).isEqualTo(43157);
        assertThat(capture.get("primaryNull").asInt()).isEqualTo(1310);
        assertThat(capture.get("additionalArrayNonNull").asInt()).isEqualTo(3763);
        assertThat(capture.get("additionalContracts").asInt()).isEqualTo(3926);
        assertThat(capture.get("locations").get("additional0:45A1268").asInt()).isEqualTo(1343);
        assertThat(capture.get("locations").get("additional1:4725B38").asInt()).isEqualTo(2583);
        for (var row : capture.get("examples")) {
            var prefix = little(hex(row.get("rawPrefix").asString()));
            long person = Long.parseUnsignedLong(row.get("person").asString(), 16);
            assertThat(prefix.getLong(0) - base)
                    .isEqualTo(Long.parseUnsignedLong(row.get("vptrRva").asString(), 16));
            assertThat(prefix.getLong(8)).isEqualTo(person);
            String location = row.get("location").asString();
            if (location.startsWith("additional")) {
                int slot = Integer.parseInt(location.substring("additional".length()));
                byte[] array = hex(row.get("arrayBytes").asString());
                assertThat(array).hasSize(32);
                assertThat(little(array).getLong(slot * 8))
                        .isEqualTo(Long.parseUnsignedLong(row.get("contract").asString(), 16));
                assertThat(row.get("rtti").asString()).isEqualTo(slot == 0
                        ? ".?AVLOAN_CONTRACT@db@@" : ".?AVB_CLUB_CONTRACT@db@@");
            }
        }
        assertThat(capture.get("errorCount").asInt()).isZero();
    }

    @Test
    void loanAuxiliaryTermsUseTheirOwnVectorsAndPreserveUninterpretedBytes() throws Exception {
        var capture = read("loan_auxiliary_vectors_live_20261020.json").get("returnValues").get(0);
        assertThat(capture.get("loanCount").asInt()).isEqualTo(1343);
        assertThat(capture.get("auxiliaryCount").asInt()).isEqualTo(419);
        assertThat(capture.get("vectors").get("0").get("totalRows").asInt()).isEqualTo(393);
        assertThat(capture.get("vectors").get("18").get("totalRows").asInt()).isEqualTo(1);
        for (var row : capture.get("examples")) {
            byte[] auxiliary = hex(row.get("auxiliaryBytes").asString());
            byte[] records = hex(row.get("rows").asString());
            int offset = Integer.parseInt(row.get("offset").asString(), 16);
            int stride = row.get("stride").asInt();
            var header = little(hex(row.get("header").asString()));
            assertThat(auxiliary).hasSize(200);
            assertThat(header.getLong(0)).isEqualTo(little(auxiliary).getLong(offset));
            assertThat(header.getLong(8) - header.getLong(0)).isEqualTo(records.length);
            assertThat(records).hasSize(row.get("count").asInt() * stride);
            if (offset == 0) {
                assertThat(stride).isEqualTo(12);
                assertThat(Byte.toUnsignedInt(records[4])).isEqualTo(12);
                assertThat(Byte.toUnsignedInt(records[8])).isEqualTo(6);
                // Native reader supplies one meaningful byte; captured high bytes are not a code.
                assertThat(little(records).getInt(4)).isNotEqualTo(Byte.toUnsignedInt(records[4]));
            } else {
                assertThat(offset).isEqualTo(0x18);
                assertThat(stride).isEqualTo(8);
                assertThat(row.get("uid").asInt()).isEqualTo(2000091868);
                assertThat(little(records).getInt(0)).isEqualTo(162477);
                assertThat(Byte.toUnsignedInt(records[4])).isEqualTo(50);
            }
        }
    }

    @Test
    void loanPackedPropertyLookupRejectsNegativeIndexAndRetainsFullWidthValues() throws Exception {
        var capture = read("loan_identity_and_format_tables_live_20261020.json")
                .get("returnValues").get(0);
        assertThat(capture.get("formatWord").asInt()).isEqualTo(0xe95);
        var positions = little(hex(capture.get("lookup45A14DC").asString()));
        var roles = little(hex(capture.get("lookup469A678").asString()));
        assertThat(positions.capacity()).isEqualTo(27 * 4);
        assertThat(roles.capacity()).isEqualTo(51 * 8);
        int[] expectedPacked = {0x0cb0, 0x0cb1};
        int[] expectedLow = {-16, -15};
        int[] expectedPositionMask = {0, 1};
        long[] expectedValues = {0x107, 6};
        for (int i = 0; i < 2; i++) {
            var loan = little(hex(capture.get("people").get(i).get("loanBytes").asString()));
            int packed = Short.toUnsignedInt(loan.getShort(0xe4));
            assertThat(packed).isEqualTo(expectedPacked[i]);
            // Native sign extension of low six bits: negative indexes must not reach the table.
            int low = (short) (packed << 10) >> 10;
            assertThat(low).isEqualTo(expectedLow[i]);
            int index = low + 15;
            int mask = index >= 0 && index < 27 ? positions.getInt(index * 4) : 0;
            assertThat(mask).isEqualTo(expectedPositionMask[i]);
            int roleIndex = ((short) (packed << 3) >> 9) + 31;
            assertThat(roleIndex).isEqualTo(81);
            long roleMask = roleIndex >= 0 && roleIndex <= 50 ? roles.getLong(roleIndex * 8) : 0;
            assertThat(roleMask).isZero();
            // Clfl copies a 64-bit value; these retained values are not followable heap pointers.
            assertThat(loan.getLong(0xa8)).isEqualTo(expectedValues[i]);
        }
    }

    @Test
    void loanScreensMatchDatesAndCombinedFeeInputsWithoutWideningSerializedBytes() throws Exception {
        String[] names = {"ahoussou", "mamour"};
        int[] expectedDays = {41, 40};
        int[] expectedBase = {2_680_789, 2_414_425};
        int[] expectedCombined = {5_117_869, 4_426_445};
        int[] expectedUnused = {7670, 0};
        for (int i = 0; i < names.length; i++) {
            var captured = read(names[i] + "_loan_screen_live_20261021.json").get("returnValues").get(0);
            var loan = little(hex(captured.get("loanBytes").asString()));
            var now = little(hex(captured.get("gameDateRaw").asString()));
            assertThat(Short.toUnsignedInt(loan.getShort(0x4a))).isEqualTo(2036);
            int endDay = Short.toUnsignedInt(loan.getShort(0x48)) & 0x1ff;
            int currentDay = Short.toUnsignedInt(now.getShort(0)) & 0x1ff;
            assertThat(endDay - currentDay).isEqualTo(expectedDays[i]);
            assertThat(loan.getInt(0xbc)).isZero();
            assertThat(loan.getInt(0xc0)).isEqualTo(expectedUnused[i]);
            int base = loan.getInt(0xb0);
            assertThat(base).isEqualTo(expectedBase[i]);
            var rows = little(hex(captured.get("vectors").get(0).get("bytes").asString()));
            assertThat(rows.capacity()).isEqualTo(12);
            assertThat(rows.get(4)).isEqualTo((byte) 12);
            assertThat(rows.get(8)).isEqualTo((byte) 6);
            // Only the low byte at+4 controls inclusion in native Clff; remaining bytes are opaque.
            assertThat(base + (rows.get(4) > 0 && rows.getInt(0) > 0 ? rows.getInt(0) : 0))
                    .isEqualTo(expectedCombined[i]);
        }
    }

    @Test
    void mandatoryLoanFeeUsesInstallmentRowsWithoutAddingTheSeparateAppearanceThresholdRow() throws Exception {
        var captured = read("joao_loan_ui_live_20261021.json").get("returnValues").get(0);
        var report = read("joao_loan_ui_verification_20261021.json");
        var ui = read("joao_loan_ui_transcription_20261021.json");
        var loan = little(hex(captured.get("loanRaw").asString()));
        var terms = little(hex(captured.get("auxiliaryRaw").asString()));
        var installments = little(hex(captured.get("auxiliary00Rows").asString()));
        var appearance = little(hex(captured.get("term18Rows").asString()));
        assertThat(captured.get("uid").asInt()).isEqualTo(2000091868);
        assertThat(captured.get("headersUnchanged").asBoolean()).isTrue();
        assertThat(loan.getLong(8)).isEqualTo(Long.parseUnsignedLong(captured.get("person").asString(), 16));
        assertThat(loan.getLong(0x68)).isEqualTo(Long.parseUnsignedLong(captured.get("auxiliary").asString(), 16));
        assertThat(terms.getLong(8) - terms.getLong(0)).isEqualTo(12);
        assertThat(terms.getLong(0x20) - terms.getLong(0x18)).isEqualTo(8);
        assertThat(installments.getInt(0)).isEqualTo(332512);
        assertThat(installments.get(4)).isEqualTo((byte) 12);
        int combined = loan.getInt(0xb0) + (installments.get(4) > 0 ? installments.getInt(0) : 0);
        assertThat(combined).isEqualTo(698277).isEqualTo(report.get("calculatedClff").get("combined").asInt());
        assertThat(appearance.getInt(0)).isEqualTo(162477);
        assertThat(appearance.get(4)).isEqualTo((byte) 50);
        assertThat(combined + appearance.getInt(0)).isNotEqualTo(combined);
        assertThat(report.get("term18").get("visibleOnSubmittedScreen").asBoolean()).isFalse();
        assertThat(ui.get("values").get("mandatoryFutureFee").asInt()).isEqualTo(700000);
        assertThat(loan.getInt(0xbc)).isEqualTo(8120);
        assertThat(loan.getInt(0xc0)).isEqualTo(8119);
        assertThat(loan.getLong(0xa8)).isZero();
        assertThat(loan.getLong(0xa0)).isEqualTo(0x9002L); // Clfl fallback; individual meanings unproven.
    }

    @Test
    void nativeWorkingClauseBitsMatchThreeLoanScreensWithoutClaimingTheUnobservedUiBridge() throws Exception {
        var report = read("loan_clause_masks_verification_20261021.json");
        assertThat(report.get("status").asString()).isEqualTo("PARTIALLY VERIFIED");
        assertThat(report.get("mappings").size()).isEqualTo(5);
        assertThat(report.get("samples").size()).isEqualTo(3);
        for (var sample : report.get("samples")) {
            String name = sample.get("sample").asString();
            var capture = read(name.equals("joao") ? "joao_loan_ui_live_20261021.json"
                    : name + "_loan_screen_live_20261021.json").get("returnValues").get(0);
            var loan = little(hex(capture.get(name.equals("joao") ? "loanRaw" : "loanBytes").asString()));
            long flags = loan.getLong(0xa8) == 0 ? loan.getLong(0xa0) : loan.getLong(0xa8);
            assertThat(flags).isEqualTo(Long.parseUnsignedLong(sample.get("effectiveFlagsHex").asString(), 16));
            for (var comparison : sample.get("comparisons")) {
                long mask = Long.parseUnsignedLong(comparison.get("maskHex").asString(), 16);
                assertThat((flags & mask) != 0).isEqualTo(comparison.get("visible").asBoolean());
            }
        }
        // Independent native working-object flags, not a captured UI callback or a production mapping.
        assertThat(report.get("nativeWorkingFlagsOffsetHex").asString()).isEqualTo("100");
        assertThat(read("joao_clfl_getter_reopen_observation_20261021.json").get("returnValues").get(0)
                .get("hits").asInt()).isZero();
    }

    @Test
    void loanOffersAndActiveLoansShareSelectedFeeTermsWithoutSharingAllocationOrAllFlags() throws Exception {
        var capture = read("loan_offer_active_contract_join_live_20261021.json").get("returnValues").get(0);
        assertThat(capture.get("headersUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("loanOffersSeen").asInt()).isEqualTo(1047);
        int selected = 0;
        for (var sample : capture.get("records")) {
            if (!sample.get("selectedUiPlayer").asBoolean()) continue;
            selected++;
            var offer = little(hex(sample.get("raw").asString()));
            var active = little(hex(sample.get("activeLoan").get("raw").asString()));
            assertThat(offer.capacity()).isEqualTo(0x128);
            assertThat(active.capacity()).isEqualTo(0xf8);
            assertThat(offer.getLong(0x38)).isEqualTo(active.getLong(8));
            assertThat(offer.getInt(0x110)).isEqualTo(active.getInt(0xb0));
            long activeFlags = active.getLong(0xa8) == 0 ? active.getLong(0xa0) : active.getLong(0xa8);
            long xor = offer.getLong(0xf8) ^ activeFlags;
            assertThat(xor).isEqualTo(sample.get("personUid").asInt() == 12081410 ? 0x100 : 0);
            assertThat(sample.get("termsE8").asString())
                    .isNotEqualTo(sample.get("activeLoan").get("auxiliary").asString());
            var rows = sample.get("termRows");
            for (int i = 0; i < rows.size(); i++) {
                assertThat(rows.get(i).get("raw").asString())
                        .isEqualTo(sample.get("activeLoan").get("termRows").get(i).get("raw").asString());
            }
            // A valid field in a0x128 LOAN_OFFER is outside a0xF8 LOAN_CONTRACT.
            assertThatThrownBy(() -> active.getLong(0xf8)).isInstanceOf(IndexOutOfBoundsException.class);
        }
        assertThat(selected).isEqualTo(2);
    }

    @Test
    void auxiliaryPersonReferencesUsePeopleIndexesIncludingRetiredPersons() throws Exception {
        var captured = read("contract_auxiliary_person_links_live_20261021.json").get("returnValues").get(0);
        assertThat(captured.get("peopleCount").asInt()).isEqualTo(151577);
        assertThat(captured.get("links").size()).isEqualTo(7);
        int retired = 0;
        boolean beyondPlayerProjection = false;
        for (var link : captured.get("links")) {
            var record = little(hex(link.get("recordBytes").asString()));
            var person = little(hex(link.get("entityPrefix").asString()));
            int index = record.getInt(8);
            assertThat(index).isEqualTo(link.get("index").asInt()).isEqualTo(person.getInt(8));
            assertThat(person.getInt(12)).isEqualTo(link.get("uid").asInt());
            assertThat(Short.toUnsignedInt(record.getShort(0x16))).isEqualTo(link.get("type").asInt());
            if (index >= 44467) beyondPlayerProjection = true;
            if (link.get("rtti").asString().equals(".?AVRETIRED_PERSON_NAME_POINTERS@db@@")) retired++;
        }
        assertThat(retired).isEqualTo(2);
        assertThat(beyondPlayerProjection).isTrue();
    }

    @Test
    void temporaryLoanFlagExperimentsChangedOneByteAndRestoredTheCapturedObject() throws Exception {
        for (int bit : new int[]{0, 8}) {
            var captured = read("mamour_loan_bit" + bit + "_experiment_result_20261021.json")
                    .get("returnValues").get(0);
            assertThat(captured.get("restored").asBoolean()).isTrue();
            assertThat(captured.get("active").asBoolean()).isFalse();
            assertThat(captured.get("timerExists").asBoolean()).isFalse();
            assertThat(captured.get("currentValue").asInt()).isEqualTo(6);
            byte[] before = hex(captured.get("beforeBytes").asString());
            byte[] changed = hex(captured.get("afterWriteBytes").asString());
            byte[] restored = hex(captured.get("afterRestoreBytes").asString());
            assertThat(before).hasSize(0xf8).isEqualTo(restored);
            assertThat(changed).hasSize(before.length);
            assertThat(little(changed).getLong(0xa8)).isEqualTo(6 | (1L << bit));
            for (int offset = 0; offset < before.length; offset++) {
                if (offset == 0xa8 + bit / 8) assertThat(changed[offset]).isNotEqualTo(before[offset]);
                else assertThat(changed[offset]).isEqualTo(before[offset]);
            }
        }
    }

    @Test
    void serializedLoansWithNonemptyTermsMatchFieldsAndUnresolvedReferenceIndexes() throws Exception {
        var report = read("loan_serialized_records_verification_20261021.json");
        assertThat(report.get("memberSha256").asString()).isEqualTo(MEMBER_SHA256);
        assertThat(report.get("samples").size()).isEqualTo(2);
        for (var sample : report.get("samples")) {
            byte[] raw = hex(sample.get("rawHex").asString());
            verifyLoanRecord(sample, raw, ContractLayout.captured());
            assertThat(sample.get("fileEnd").asLong() - sample.get("fileStart").asLong()).isEqualTo(204);
        }
    }

    @Test
    void serializedLoanChecksRejectDriftTruncationAndUnexpectedTermCounts() throws Exception {
        var sample = read("loan_serialized_records_verification_20261021.json").get("samples").get(0);
        byte[] raw = hex(sample.get("rawHex").asString());
        assertThatThrownBy(() -> verifyLoanRecord(sample, raw, new ContractLayout(1)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> verifyLoanRecord(sample, Arrays.copyOf(raw, 203), ContractLayout.captured()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("truncated");
        byte[] unsupported = raw.clone(); unsupported[2] = 2;
        assertThatThrownBy(() -> verifyLoanRecord(sample, unsupported, ContractLayout.captured()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported branch");
    }

    private static void verifyLoanRecord(JsonNode sample, byte[] raw, ContractLayout layout) throws IOException {
        var captured = read(sample.get("name").asString() + "_loan_screen_live_20261021.json")
                .get("returnValues").get(0);
        byte[] object = hex(captured.get("loanBytes").asString());
        var walker = new Walker(raw, object, layout);
        walker.expect(1, 1); walker.expect(2, 1); walker.expect(1, 1); walker.expect(2, 1);
        byte[] term = hex(captured.get("vectors").get(0).get("bytes").asString());
        assertThat(walker.take(4)).isEqualTo(Arrays.copyOfRange(term, 0, 4));
        assertThat(walker.take(1)).containsExactly(term[4]);
        assertThat(walker.take(1)).containsExactly(term[8]);
        walker.expect(0, 6); walker.expect(0, 4);
        byte[] auxiliary = hex(captured.get("auxiliaryBytes").asString());
        assertThat(walker.take(7)).isEqualTo(Arrays.copyOfRange(auxiliary, 0xc0, 0xc7));
        walker.expect(0, 1); walker.expect(0, 1); walker.expect(5, 1);
        walker.fields(List.of(new Run(0xe4, 2), new Run(0xdc, 8)));
        walker.expect(1, 1);
        walker.fields(List.of(new Run(0x94, 9), new Run(0x90, 4), new Run(0xa0, 60),
                new Run(0xe6, 16), new Run(0x38, 12), new Run(0x48, 8), new Run(0x54, 6)));
        walker.expect(0, 4);
        walker.fields(List.of(new Run(0x62, 1), new Run(0x44, 4), new Run(0x5a, 7),
                new Run(0x28, 8), new Run(0x50, 4), new Run(0x61, 1)));
        assertThat(little(walker.take(4)).getInt()).isEqualTo(captured.get("index").asInt());
        assertThat(little(walker.take(8)).getLong()).isEqualTo(captured.get("loanTeam").get("index").asLong());
        walker.fields(List.of(new Run(0x20, 4), new Run(0x26, 1), new Run(0x24, 2)));
        assertThat(walker.cursor).isEqualTo(204).isEqualTo(raw.length);
    }

    private static Result verify(Fixture fixture, ContractLayout layout) {
        var entry = phase(fixture.capture(), "contract");
        if (entry.get("format").asInt() != 0xe95 || entry.get("flag49cab").asInt() != 1
                || entry.get("flag492ac").asInt() != 0) {
            throw new IllegalArgumentException("unsupported capture mode");
        }
        byte[] object = hex(fixture.returned().get("contractBytes").asString());
        assertThat(object).hasSize(0xc8);
        var walker = new Walker(fixture.raw(), object, layout);
        // Native 20BB000 prefix. Meanings intentionally left unnamed.
        walker.fields(List.of(new Run(0xc3, 1), new Run(0xa8, 16), new Run(0xa0, 8),
                new Run(0xc2, 1), new Run(0xc0, 2)));
        int count = walker.u8();
        if (count > 64) throw new IllegalArgumentException("row count exceeds capture bound");
        byte[] rows = walker.take(count * 8);
        // Empty +80, absent +98, and no discarded records in this capture.
        walker.expect(0, 1); walker.expect(0, 1); walker.expect(0, 4);
        walker.field(new Run(0xc4, 1));
        walker.expect(3, 1); walker.expect(4, 4); // 195D2E0 marker and element count
        walker.fields(List.of(new Run(0xb8, 4), new Run(0xbc, 4), new Run(0x38, 12),
                new Run(0x48, 8), new Run(0x54, 6)));
        walker.expect(0, 4); // no base auxiliary records
        walker.fields(List.of(new Run(0x62, 1), new Run(0x44, 4), new Run(0x5a, 7),
                new Run(0x28, 8), new Run(0x50, 4), new Run(0x61, 1),
                new Run(0x08, 4), new Run(0x10, 8), new Run(0x20, 4),
                new Run(0x26, 1), new Run(0x24, 2)));
        if (walker.cursor != fixture.raw().length) throw new IllegalArgumentException("unconsumed bytes");
        assertThat(rows).isEqualTo(hex(fixture.returned().get("vector68Bytes").asString()));
        assertThat(count).isEqualTo(fixture.returned().get("vector68Count").asInt());
        long start = streamPosition(entry), end = streamPosition(fixture.returned());
        assertThat(start).isEqualTo(90_058_773L);
        assertThat(end - start).isEqualTo(walker.cursor);
        return new Result(walker.cursor, count, rows);
    }

    private static Fixture fixture() throws IOException {
        var capture = read("player_contract_capture_20261019.json").get("returnValues").get(0);
        var file = read("contract_file_correlation_20261019.json");
        assertThat(file.get("memberSha256").asString()).isEqualTo(MEMBER_SHA256);
        return new Fixture(capture, phase(capture, "contractReturn"), hex(file.get("bytesHex").asString()));
    }

    private static JsonNode read(String name) throws IOException {
        try (var input = Files.newInputStream(EVIDENCE.resolve(name))) {
            byte[] data = input.readNBytes(1_048_577);
            if (data.length > 1_048_576) throw new IOException("Evidence exceeds JSON size bound");
            return JsonMapper.builder().build().readTree(data);
        }
    }

    private static JsonNode phase(JsonNode capture, String name) {
        for (var record : capture.get("records")) {
            if (name.equals(record.get("phase").asString())) return record;
        }
        throw new IllegalArgumentException("Missing capture phase: " + name);
    }

    private static long streamPosition(JsonNode record) {
        var buffer = little(hex(record.get("streamBytes").asString()));
        return Math.addExact(buffer.getLong(0x20), buffer.getLong(0x30));
    }

    private static byte[] hex(String text) { return HexFormat.of().parseHex(text.replace(" ", "")); }
    private static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    private record Fixture(JsonNode capture, JsonNode returned, byte[] raw) { }
    private record Result(int consumed, int rowCount, byte[] rows) { }
    private record Run(int offset, int width) { }
    /** Test-only layout: independent capture, never used by the production extractor. */
    private record ContractLayout(int shift) {
        static ContractLayout captured() { return new ContractLayout(0); }
    }

    private static final class Walker {
        private final byte[] raw;
        private final byte[] object;
        private final ContractLayout layout;
        private int cursor;

        private Walker(byte[] raw, byte[] object, ContractLayout layout) {
            this.raw = raw; this.object = object; this.layout = layout;
        }

        private byte[] take(int width) {
            if (width < 0 || width > raw.length - cursor) throw new IllegalArgumentException("truncated record");
            byte[] result = Arrays.copyOfRange(raw, cursor, cursor + width);
            cursor += width;
            return result;
        }

        private int u8() { return Byte.toUnsignedInt(take(1)[0]); }
        private void expect(int value, int width) {
            var data = take(width);
            for (int i = 0; i < width; i++) {
                if (Byte.toUnsignedInt(data[i]) != ((value >>> (i * 8)) & 0xff))
                    throw new IllegalArgumentException("unsupported branch in captured record");
            }
        }
        private void field(Run run) {
            int offset = run.offset() + layout.shift();
            if (offset < 0 || run.width() > object.length - offset)
                throw new IllegalArgumentException("object read outside capture");
            assertThat(take(run.width())).as("contract field +0x%x", offset)
                    .isEqualTo(Arrays.copyOfRange(object, offset, offset + run.width()));
        }
        private void fields(List<Run> runs) { runs.forEach(this::field); }
    }
}
