package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.ProcessInfo;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One-off spike: find the user's team via the team pointer table, then scan
 * its surroundings for person records (candidate team sheet). Read-only.
 * Runs only when FM_SPIKE_CLUB is set, never in CI.
 */
@EnabledIfEnvironmentVariable(named = "FM_SPIKE_CLUB", matches = ".+")
class TeamSheetSpikeTest {
    private static final long TEAM_WINDOW = 0x8000L;
    private static final long MAX_TEAMS = 300_000L;

    @Test
    void scanAroundUserTeam() throws Exception {
        String clubName = System.getenv("FM_SPIKE_CLUB");
        int pid = findGamePid();
        System.out.println("SPIKE pid=" + pid + " club=" + clubName);
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            FmOffsets.Bounds bounds =
                    FmOffsets.tableBounds(reader, FmOffsets.DEFAULT_BUILD, base, "TeamOffset");
            System.out.println("SPIKE team table start=0x" + Long.toHexString(bounds.start())
                    + " end=0x" + Long.toHexString(bounds.end()) + " count=" + bounds.count());
            if (bounds.count() <= 0 || bounds.count() > MAX_TEAMS) {
                throw new IOException("implausible team table count: " + bounds.count());
            }
            List<Long> teams = matchingTeams(reader, bounds, clubName);
            System.out.println("SPIKE teams matching '" + clubName + "': " + teams.size());
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            for (long team : teams) {
                System.out.println("SPIKE team at 0x" + Long.toHexString(team));
                scanTeamSurroundings(reader, classifier, team);
            }
            System.out.println("SPIKE done");
        }
    }

    private static List<Long> matchingTeams(
            ProcessMemoryReader reader, FmOffsets.Bounds bounds, String clubName) throws IOException {
        List<Long> matches = new ArrayList<>();
        long count = bounds.count();
        long slotsPerChunk = 8192;
        for (long chunkStart = 0; chunkStart < count; chunkStart += slotsPerChunk) {
            long chunkCount = Math.min(slotsPerChunk, count - chunkStart);
            byte[] data;
            try {
                data = reader.readBytes(bounds.start() + chunkStart * Long.BYTES, (int) (chunkCount * Long.BYTES));
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            for (long slot = 0; slot < chunkCount; slot++) {
                long team = getLong(data, (int) (slot * Long.BYTES));
                if (team <= 0 || team > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                Optional<Long> clubOpt;
                try {
                    clubOpt = reader.qwordOrNull(team + 0x30);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (clubOpt.isEmpty()) {
                    continue;
                }
                Optional<String> name;
                try {
                    name = FmMemoryStrings.clubDisplayName(reader, clubOpt.get());
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (name.isPresent() && name.get().equalsIgnoreCase(clubName)) {
                    matches.add(team);
                    System.out.println("SPIKE match team=0x" + Long.toHexString(team)
                            + " club=0x" + Long.toHexString(clubOpt.get()));
                }
            }
        }
        return matches;
    }

    private static void scanTeamSurroundings(
            ProcessMemoryReader reader, PersonMemoryClassifier classifier, long team) {
        int personHits = 0;
        for (long address = team - TEAM_WINDOW; address < team + TEAM_WINDOW; address += Long.BYTES) {
            if (address <= 0) {
                continue;
            }
            long value;
            try {
                value = reader.readU64(address);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            if (value <= 0 || value > ProcessMemoryReader.MAX_USER_ADDRESS) {
                continue;
            }
            PersonMemoryClassifier.Classification classification;
            try {
                classification = classifier.classify(value);
            } catch (IOException | RuntimeException notAPerson) {
                continue;
            }
            if (classification.type() == PersonMemoryClassifier.PersonType.UNKNOWN) {
                continue;
            }
            personHits++;
            String name;
            try {
                name = FmMemoryStrings.playerName(reader, value).orElse("");
            } catch (RuntimeException unreadable) {
                name = "";
            }
            long uniqueId;
            try {
                uniqueId = reader.readU32(value + 0x0C);
            } catch (IOException | RuntimeException unreadable) {
                uniqueId = -1;
            }
            System.out.println("SPIKE person team+0x" + Long.toHexString(address - team)
                    + " type=" + classification.type() + " unique_id=" + uniqueId + " name=" + name);
        }
        System.out.println("SPIKE person hits near team: " + personHits);
    }

    private static long getLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value |= ((long) (data[offset + i] & 0xff)) << (8 * i);
        }
        return value;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_TEAM", matches = ".+")
    void dumpSheetArray() throws Exception {
        String[] teams = System.getenv("FM_SPIKE_TEAM").split(",");
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            for (String token : teams) {
                long team = Long.decode(token.trim());
                System.out.println("SPIKE sheet team=0x" + Long.toHexString(team));
                for (long slot = 0; slot < 0x100; slot++) {
                long address = team + slot * Long.BYTES;
                long value;
                try {
                    value = reader.readU64(address);
                } catch (IOException | RuntimeException unreadable) {
                    System.out.println("SPIKE sheet +0x" + Long.toHexString(slot * 8) + " unreadable");
                    continue;
                }
                if (value <= 0 || value > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    System.out.println("SPIKE sheet +0x" + Long.toHexString(slot * 8) + " = 0x"
                            + Long.toHexString(value));
                    continue;
                }
                String detail;
                try {
                    PersonMemoryClassifier.Classification classification = classifier.classify(value);
                    if (classification.type() == PersonMemoryClassifier.PersonType.UNKNOWN) {
                        detail = "not-a-person";
                    } else {
                        String name;
                        try {
                            name = FmMemoryStrings.playerName(reader, value).orElse("");
                        } catch (RuntimeException unreadable) {
                            name = "";
                        }
                        long uniqueId;
                        try {
                            uniqueId = reader.readU32(value + 0x0C);
                        } catch (IOException | RuntimeException unreadable) {
                            uniqueId = -1;
                        }
                        detail = classification.type() + " unique_id=" + uniqueId + " " + name;
                    }
                } catch (IOException | RuntimeException notAPerson) {
                    detail = "unreadable-target";
                }
                System.out.println("SPIKE sheet +0x" + Long.toHexString(slot * 8) + " " + detail);
                }
            }
            System.out.println("SPIKE sheet done");
        }
    }

    /**
     * Second-order scan: for every in-range pointer in team+0x0..0x800, read 24
     * qwords there and report targets holding 8+ person records (candidate
     * squad/XI arrays). Gated by FM_SPIKE_TEAM (single address).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_TEAM", matches = "0x.+")
    void scanSecondOrder() throws Exception {
        long team = Long.decode(System.getenv("FM_SPIKE_TEAM").split(",")[0]);
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            for (long slot = 0; slot < 0x100; slot++) {
                long fieldAddress = team + slot * Long.BYTES;
                long target;
                try {
                    target = reader.readU64(fieldAddress);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                if (target <= 0 || target > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                List<String> persons = new ArrayList<>();
                for (long entry = 0; entry < 24; entry++) {
                    long candidate;
                    try {
                        candidate = reader.readU64(target + entry * Long.BYTES);
                    } catch (IOException | RuntimeException unreadable) {
                        break;
                    }
                    if (candidate <= 0 || candidate > ProcessMemoryReader.MAX_USER_ADDRESS) {
                        continue;
                    }
                    PersonMemoryClassifier.Classification classification;
                    try {
                        classification = classifier.classify(candidate);
                    } catch (IOException | RuntimeException notAPerson) {
                        continue;
                    }
                    if (classification.type() == PersonMemoryClassifier.PersonType.UNKNOWN) {
                        continue;
                    }
                    String name;
                    try {
                        name = FmMemoryStrings.playerName(reader, candidate).orElse("");
                    } catch (RuntimeException unreadable) {
                        name = "";
                    }
                    persons.add("[" + entry + "]" + classification.type() + ":" + name);
                    if (persons.size() >= 12) {
                        break;
                    }
                }
                if (persons.size() >= 8) {
                    System.out.println("SPIKE dense team+0x" + Long.toHexString(slot * 8)
                            + " -> 0x" + Long.toHexString(target));
                    persons.forEach(person -> System.out.println("SPIKE   " + person));
                }
            }
            System.out.println("SPIKE deep done");
        }
    }

    /**
     * Searches for known player unique_ids (u32/i64, little-endian) in a wide
     * window around the team: a clustered hit run means the sheet stores IDs,
     * not pointers. Gated by FM_SPIKE_TEAM (single address) and FM_SPIKE_IDS
     * (comma-separated unique_ids).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_IDS", matches = ".+")
    void scanForKnownIds() throws Exception {
        long team = Long.decode(System.getenv("FM_SPIKE_TEAM").split(",")[0]);
        long[] wanted = java.util.Arrays.stream(System.getenv("FM_SPIKE_IDS").split(","))
                .mapToLong(token -> Long.parseLong(token.trim()))
                .toArray();
        int pid = findGamePid();
        long window = 0x200000L;
        int chunk = 1 << 20;
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            for (long base = team - window; base < team + window; base += chunk) {
                if (base <= 0) {
                    continue;
                }
                byte[] data;
                try {
                    data = reader.readBytes(base, chunk + 8);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                for (int i = 0; i + 8 <= data.length; i += 4) {
                    long le32 = (data[i] & 0xffL)
                            | ((data[i + 1] & 0xffL) << 8)
                            | ((data[i + 2] & 0xffL) << 16)
                            | ((data[i + 3] & 0xffL) << 24);
                    for (long id : wanted) {
                        if (le32 == id) {
                            System.out.println("SPIKE id " + id + " at team+0x"
                                    + Long.toHexString(base + i - team));
                        }
                    }
                }
            }
            System.out.println("SPIKE idscan done");
        }
    }

    /**
     * Locates one person record by unique_id via the people pointer table,
     * verifies with classification + name, then dumps the surroundings as raw
     * u16 values for performance-field hunting. Gated by FM_SPIKE_UID.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_UID", matches = ".+")
    void findRecordByUniqueId() throws Exception {
        long wanted = Long.parseLong(System.getenv("FM_SPIKE_UID").trim());
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            FmOffsets.Bounds bounds =
                    FmOffsets.peopleBounds(reader, FmOffsets.DEFAULT_BUILD, base);
            System.out.println("SPIKE people table count=" + bounds.count());
            if (bounds.count() <= 0 || bounds.count() > 1_000_000L) {
                throw new IOException("implausible people count: " + bounds.count());
            }
            long found = 0;
            long slotsPerChunk = 8192;
            outer:
            for (long chunkStart = 0; chunkStart < bounds.count(); chunkStart += slotsPerChunk) {
                long chunkCount = Math.min(slotsPerChunk, bounds.count() - chunkStart);
                byte[] data;
                try {
                    data = reader.readBytes(bounds.start() + chunkStart * Long.BYTES,
                            (int) (chunkCount * Long.BYTES));
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                for (long slot = 0; slot < chunkCount; slot++) {
                    long person = getLong(data, (int) (slot * Long.BYTES));
                    if (person <= 0 || person > ProcessMemoryReader.MAX_USER_ADDRESS) {
                        continue;
                    }
                    long uniqueId;
                    try {
                        uniqueId = reader.readU32(person + 0x0C);
                    } catch (IOException | RuntimeException unreadable) {
                        continue;
                    }
                    if (uniqueId == wanted) {
                        found = person;
                        break outer;
                    }
                }
            }
            if (found == 0) {
                System.out.println("SPIKE unique_id " + wanted + " not found");
                return;
            }
            System.out.println("SPIKE record for unique_id " + wanted + " at 0x" + Long.toHexString(found));
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            try {
                System.out.println("SPIKE type=" + classifier.classify(found).type());
            } catch (IOException | RuntimeException notAPerson) {
                System.out.println("SPIKE classification failed");
            }
            try {
                System.out.println("SPIKE name=" + FmMemoryStrings.playerName(reader, found).orElse(""));
            } catch (RuntimeException unreadable) {
                System.out.println("SPIKE name unreadable");
            }
            for (long offset = -0x400; offset < 0x400; offset += 2) {
                int u16;
                try {
                    u16 = reader.readU16(found + offset);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                if (u16 == 5 || (u16 <= 300 && offset % 8 == 0)) {
                    System.out.println("SPIKE env +0x" + Long.toHexString(offset) + " u16=" + u16);
                }
            }
            System.out.println("SPIKE env done");
        }
    }

    /**
     * Follows every heap-range pointer near the record of FM_SPIKE_UID and
     * reports target blocks containing the value FM_SPIKE_VALUE (u16) with
     * neighbouring small ints for context. Hunts linked stats structures.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_VALUE", matches = ".+")
    void scanStatBlocks() throws Exception {
        long wanted = Long.parseLong(System.getenv("FM_SPIKE_UID").trim());
        int target = Integer.parseInt(System.getenv("FM_SPIKE_VALUE").trim());
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            FmOffsets.Bounds bounds =
                    FmOffsets.peopleBounds(reader, FmOffsets.DEFAULT_BUILD, base);
            long record = findRecordByUid(reader, bounds, wanted);
            if (record == 0) {
                System.out.println("SPIKE unique_id " + wanted + " not found");
                return;
            }
            System.out.println("SPIKE record at 0x" + Long.toHexString(record));
            for (long offset = -0x400; offset < 0x400; offset += 8) {
                long pointer;
                try {
                    pointer = reader.readU64(record + offset);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                if (pointer <= 0 || pointer > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                List<String> hits = new ArrayList<>();
                for (long entry = 0; entry < 64; entry++) {
                    int value;
                    try {
                        value = reader.readU16(pointer + entry * 2L);
                    } catch (IOException | RuntimeException unreadable) {
                        break;
                    }
                    if (value == target) {
                        StringBuilder context = new StringBuilder("@" + entry + "=[");
                        for (long back = -4; back <= 4; back++) {
                            if (back != 0) {
                                context.append(',');
                            }
                            try {
                                context.append(reader.readU16(pointer + (entry + back) * 2L));
                            } catch (IOException | RuntimeException unreadable) {
                                context.append('?');
                            }
                        }
                        context.append(']');
                        hits.add(context.toString());
                        if (hits.size() >= 6) {
                            break;
                        }
                    }
                }
                if (!hits.isEmpty()) {
                    System.out.println("SPIKE block record+0x" + Long.toHexString(offset)
                            + " -> 0x" + Long.toHexString(pointer));
                    hits.forEach(hit -> System.out.println("SPIKE   " + hit));
                }
            }
            System.out.println("SPIKE statblocks done");
        }
    }

    private static long findRecordByUid(
            ProcessMemoryReader reader, FmOffsets.Bounds bounds, long wanted) throws IOException {
        System.out.println("SPIKE people table count=" + bounds.count());
        if (bounds.count() <= 0 || bounds.count() > 1_000_000L) {
            throw new IOException("implausible people count: " + bounds.count());
        }
        long slotsPerChunk = 8192;
        for (long chunkStart = 0; chunkStart < bounds.count(); chunkStart += slotsPerChunk) {
            long chunkCount = Math.min(slotsPerChunk, bounds.count() - chunkStart);
            byte[] data;
            try {
                data = reader.readBytes(bounds.start() + chunkStart * Long.BYTES,
                        (int) (chunkCount * Long.BYTES));
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            for (long slot = 0; slot < chunkCount; slot++) {
                long person = getLong(data, (int) (slot * Long.BYTES));
                if (person <= 0 || person > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                long uniqueId;
                try {
                    uniqueId = reader.readU32(person + 0x0C);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                if (uniqueId == wanted) {
                    return person;
                }
            }
        }
        return 0;
    }

    /**
     * Hunts a stats cluster for FM_SPIKE_UID: scans +-32KB around the record
     * for u16 windows holding the values of FM_SPIKE_SET (comma-separated,
     * e.g. apps,goals,assists) within 8 slots, plus float32 occurrences of
     * FM_SPIKE_RATINGS (comma-separated, e.g. 7.3,6.3,7.2).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_SET", matches = ".+")
    void scanStatCluster() throws Exception {
        int[] set = java.util.Arrays.stream(System.getenv("FM_SPIKE_SET").split(","))
                .mapToInt(token -> Integer.parseInt(token.trim()))
                .toArray();
        float[] ratings = System.getenv().getOrDefault("FM_SPIKE_RATINGS", "").isBlank() ? new float[0]
                : parseFloats(System.getenv("FM_SPIKE_RATINGS"));
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            long anchor;
            String baseEnv = System.getenv("FM_SPIKE_BASE");
            if (baseEnv != null && !baseEnv.isBlank()) {
                anchor = Long.decode(baseEnv.trim());
            } else {
                long wanted = Long.parseLong(System.getenv("FM_SPIKE_UID").trim());
                long base = FmOffsets.findGamePluginBase(reader);
                FmOffsets.Bounds bounds =
                        FmOffsets.peopleBounds(reader, FmOffsets.DEFAULT_BUILD, base);
                anchor = findRecordByUid(reader, bounds, wanted);
                if (anchor == 0) {
                    System.out.println("SPIKE unique_id " + wanted + " not found");
                    return;
                }
                System.out.println("SPIKE record at 0x" + Long.toHexString(anchor));
            }
            long window = 0x8000L;
            int span = (int) (window * 2);
            byte[] data;
            try {
                data = reader.readBytes(anchor - window, span);
            } catch (IOException | RuntimeException unreadable) {
                System.out.println("SPIKE window unreadable");
                return;
            }
            int[] u16 = new int[span / 2];
            for (int i = 0; i < u16.length; i++) {
                u16[i] = (data[2 * i] & 0xff) | ((data[2 * i + 1] & 0xff) << 8);
            }
            for (int i = 0; i + 8 <= u16.length; i++) {
                boolean all = true;
                for (int want : set) {
                    boolean found = false;
                    for (int j = 0; j < 8; j++) {
                        if (u16[i + j] == want) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    StringBuilder context = new StringBuilder("SPIKE cluster +0x")
                            .append(Long.toHexString((long) (i * 2) - window))
                            .append(" =[");
                    for (int j = 0; j < 8; j++) {
                        if (j != 0) {
                            context.append(',');
                        }
                        context.append(u16[i + j]);
                    }
                    context.append(']');
                    System.out.println(context);
                }
            }
            for (float rating : ratings) {
                int bits = Float.floatToIntBits(rating);
                byte[] pattern = new byte[]{
                        (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)};
                for (int i = 0; i + 4 <= data.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < 4; j++) {
                        if (data[i + j] != pattern[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        System.out.println("SPIKE float " + rating + " at +0x"
                                + Long.toHexString((long) i - window));
                    }
                }
            }
            System.out.println("SPIKE cluster done");
        }
    }

    /**
     * Deep version: follows heap-range pointers near the record of FM_SPIKE_UID
     * and runs the FM_SPIKE_SET co-occurrence plus FM_SPIKE_RATINGS float
     * search on +-1KB around each target. Gated by FM_SPIKE_SET.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_SET", matches = ".+")
    void scanStatDeep() throws Exception {
        long wanted = Long.parseLong(System.getenv("FM_SPIKE_UID").trim());
        int[] set = java.util.Arrays.stream(System.getenv("FM_SPIKE_SET").split(","))
                .mapToInt(token -> Integer.parseInt(token.trim()))
                .toArray();
        float[] ratings = System.getenv().getOrDefault("FM_SPIKE_RATINGS", "").isBlank() ? new float[0]
                : parseFloats(System.getenv("FM_SPIKE_RATINGS"));
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            FmOffsets.Bounds bounds =
                    FmOffsets.peopleBounds(reader, FmOffsets.DEFAULT_BUILD, base);
            long record = findRecordByUid(reader, bounds, wanted);
            if (record == 0) {
                System.out.println("SPIKE unique_id " + wanted + " not found");
                return;
            }
            System.out.println("SPIKE record at 0x" + Long.toHexString(record));
            for (long offset = -0x400; offset < 0x400; offset += 8) {
                long pointer;
                try {
                    pointer = reader.readU64(record + offset);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                if (pointer <= 0 || pointer > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                byte[] data;
                try {
                    data = reader.readBytes(pointer - 1024, 2048);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                scanBufferForSet(data, pointer - 1024, set, "block record+0x"
                        + Long.toHexString(offset));
                scanBufferForFloats(data, pointer - 1024, ratings);
            }
            System.out.println("SPIKE deep done");
        }
    }

    private static void scanBufferForSet(byte[] data, long base, int[] set, String tag) {
        int[] u16 = new int[data.length / 2];
        for (int i = 0; i < u16.length; i++) {
            u16[i] = (data[2 * i] & 0xff) | ((data[2 * i + 1] & 0xff) << 8);
        }
        for (int i = 0; i + 8 <= u16.length; i++) {
            boolean all = true;
            for (int want : set) {
                boolean found = false;
                for (int j = 0; j < 8; j++) {
                    if (u16[i + j] == want) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) {
                StringBuilder context = new StringBuilder("SPIKE cluster ").append(tag)
                        .append(" @0x").append(Long.toHexString(base + (long) i * 2))
                        .append(" =[");
                for (int j = 0; j < 8; j++) {
                    if (j != 0) {
                        context.append(',');
                    }
                    context.append(u16[i + j]);
                }
                context.append(']');
                System.out.println(context);
            }
        }
        for (int i = 0; i < data.length; i++) {
            boolean all = true;
            for (int want : set) {
                boolean found = false;
                for (int j = 0; j < 8 && i + j < data.length; j++) {
                    if ((data[i + j] & 0xff) == want) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) {
                System.out.println("SPIKE bytes " + tag + " @0x" + Long.toHexString(base + i));
                i += 8;
            }
        }
    }

    private static void scanBufferForFloats(byte[] data, long base, float[] ratings) {
        for (float rating : ratings) {
            int bits = Float.floatToIntBits(rating);
            byte[] pattern = new byte[]{
                    (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)};
            for (int i = 0; i + 4 <= data.length; i++) {
                boolean match = true;
                for (int j = 0; j < 4; j++) {
                    if (data[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    System.out.println("SPIKE float " + rating + " @0x" + Long.toHexString(base + i));
                }
            }
        }
    }

    private static float[] parseFloats(String csv) {
        String[] tokens = csv.split(",");
        float[] out = new float[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            out[i] = Float.parseFloat(tokens[i].trim());
        }
        return out;
    }

    private static int findGamePid() throws IOException {
        List<ProcessInfo> candidates = ProcessReaders.findProcesses("fm.exe");
        for (ProcessInfo candidate : candidates) {
            try (ProcessMemoryReader reader = ProcessReaders.open(candidate.pid())) {
                for (MemoryRegion region : reader.maps()) {
                    if (region.path().toLowerCase(Locale.ROOT).endsWith("fm.exe")) {
                        return candidate.pid();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        throw new IOException("fm.exe process not found among " + candidates.size() + " candidates");
    }
}
