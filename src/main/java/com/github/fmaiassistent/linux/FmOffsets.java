package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FmOffsets {
    public static final int DEFAULT_BUILD = 0x235144;
    public static final String PEOPLE_SLOT = "PeopleOffset";
    private static final long MAX_SCAN_REGION_SIZE = 80_000_000L;
    private static final long GAME_PLUGIN_SCAN_RANGE = 0x0520_0000L;
    private static final int MIN_VALID_TABLE_SCORE = 35;

    private static final Map<Integer, Long> BUILD_TO_TABLE_RVA = Map.ofEntries(
            Map.entry(0x21eecd, 0x4df03b0L), Map.entry(0x21f273, 0x4df13b0L),
            Map.entry(0x21f34c, 0x4d12100L), Map.entry(0x21f739, 0x4df23f0L),
            Map.entry(0x21fe2e, 0x4df23f0L), Map.entry(0x21ff70, 0x4df23f0L),
            Map.entry(0x2202d1, 0x4df33f0L), Map.entry(0x2202da, 0x4df4420L),
            Map.entry(0x2202db, 0x4d2b270L), Map.entry(0x220ca2, 0x4df9330L),
            Map.entry(0x220ca3, 0x4d31180L), Map.entry(0x22191e, 0x4e05340L),
            Map.entry(0x22191f, 0x4d3d1d0L), Map.entry(0x221920, 0x4d250a0L),
            Map.entry(0x222a65, 0x4e0d350L), Map.entry(0x222a9e, 0x4e0d350L),
            Map.entry(0x222ad8, 0x4d461f0L), Map.entry(0x2241d3, 0x4d6b530L),
            Map.entry(0x2242f0, 0x4e4b7c0L), Map.entry(0x2243a4, 0x4e4b7c0L),
            Map.entry(0x2243a5, 0x4d84660L), Map.entry(0x224c5b, 0x4e54940L),
            Map.entry(0x224dbf, 0x4e56940L), Map.entry(0x224dc0, 0x4d8f7a0L),
            Map.entry(0x226a5a, 0x4d024c0L), Map.entry(0x226ba1, 0x4de1760L),
            Map.entry(0x226ba2, 0x4d1a5f0L), Map.entry(0x228bdb, 0x4d1b4f0L),
            Map.entry(0x2291c7, 0x4dfa780L), Map.entry(0x2291c8, 0x4d33610L),
            Map.entry(0x22973c, 0x4dfa780L), Map.entry(0x22973d, 0x4d34610L),
            Map.entry(0x22b6ff, 0x4dfc0c0L), Map.entry(0x22b700, 0x4d35f70L),
            Map.entry(0x22b701, 0x4d1ce40L), Map.entry(0x22bc1f, 0x4dfc0c0L),
            Map.entry(0x22bc20, 0x4d34f70L), Map.entry(0x22d6e7, 0x4df9ca0L),
            Map.entry(0x22d6e8, 0x4d32b40L), Map.entry(0x22d6e9, 0x4d19a10L),
            Map.entry(0x22e3ef, 0x4df9ca0L), Map.entry(0x22e5fd, 0x4d19a10L),
            Map.entry(0x235144, 0x4e47490L), Map.entry(0x235145, 0x4d80320L),
            Map.entry(0x235d1d, 0x4d67200L)
    );

    private static final Map<String, Long> SLOTS = new LinkedHashMap<>();
    private static final Map<String, Long> DETECTED_TABLE_BASES = new ConcurrentHashMap<>();

    static {
        SLOTS.put("CityOffset", 0x0F0L);
        SLOTS.put("ClubOffset", 0x0F8L);
        SLOTS.put("CompetitionOffset", 0x100L);
        SLOTS.put("ContinentOffset", 0x108L);
        SLOTS.put("NationOffset", 0x110L);
        SLOTS.put("CurrencyOffset", 0x118L);
        SLOTS.put("PeopleOffset", 0x150L);
        SLOTS.put("RegionOffset", 0x160L);
        SLOTS.put("StadiumOffset", 0x168L);
        SLOTS.put("TeamOffset", 0x180L);
        SLOTS.put("AgreementOffset", 0x1A0L);
    }

    private FmOffsets() {
    }

    public static long findGamePluginBase(ProcessMemoryReader reader) throws IOException {
        return reader.maps().stream()
                .filter(region -> region.path().contains("game_plugin.dll"))
                .mapToLong(region -> region.start())
                .min()
                .orElseThrow(() -> new IllegalStateException("game_plugin.dll not found in maps"));
    }

    public static Bounds peopleBounds(ProcessMemoryReader reader, int build, Long gamePluginBase) throws IOException {
        return tableBounds(reader, build, gamePluginBase, PEOPLE_SLOT);
    }

    public static Bounds tableBounds(ProcessMemoryReader reader, int build, Long gamePluginBase, String slotName) throws IOException {
        long base = gamePluginBase == null ? findGamePluginBase(reader) : gamePluginBase;
        Long slot = SLOTS.get(slotName);
        if (slot == null) {
            throw new IllegalArgumentException("unknown table slot: " + slotName);
        }
        long tableBase = findOffsetTableBase(reader, build, base);
        long slotPtr = reader.readU64(tableBase + slot);
        long offsetValue = reader.readU64(slotPtr + 0x80);
        return new Bounds(reader.readU64(offsetValue), reader.readU64(offsetValue + 8));
    }

    private static long findOffsetTableBase(ProcessMemoryReader reader, int build, long gamePluginBase) throws IOException {
        String cacheKey = reader.pid() + ":0x" + Long.toHexString(gamePluginBase);
        Long cached = DETECTED_TABLE_BASES.get(cacheKey);
        if (cached != null && tableScore(reader, cached) >= MIN_VALID_TABLE_SCORE) {
            return cached;
        }

        Long knownRva = BUILD_TO_TABLE_RVA.get(build);
        if (knownRva != null) {
            long candidate = gamePluginBase + knownRva;
            if (tableScore(reader, candidate) >= MIN_VALID_TABLE_SCORE) {
                DETECTED_TABLE_BASES.put(cacheKey, candidate);
                return candidate;
            }
        }

        long detected = scanOffsetTableBase(reader, gamePluginBase);
        DETECTED_TABLE_BASES.put(cacheKey, detected);
        return detected;
    }

    private static long scanOffsetTableBase(ProcessMemoryReader reader, long gamePluginBase) throws IOException {
        List<MemoryRegion> maps = reader.maps().stream()
                .filter(MemoryRegion::readable)
                .sorted(Comparator.comparingLong(MemoryRegion::start))
                .toList();
        List<MemoryRegion> pluginRegions = maps.stream()
                .filter(region -> region.start() >= gamePluginBase)
                .filter(region -> region.start() < gamePluginBase + GAME_PLUGIN_SCAN_RANGE)
                .filter(region -> region.size() <= MAX_SCAN_REGION_SIZE)
                .sorted(Comparator
                        .comparing(MemoryRegion::writable).reversed()
                        .thenComparingLong(MemoryRegion::size))
                .toList();

        long bestTable = 0;
        int bestScore = 0;
        long maxSlot = SLOTS.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        for (MemoryRegion region : pluginRegions) {
            byte[] data;
            try {
                data = reader.readBytes(region.start(), Math.toIntExact(region.size()));
            } catch (IOException | ArithmeticException ex) {
                continue;
            }
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int maxOffset = Math.toIntExact(data.length - maxSlot - Long.BYTES);
            for (int offset = 0; offset < maxOffset; offset += Long.BYTES) {
                long continentPtr = buffer.getLong(Math.toIntExact(offset + SLOTS.get("ContinentOffset")));
                long regionPtr = buffer.getLong(Math.toIntExact(offset + SLOTS.get("RegionOffset")));
                long peoplePtr = buffer.getLong(Math.toIntExact(offset + SLOTS.get("PeopleOffset")));
                if (!isReadable(maps, continentPtr + 0x88)
                        || !isReadable(maps, regionPtr + 0x88)
                        || !isReadable(maps, peoplePtr + 0x88)) {
                    continue;
                }
                long table = region.start() + offset;
                int score = tableScore(reader, table);
                if (score > bestScore) {
                    bestScore = score;
                    bestTable = table;
                }
            }
        }
        if (bestScore < MIN_VALID_TABLE_SCORE) {
            throw new IllegalStateException("FM offset table not found for game_plugin.dll base 0x"
                    + Long.toHexString(gamePluginBase));
        }
        return bestTable;
    }

    private static int tableScore(ProcessMemoryReader reader, long tableBase) {
        Map<String, Long> counts = tableCounts(reader, tableBase);
        if (counts.isEmpty()) {
            return 0;
        }
        int score = 0;
        score += scoreExact(counts.get("ContinentOffset"), 7, 10);
        score += scoreExact(counts.get("RegionOffset"), 28, 10);
        score += scoreRange(counts.get("PeopleOffset"), 30_000, 120_000, 5);
        score += scoreRange(counts.get("TeamOffset"), 30_000, 90_000, 4);
        score += scoreRange(counts.get("ClubOffset"), 10_000, 50_000, 4);
        score += scoreRange(counts.get("CompetitionOffset"), 1_000, 20_000, 3);
        score += scoreRange(counts.get("NationOffset"), 150, 400, 3);
        score += scoreRange(counts.get("CurrencyOffset"), 50, 300, 2);
        score += scoreRange(counts.get("CityOffset"), 30_000, 120_000, 3);
        score += scoreRange(counts.get("StadiumOffset"), 5_000, 50_000, 2);
        score += scoreRange(counts.get("AgreementOffset"), 0, 1_000, 1);
        return score;
    }

    private static Map<String, Long> tableCounts(ProcessMemoryReader reader, long tableBase) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Long> slot : SLOTS.entrySet()) {
                long slotPtr = reader.readU64(tableBase + slot.getValue());
                long offsetValue = reader.readU64(slotPtr + 0x80);
                long start = reader.readU64(offsetValue);
                long end = reader.readU64(offsetValue + 8);
                if (end < start || (end - start) % 8 != 0) {
                    return Map.of();
                }
                long count = (end - start) / 8;
                if (count < 0 || count > 200_000) {
                    return Map.of();
                }
                counts.put(slot.getKey(), count);
            }
        } catch (IOException | RuntimeException ex) {
            return Map.of();
        }
        return counts;
    }

    private static int scoreExact(Long value, long expected, int points) {
        return value != null && value == expected ? points : 0;
    }

    private static int scoreRange(Long value, long min, long max, int points) {
        return value != null && value >= min && value <= max ? points : 0;
    }

    private static boolean isReadable(List<MemoryRegion> sortedRegions, long address) {
        int low = 0;
        int high = sortedRegions.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            MemoryRegion region = sortedRegions.get(mid);
            if (address < region.start()) {
                high = mid - 1;
            } else if (address >= region.end()) {
                low = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    public static long tableRva(int build) {
        Long rva = BUILD_TO_TABLE_RVA.get(build);
        if (rva == null) {
            throw new IllegalArgumentException("unknown build 0x" + Integer.toHexString(build));
        }
        return rva;
    }

    public record Bounds(long start, long end) {
        public long count() {
            return (end - start) / 8;
        }
    }
}
