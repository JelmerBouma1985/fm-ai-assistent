package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.player.AttributeDefinitions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, read-only cross-check against the running FM26 process. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named = "fm26.live", matches = "true")
class Fm26LiveMemoryValidationTest {
    private static final String EXPECTED_PLUGIN_SHA256 =
            "EB6C86FAB56E051FE41482C7B93D8CB0AF716A1CC2C6197CE1BABBFF68372BB8";
    private static final int BUILD = 0x238BDD;

    @Test
    void identifiedPlayerSurvivesRootResolutionAndJavaDecode() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        int index = Integer.getInteger("fm26.live.player.index", 91_481);
        String expectedName = System.getProperty("fm26.live.player.name", "Sierd Buursma");
        String expectedClub = System.getProperty("fm26.live.player.club", "sc Heerenveen");
        int expectedPassing = Integer.getInteger("fm26.live.player.passing", 15);
        assertThat(pid).as("Set -Dfm26.live.pid to the running FM26 PID").isPositive();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("build", "0x" + Integer.toHexString(BUILD));
        report.put("pid", pid);
        report.put("index", index);
        try (var reader = ProcessReaders.open(pid)) {
            String sha256 = pluginSha256(reader);
            report.put("pluginSha256", sha256);
            assertThat(sha256).isEqualTo(EXPECTED_PLUGIN_SHA256);

            long moduleBase = FmOffsets.findGamePluginBase(reader);
            var bounds = FmOffsets.peopleBounds(reader, BUILD, moduleBase);
            report.put("moduleBase", "0x" + Long.toHexString(moduleBase));
            report.put("peopleCount", bounds.count());
            assertThat(index).isBetween(0, Math.toIntExact(bounds.count() - 1));
            long record = reader.readU64(bounds.start() + index * Long.BYTES);
            report.put("record", "0x" + Long.toHexString(record));
            assertThat(reader.readU32(record + 8)).isEqualTo(index);
            var type = new PersonMemoryClassifier(reader).classify(record).type();
            assertThat(type).isEqualTo(PersonMemoryClassifier.PersonType.PLAYER);
            var decoded = new PlayerExporter().decodeClassifiedRow(reader, index, record, type)
                    .orElseThrow();
            report.put("name", decoded.get("name"));
            report.put("playingClub", decoded.get("playing_club"));
            report.put("passing", decoded.get("Passing"));
            report.put("rawPassing", reader.readU8(record - 0x122));
            report.put("nameMatches", expectedName.equals(decoded.get("name")));
            report.put("clubMatches", expectedClub.equals(decoded.get("playing_club")));
            report.put("passingMatches", expectedPassing == ((Number) decoded.get("Passing")).intValue());
        } finally {
            Path output = Path.of("target", "fm26-live-validation.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, JsonMapper.builder().build().writeValueAsString(report));
        }
        assertThat(report.get("nameMatches")).isEqualTo(true);
        assertThat(report.get("clubMatches")).isEqualTo(true);
        assertThat(report.get("passingMatches")).isEqualTo(true);
    }

    @Test
    void identifiedAgilityPlayerMatchesRawMemoryAndJavaDecode() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).as("Set -Dfm26.live.pid to the running FM26 PID").isPositive();
        String expectedName = System.getProperty("fm26.live.agility.name", "Rodrigo Fracassi");
        String expectedClub = System.getProperty("fm26.live.agility.club", "Bayer 04 Leverkusen");
        int expectedAgility = Integer.getInteger("fm26.live.agility.value", 19);

        var export = new PlayerExporter().exportAllPlayers(pid, BUILD, null);
        var namedMatches = export.rows().stream()
                .filter(row -> expectedName.equalsIgnoreCase(String.valueOf(row.get("name"))))
                .toList();
        var matches = namedMatches.stream()
                .filter(row -> expectedClub.equals(row.get("playing_club")))
                .toList();
        assertThat(matches).as("player identity in Java's live export; same-name rows=%s", namedMatches)
                .hasSize(1);
        Map<String, Object> player = matches.getFirst();
        long record = Long.parseUnsignedLong(
                String.valueOf(player.get("record")).substring(2), 16);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("build", "0x" + Integer.toHexString(BUILD));
        report.put("pid", pid);
        report.put("pluginSha256", EXPECTED_PLUGIN_SHA256);
        report.put("peopleTableIndex", player.get("index"));
        report.put("record", player.get("record"));
        report.put("primaryPlayerObject", "0x" + Long.toHexString(record - 0x288));
        long primary = record - 0x288;
        report.put("name", player.get("name"));
        report.put("playingClub", player.get("playing_club"));
        report.put("javaAgility", player.get("Agility"));
        report.put("candidatePrimaryObjectOffset", "0x18D");
        report.put("candidateRawAddress", "record-0xFB");
        Map<String, Object> linkedObjects = new LinkedHashMap<>();
        int rawAgility;
        try (var reader = ProcessReaders.open(pid)) {
            assertThat(pluginSha256(reader)).isEqualTo(EXPECTED_PLUGIN_SHA256);
            long moduleBase = FmOffsets.findGamePluginBase(reader);
            report.put("moduleBase", "0x" + Long.toHexString(moduleBase));
            report.put("playerRtti", rttiTypeName(reader, moduleBase, reader.readU64(primary)));
            report.put("primaryObjectBytes0x000_0x3C7",
                    HexFormat.of().withUpperCase().formatHex(reader.readBytes(primary, 0x3C8)));
            Map<String, Object> rawAttributes = new LinkedHashMap<>();
            for (var field : AttributeDefinitions.POSITION_FIELDS) {
                int raw = reader.readU8(record - 0x13A + field.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET);
                rawAttributes.put(field.name(), Map.of("primaryObjectOffset", "0x" + Integer.toHexString(field.offset()),
                        "rawU8", raw, "javaValue", AttributeDefinitions.direct20(raw)));
            }
            for (var field : AttributeDefinitions.VISIBLE_FIELDS) {
                int raw = reader.readU8(record - 0x13A + field.offset() - AttributeDefinitions.SOURCE_OBJECT_BASE_OFFSET);
                rawAttributes.put(field.name(), Map.of("primaryObjectOffset", "0x" + Integer.toHexString(field.offset()),
                        "rawU8", raw, "javaValue", AttributeDefinitions.norm20(raw)));
            }
            for (var field : AttributeDefinitions.HIDDEN_DIRECT_FIELDS) {
                int raw = reader.readU8(record + field.offset());
                rawAttributes.put(field.name(), Map.of("recordRelativeOffset", "0x" + Integer.toHexString(field.offset()),
                        "rawU8", raw, "javaValue", raw));
            }
            report.put("playerAttributeBytes", rawAttributes);
            for (int offset : new int[]{0x120, 0x128, 0x130, 0x138, 0x2F0, 0x308, 0x330, 0x390, 0x398}) {
                long pointer = reader.readU64(primary + offset);
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("pointer", "0x" + Long.toHexString(pointer));
                if (pointer > 0 && pointer <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                    link.put("rtti", rttiTypeName(reader, moduleBase, reader.readU64(pointer)));
                }
                linkedObjects.put("0x" + Integer.toHexString(offset), link);
            }
            report.put("linkedObjects", linkedObjects);
            long agent = reader.readU64(primary + 0x398);
            if (agent > 0 && agent <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                report.put("agentFirstNameCandidate",
                        FmMemoryStrings.recordString(reader, agent, 0x28).orElse(""));
                report.put("agentLastNameCandidate",
                        FmMemoryStrings.recordString(reader, agent, 0x30).orElse(""));
                report.put("agentNameReference0x28",
                        "0x" + Long.toHexString(reader.readU64(agent + 0x28)));
                report.put("agentNameReference0x30",
                        "0x" + Long.toHexString(reader.readU64(agent + 0x30)));
            }
            long team = reader.readU64(primary + 0x130);
            if (team > 0 && team <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                long club = reader.readU64(team + 0x30);
                long begin = reader.readU64(team + 0x38);
                long end = reader.readU64(team + 0x40);
                long count = Long.compareUnsigned(end, begin) >= 0 ? (end - begin) / Long.BYTES : -1;
                report.put("teamObject", "0x" + Long.toHexString(team));
                report.put("teamRtti", rttiTypeName(reader, moduleBase, reader.readU64(team)));
                report.put("teamRosterCount", count);
                report.put("teamClubObject", "0x" + Long.toHexString(club));
                report.put("teamClubName", FmMemoryStrings.clubDisplayName(reader, club).orElse(""));
                boolean rosterContainsPlayer = false;
                if (count >= 0 && count <= 512) {
                    for (long cursor = begin; cursor < end; cursor += Long.BYTES) {
                        long rosterEntry = reader.readU64(cursor);
                        if (rosterEntry == primary || rosterEntry == record) {
                            rosterContainsPlayer = true;
                            break;
                        }
                    }
                }
                report.put("teamRosterContainsPlayer", rosterContainsPlayer);
            }
            rawAgility = reader.readU8(record - 0xFB);
            report.put("rawAgility", rawAgility);
            report.put("rawAgilityAddress", "0x" + Long.toHexString(record - 0xFB));
        }
        report.put("uiAgility", expectedAgility);
        Path output = Path.of("target", "fm26-live-rodrigo-agility.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writeValueAsString(report));

        assertThat(player.get("Agility")).isInstanceOf(Number.class);
        assertThat(((Number) player.get("Agility")).intValue()).isEqualTo(expectedAgility);
        assertThat(AttributeDefinitions.norm20(rawAgility)).isEqualTo(expectedAgility);
    }

    @Test
    @EnabledIfSystemProperty(named = "fm26.live.contracts", matches = "true")
    void screenshotPlayersHaveBoundedContractObjectsCaptured() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).as("Set -Dfm26.live.pid to the running FM26 PID").isPositive();
        var players = new PlayerExporter().exportAllPlayers(pid, BUILD, null);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pid", pid);
        report.put("build", "0x" + Integer.toHexString(BUILD));
        report.put("pluginSha256", EXPECTED_PLUGIN_SHA256);
        report.put("gameDate", players.gameDate());
        Map<String, Object> captures = new LinkedHashMap<>();
        try (var reader = ProcessReaders.open(pid)) {
            assertThat(pluginSha256(reader)).isEqualTo(EXPECTED_PLUGIN_SHA256);
            long moduleBase = FmOffsets.findGamePluginBase(reader);
            report.put("moduleBase", "0x" + Long.toHexString(moduleBase));
            for (String expectedName : new String[]{"Stephen Jones", "Sierd Buursma", "Juan Garrido"}) {
                var rows = players.rows().stream()
                        .filter(row -> expectedName.equalsIgnoreCase(String.valueOf(row.get("name"))))
                        .toList();
                List<Map<String, Object>> playerCaptures = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    long record = Long.parseUnsignedLong(String.valueOf(row.get("record")).substring(2), 16);
                    long primary = record - PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET;
                    long contract = reader.readU64(record + 0xA8);
                    Map<String, Object> player = new LinkedHashMap<>();
                    player.put("name", row.get("name"));
                    player.put("uniqueId", row.get("unique_id"));
                    player.put("peopleTableIndex", row.get("index"));
                    player.put("record", row.get("record"));
                    player.put("club", row.get("club"));
                    player.put("gameDate", row.get("age_as_of"));
                    player.put("javaSalaryWeeklyRaw", row.get("salary_weekly_raw"));
                    player.put("javaContractEndDate", row.get("contract_end_date"));
                    player.put("primaryObject", "0x" + Long.toHexString(primary));
                    byte[] primaryBytes = reader.readBytes(primary, 0x3C8);
                    player.put("primaryObjectBytesWindow0x000_0x3C7",
                            HexFormat.of().withUpperCase().formatHex(primaryBytes));
                    List<Map<String, Object>> primaryTypedPointerCandidates = new ArrayList<>();
                    for (int offset = Long.BYTES; offset < primaryBytes.length; offset += Long.BYTES) {
                        long candidate = littleEndianLong(primaryBytes, offset);
                        if (candidate <= 0 || candidate > com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                            continue;
                        }
                        try {
                            String candidateRtti = rttiTypeName(reader, moduleBase, reader.readU64(candidate));
                            if (candidateRtti.startsWith(".?A")) {
                                primaryTypedPointerCandidates.add(Map.of(
                                        "primaryOffset", "0x" + Integer.toHexString(offset),
                                        "pointer", "0x" + Long.toHexString(candidate),
                                        "rtti", candidateRtti));
                            }
                        } catch (Exception ignored) {
                            // Primary object contains non-pointer fields; only retain readable RTTI-confirmed targets.
                        }
                    }
                    player.put("primaryTypedPointerCandidates", primaryTypedPointerCandidates);
                    player.put("fullContractPointerAtRecordPlus0xA8", "0x" + Long.toHexString(contract));
                    if (contract > 0 && contract <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                        player.put("contractRtti", rttiTypeName(reader, moduleBase, reader.readU64(contract)));
                        player.put("playingTimeByteCandidateAtContractPlus0x54", reader.readU8(contract + 0x54));
                        byte[] contractBytes = reader.readBytes(contract, 0x200);
                        player.put("contractCaptureWindowSize", contractBytes.length);
                        player.put("contractRawBytesWindow0x000_0x1FF",
                                HexFormat.of().withUpperCase().formatHex(contractBytes));
                        List<Map<String, Object>> typedPointerCandidates = new ArrayList<>();
                        for (int offset = Long.BYTES; offset < contractBytes.length; offset += Long.BYTES) {
                            long candidate = littleEndianLong(contractBytes, offset);
                            if (candidate <= 0 || candidate > com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                                continue;
                            }
                            String candidateRtti;
                            try {
                                candidateRtti = rttiTypeName(reader, moduleBase, reader.readU64(candidate));
                            } catch (Exception ignored) {
                                continue;
                            }
                            if (candidateRtti.startsWith(".?A")) {
                                typedPointerCandidates.add(Map.of(
                                        "contractOffset", "0x" + Integer.toHexString(offset),
                                        "pointer", "0x" + Long.toHexString(candidate),
                                        "rtti", candidateRtti));
                            }
                        }
                        player.put("typedPointerCandidates", typedPointerCandidates);
                        player.put("termVectors", List.of(
                                captureContractVector(reader, contract, 0x68),
                                captureContractVector(reader, contract, 0x80),
                                captureContractVector(reader, contract, 0x130)));
                        player.put("contractTeamPointerCandidate", "0x" + Long.toHexString(reader.readU64(contract + 0x10)));
                        player.put("contractSalaryU32At0x20", reader.readU32(contract + 0x20));
                        player.put("contractByteAt0x26", reader.readU8(contract + 0x26));
                        player.put("contractDateU16PairAt0x48", List.of(
                                reader.readU16(contract + 0x48), reader.readU16(contract + 0x4A)));
                        player.put("contractDateU16PairAt0x110", List.of(
                                reader.readU16(contract + 0x110), reader.readU16(contract + 0x112)));
                    }
                    playerCaptures.add(player);
                }
                captures.put(expectedName, playerCaptures);
            }
        }
        report.put("screenshotPlayers", captures);
        Path output = Path.of("target", "fm26-live-contract-samples.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writeValueAsString(report));
        assertThat(captures).containsKeys("Stephen Jones", "Sierd Buursma", "Juan Garrido");
        for (String expectedName : new String[]{"Stephen Jones", "Sierd Buursma", "Juan Garrido"}) {
            assertThat((List<?>) captures.get(expectedName))
                    .as("exact screenshot player %s", expectedName).hasSize(1);
        }
        assertThat(((Number) ((Map<?, ?>) ((List<?>) captures.get("Stephen Jones")).getFirst())
                .get("playingTimeByteCandidateAtContractPlus0x54")).intValue()).isEqualTo(3);
        assertThat(((Number) ((Map<?, ?>) ((List<?>) captures.get("Sierd Buursma")).getFirst())
                .get("playingTimeByteCandidateAtContractPlus0x54")).intValue()).isEqualTo(3);
        assertThat(((Number) ((Map<?, ?>) ((List<?>) captures.get("Juan Garrido")).getFirst())
                .get("playingTimeByteCandidateAtContractPlus0x54")).intValue()).isEqualTo(2);
    }

    private static Map<String, Object> captureContractVector(
            com.github.fmaiassistent.memory.ProcessMemoryReader reader,
            long contract,
            int vectorOffset) throws Exception {
        long begin = reader.readU64(contract + vectorOffset);
        long end = reader.readU64(contract + vectorOffset + Long.BYTES);
        long capacityEnd = reader.readU64(contract + vectorOffset + Long.BYTES * 2);
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("contractVectorOffset", "0x" + Integer.toHexString(vectorOffset));
        vector.put("begin", "0x" + Long.toHexString(begin));
        vector.put("end", "0x" + Long.toHexString(end));
        vector.put("capacityEnd", "0x" + Long.toHexString(capacityEnd));
        if (begin == 0 && end == 0 && capacityEnd == 0) {
            vector.put("entryCount", 0);
            vector.put("entries", List.of());
            return vector;
        }
        if (begin <= 0 || end < begin || capacityEnd < end || end - begin > 0x400
                || (end - begin) % 8 != 0 || capacityEnd > com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
            vector.put("validVectorCandidate", false);
            return vector;
        }
        int count = Math.toIntExact((end - begin) / 8);
        List<Map<String, Object>> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long address = begin + index * 8L;
            entries.add(Map.of(
                    "index", index,
                    "address", "0x" + Long.toHexString(address),
                    "rawBytes", HexFormat.of().withUpperCase().formatHex(reader.readBytes(address, 8)),
                    "u32_0", reader.readU32(address),
                    "u16_4", reader.readU16(address + 4),
                    "u16_6", reader.readU16(address + 6)));
        }
        vector.put("validVectorCandidate", true);
        vector.put("entryCount", count);
        vector.put("entryStride", 8);
        vector.put("entries", entries);
        return vector;
    }

    @Test
    @EnabledIfSystemProperty(named = "fm26.live.collections", matches = "true")
    void oneExportedStaffObjectAndTypedLinksAreCaptured() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).isPositive();
        var staff = new StaffExporter().exportAllStaff(pid, BUILD, null);
        assertThat(staff.rows()).isNotEmpty();
        Map<String, Object> row = null;
        long record = 0;
        long primary = 0;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pid", pid);
        report.put("build", "0x" + Integer.toHexString(BUILD));
        report.put("pluginSha256", EXPECTED_PLUGIN_SHA256);
        report.put("exportedStaffCount", staff.rows().size());
        try (var reader = ProcessReaders.open(pid)) {
            assertThat(pluginSha256(reader)).isEqualTo(EXPECTED_PLUGIN_SHA256);
            long moduleBase = FmOffsets.findGamePluginBase(reader);
            report.put("moduleBase", "0x" + Long.toHexString(moduleBase));
            for (Map<String, Object> candidate : staff.rows()) {
                long candidateRecord = Long.parseUnsignedLong(
                        String.valueOf(candidate.get("record_address")).substring(2), 16);
                long candidatePrimary = candidateRecord - 0x100;
                if (rttiTypeName(reader, moduleBase, reader.readU64(candidateRecord))
                        .equals(".?AVACTUAL_NON_PLAYER@db@@")) {
                    row = candidate;
                    record = candidateRecord;
                    primary = candidatePrimary;
                    break;
                }
            }
            assertThat(row).as("standalone actual non-player sample").isNotNull();
            report.put("staffIndex", row.get("staff_index"));
            report.put("secondaryRecord", "0x" + Long.toHexString(record));
            report.put("primaryObject", "0x" + Long.toHexString(primary));
            report.put("decodedRow", row);
            report.put("primaryVptr", "0x" + Long.toHexString(reader.readU64(primary)));
            report.put("secondaryRtti", rttiTypeName(reader, moduleBase, reader.readU64(record)));
            report.put("primaryObjectBytes0x000_0x23F",
                    HexFormat.of().withUpperCase().formatHex(reader.readBytes(primary, 0x240)));
            Map<String, Object> links = new LinkedHashMap<>();
            for (int offset : new int[]{0x60, 0x70, 0x168, 0x180, 0x208}) {
                long pointer = reader.readU64(primary + offset);
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("pointer", "0x" + Long.toHexString(pointer));
                if (pointer > 0 && pointer <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                    link.put("rtti", rttiTypeName(reader, moduleBase, reader.readU64(pointer)));
                }
                links.put("0x" + Integer.toHexString(offset), link);
            }
            report.put("candidateTypedLinks", links);
            long contract = reader.readU64(record + 0xA8);
            Map<String, Object> contractLink = new LinkedHashMap<>();
            contractLink.put("pointer", "0x" + Long.toHexString(contract));
            if (contract > 0 && contract <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                contractLink.put("rtti", rttiTypeName(reader, moduleBase, reader.readU64(contract)));
                contractLink.put("jobIdCandidate", reader.readU8(contract + 0x26));
                contractLink.put("salaryWeeklyCandidate", reader.readU32(contract + 0x20));
                long contractTeam = reader.readU64(contract + 0x10);
                contractLink.put("teamPointer", "0x" + Long.toHexString(contractTeam));
                if (contractTeam > 0 && contractTeam <= com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                    contractLink.put("teamRtti", rttiTypeName(reader, moduleBase, reader.readU64(contractTeam)));
                    long contractClub = reader.readU64(contractTeam + 0x30);
                    contractLink.put("clubPointer", "0x" + Long.toHexString(contractClub));
                    contractLink.put("clubName", FmMemoryStrings.clubDisplayName(reader, contractClub).orElse(""));
                }
            }
            report.put("contractLinkCandidate", contractLink);
        }
        Path output = Path.of("target", "fm26-live-staff-layout.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writeValueAsString(report));
        assertThat(report.get("secondaryRtti")).isEqualTo(".?AVACTUAL_NON_PLAYER@db@@");
    }

    @Test
    @EnabledIfSystemProperty(named = "fm26.live.collections", matches = "true")
    void nativeRegistryAndJavaExportCountsAreReported() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).isPositive();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pid", pid);
        report.put("build", "0x" + Integer.toHexString(BUILD));
        long moduleBase;
        try (var reader = ProcessReaders.open(pid)) {
            assertThat(pluginSha256(reader)).isEqualTo(EXPECTED_PLUGIN_SHA256);
            moduleBase = FmOffsets.findGamePluginBase(reader);
            for (String name : new String[]{"CityOffset", "ClubOffset", "CompetitionOffset",
                    "ContinentOffset", "NationOffset", "CurrencyOffset", "PeopleOffset",
                    "RegionOffset", "StadiumOffset", "TeamOffset", "AgreementOffset"}) {
                report.put(name, FmOffsets.tableBounds(reader, BUILD, moduleBase, name).count());
            }
        }
        var people = new PeopleExporter().exportAllPeople(pid, BUILD, moduleBase);
        var clubs = new ClubExporter().exportAllClubs(pid, BUILD, moduleBase);
        var competitions = new CompetitionExporter().exportAllCompetitions(pid, BUILD, moduleBase);
        report.put("javaPlayers", people.players().rows().size());
        report.put("javaStaff", people.staff().rows().size());
        report.put("javaClubs", clubs.rows().size());
        report.put("javaCompetitions", competitions.rows().size());
        report.put("peopleDiagnostics", people.diagnostics().toString());
        report.put("playerDuplicateUniqueIds", people.players().rows().size()
                - people.players().rows().stream().map(row -> row.get("unique_id")).distinct().count());
        report.put("staffDuplicateUniqueIds", people.staff().rows().size()
                - people.staff().rows().stream().map(row -> row.get("unique_id")).distinct().count());
        Path output = Path.of("target", "fm26-live-collections.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writeValueAsString(report));
        assertThat(people.players().rows()).isNotEmpty();
        assertThat(people.staff().rows()).isNotEmpty();
        assertThat(clubs.rows()).isNotEmpty();
        assertThat(competitions.rows()).isNotEmpty();
    }

    @Test
    @EnabledIfSystemProperty(named = "fm26.live.collections", matches = "true")
    void unknownPeopleTableRttiSubtypesAreCatalogued() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).isPositive();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pid", pid);
        report.put("build", "0x" + Integer.toHexString(BUILD));
        Map<Integer, Integer> countsByDynamicOffset = new TreeMap<>();
        Map<Integer, Set<String>> namesByDynamicOffset = new TreeMap<>();
        Map<String, Integer> countsByRttiType = new TreeMap<>();
        Map<Long, String> namesByVtable = new TreeMap<>();
        try (var reader = ProcessReaders.open(pid)) {
            String sha256 = pluginSha256(reader);
            report.put("pluginSha256", sha256);
            assertThat(sha256).isEqualTo(EXPECTED_PLUGIN_SHA256);
            long moduleBase = FmOffsets.findGamePluginBase(reader);
            report.put("moduleBase", "0x" + Long.toHexString(moduleBase));
            var bounds = FmOffsets.peopleBounds(reader, BUILD, moduleBase);
            report.put("peopleCount", bounds.count());
            report.put("rttiMethod", "vtable[-1] -> CompleteObjectLocator; locator+4 subobject offset; locator+12 TypeDescriptor RVA; TypeDescriptor+0x10 decorated name");
            byte[] table = reader.readBytes(bounds.start(), Math.toIntExact(bounds.count() * Long.BYTES));
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            long sierdEntry = littleEndianLong(table, 91_481 * Long.BYTES);
            var sierdType = classifier.classify(sierdEntry).type();
            assertThat(sierdType).isEqualTo(PersonMemoryClassifier.PersonType.PLAYER);
            var sierdRow = new PlayerExporter().decodeClassifiedRow(reader, 91_481, sierdEntry, sierdType)
                    .orElseThrow();
            assertThat(sierdRow.get("name")).isEqualTo("Sierd Buursma");
            assertThat(sierdRow.get("playing_club")).isEqualTo("sc Heerenveen");
            long sierdAgent = reader.readU64(sierdEntry - PersonMemoryClassifier.PLAYER_DYNAMIC_OFFSET + 0x398);
            report.put("sierdAgentPointer", "0x" + Long.toHexString(sierdAgent));
            int sierdAgentIndex = -1;
            long agentVtable = reader.readU64(sierdAgent);
            String agentRtti = rttiTypeName(reader, moduleBase, agentVtable);
            report.put("sierdAgentRtti", agentRtti);
            assertThat(agentRtti).isEqualTo(".?AVAGENT@db@@");
            long agentFirstNameReference = reader.readU64(sierdAgent + 0x28);
            long agentLastNameReference = reader.readU64(sierdAgent + 0x30);
            report.put("sierdAgentFirstNameReference", "0x" + Long.toHexString(agentFirstNameReference));
            report.put("sierdAgentLastNameReference", "0x" + Long.toHexString(agentLastNameReference));
            report.put("sierdAgentFirstNameReferenceBytes", safeReadHex(reader, agentFirstNameReference, 32));
            report.put("sierdAgentLastNameReferenceBytes", safeReadHex(reader, agentLastNameReference, 32));
            String agentFirstName = FmMemoryStrings.recordString(reader, sierdAgent, 0x28).orElse("");
            String agentLastName = FmMemoryStrings.recordString(reader, sierdAgent, 0x30).orElse("");
            report.put("sierdAgentFirstName", agentFirstName);
            report.put("sierdAgentLastName", agentLastName);
            assertThat(agentFirstName).isEqualTo("Kevin");
            assertThat(agentLastName).isEqualTo("van Rijn");
            report.put("sierdAgentBytes0x00_0xCF",
                    HexFormat.of().withUpperCase().formatHex(reader.readBytes(sierdAgent, 0xD0)));
            long agentLocator = reader.readU64(agentVtable - Long.BYTES);
            int agentDynamicOffset = reader.readI32(agentLocator + 4);
            report.put("sierdAgentPeopleSubobjectOffset", agentDynamicOffset);
            int unknown = 0;
            int sierdAgentTableAdjustment = -1;
            for (int index = 0; index < bounds.count(); index++) {
                long entry = littleEndianLong(table, index * Long.BYTES);
                if (entry == sierdAgent) {
                    sierdAgentIndex = index;
                    sierdAgentTableAdjustment = 0;
                } else if (entry == sierdAgent + 0xC0) {
                    sierdAgentIndex = index;
                    sierdAgentTableAdjustment = 0xC0;
                }
                if (entry <= 0 || entry > com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                var classification = classifier.classify(entry);
                if (classification.type() != PersonMemoryClassifier.PersonType.UNKNOWN) {
                    continue;
                }
                unknown++;
                countsByDynamicOffset.merge(classification.dynamicOffset(), 1, Integer::sum);
                long vtable = reader.readU64(entry);
                String className = namesByVtable.computeIfAbsent(vtable, key -> rttiTypeName(reader, moduleBase, key));
                countsByRttiType.merge(className, 1, Integer::sum);
                namesByDynamicOffset.computeIfAbsent(classification.dynamicOffset(), ignored -> new TreeSet<>())
                        .add(className);
            }
            report.put("unknownSlots", unknown);
            report.put("sierdAgentPeopleTableIndex", sierdAgentIndex);
            report.put("sierdAgentPeopleTableAdjustment", sierdAgentTableAdjustment);
            report.put("unknownSlotsByRttiType", countsByRttiType);
            Map<String, Object> subtypes = new TreeMap<>();
            countsByDynamicOffset.forEach((offset, count) -> subtypes.put(Integer.toString(offset), Map.of(
                    "count", count,
                    "rttiTypeNames", new ArrayList<>(namesByDynamicOffset.getOrDefault(offset, Set.of())))));
            report.put("unknownSubtypes", subtypes);
        }
        Path output = Path.of("target", "fm26-live-unknown-subtypes.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writeValueAsString(report));
        assertThat(report.get("unknownSlots")).isEqualTo(82_051);
        assertThat(report.get("sierdAgentPeopleTableIndex")).isEqualTo(81_654);
        assertThat(report.get("sierdAgentPeopleTableAdjustment")).isEqualTo(0xC0);
        assertThat(countsByRttiType).containsEntry(".?AVAGENT@db@@", 395)
                .containsEntry(".?AVVIRTUAL_PLAYER@db@@", 6_831)
                .containsEntry(".?AVSUPPORT_STAFF@db@@", 4_618)
                .containsEntry(".?AVOFFICIAL@db@@", 1_936)
                .containsEntry(".?AVJOURNALIST@db@@", 473);
    }

    private static String pluginSha256(com.github.fmaiassistent.memory.ProcessMemoryReader reader) throws Exception {
        var plugin = reader.maps().stream()
                .filter(region -> region.path().toLowerCase().endsWith("game_plugin.dll"))
                .findFirst().orElseThrow();
        MessageDigest hasher = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(Path.of(plugin.path())), hasher)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(hasher.digest());
    }

    private static String safeReadHex(com.github.fmaiassistent.memory.ProcessMemoryReader reader,
                                      long address, int size) {
        try {
            return HexFormat.of().withUpperCase().formatHex(reader.readBytes(address, size));
        } catch (Exception ignored) {
            return "<unreadable>";
        }
    }

    private static String rttiTypeName(com.github.fmaiassistent.memory.ProcessMemoryReader reader,
                                       long moduleBase, long vtable) {
        try {
            long locator = reader.readU64(vtable - Long.BYTES);
            long typeDescriptor = moduleBase + reader.readU32(locator + 12);
            byte[] name = reader.readBytes(typeDescriptor + 0x10, 256);
            int end = 0;
            while (end < name.length && name[end] != 0) {
                end++;
            }
            return end == name.length ? "<unterminated>"
                    : new String(name, 0, end, StandardCharsets.US_ASCII);
        } catch (Exception ignored) {
            return "<unreadable>";
        }
    }

    private static long littleEndianLong(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | bytes[offset + 3] << 24)
                | (long) (Byte.toUnsignedInt(bytes[offset + 4])
                | Byte.toUnsignedInt(bytes[offset + 5]) << 8
                | Byte.toUnsignedInt(bytes[offset + 6]) << 16
                | bytes[offset + 7] << 24) << 32;
    }
}
