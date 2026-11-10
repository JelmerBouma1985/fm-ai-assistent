package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.FmBuildProfile;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

public final class FmOffsets {
    private static final Logger log = LoggerFactory.getLogger(FmOffsets.class);
    public static final int DEFAULT_BUILD = 2494687; // FM27 27.0.1, Steam build 26385295
    public static final String PEOPLE_SLOT = "PeopleOffset";
    private static final int SCAN_CHUNK_BYTES = 1024 * 1024;
    // FM27 moved the data registries beyond the old 0x05200000 scan window.
    private static final long GAME_PLUGIN_SCAN_RANGE = 0x0600_0000L;
    private static final int MIN_VALID_TABLE_SCORE = 35;

    private static final Map<Integer, Long> BUILD_TO_TABLE_RVA = Map.of(
            0x25f1a7, 0x57035e8L,
            0x260bf2, 0x57055e8L,
            2494687, 0x5820628L);

    // FM's native game_plugin.dll layout is the same on Windows and under Proton.
    private static final Map<Integer, Long> BUILD_TO_CURRENT_DATE_RVA = Map.of(
            0x25f1a7, 0x56ad378L,
            0x260bf2, 0x56af378L,
            2494687, 0x57ca3b8L
    );

    // Points at the current human manager person record. Keep this build-gated:
    // accepting a stale pointer here could silently attach the wrong club context.
    private static final Map<Integer, Long> BUILD_TO_CURRENT_HUMAN_MANAGER_RVA = Map.of(
            0x25f1a7, 0x56f32c8L,
            0x260bf2, 0x56f52c8L,
            2494687, 0x5810308L
    );

    // Legacy (27.0.0+2486695) roots; an identified image translates them with NativeModuleProfile.
    private static final int LEGACY_TABLE_RVA = 0x57035e8;
    private static final int LEGACY_CURRENT_DATE_RVA = 0x56ad378;
    private static final int LEGACY_CURRENT_HUMAN_MANAGER_RVA = 0x56f32c8;
    private static final String MODULE_PROPERTY = "fmaiassistent.contracts.module";

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
            int score = tableScore(reader, candidate);
            if (score >= MIN_VALID_TABLE_SCORE) {
                DETECTED_TABLE_BASES.put(cacheKey, candidate);
                return candidate;
            }
            log.info("FM offset lookup: known RVA for build 0x{} scored {} (minimum {})",
                    Integer.toHexString(build), score, MIN_VALID_TABLE_SCORE);
        } else {
            log.info("FM offset lookup: build 0x{} has no known RVA entry", Integer.toHexString(build));
        }

        // The build number cannot tell a Game Pass image apart; its exact identity can.
        Long imageRva = knownTableRva(moduleProfile(reader, gamePluginBase), build);
        if (imageRva != null && !imageRva.equals(knownRva)) {
            long candidate = gamePluginBase + imageRva;
            if (tableScore(reader, candidate) >= MIN_VALID_TABLE_SCORE) {
                DETECTED_TABLE_BASES.put(cacheKey, candidate);
                return candidate;
            }
        }
        log.info("FM offset lookup: no verified registry root scored, scanning game_plugin.dll");

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
                .filter(region -> region.end() > gamePluginBase)
                .filter(region -> region.start() < gamePluginBase + GAME_PLUGIN_SCAN_RANGE)
                .sorted(Comparator
                        .comparing(MemoryRegion::writable).reversed()
                        .thenComparing(region -> !region.path().contains("game_plugin.dll"))
                        .thenComparingLong(MemoryRegion::size))
                .toList();
        log.info("FM offset scan: {} readable regions near game_plugin.dll base 0x{}, bounded to {} bytes per read",
                pluginRegions.size(), Long.toHexString(gamePluginBase), SCAN_CHUNK_BYTES);

        long bestTable = 0;
        int bestScore = 0;
        int unreadableChunks = 0;
        long maxSlot = SLOTS.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        int tableBytes = Math.toIntExact(maxSlot + Long.BYTES);
        int step = (SCAN_CHUNK_BYTES - tableBytes) & -Long.BYTES;
        for (MemoryRegion region : pluginRegions) {
            long end = Math.min(region.end(), gamePluginBase + GAME_PLUGIN_SCAN_RANGE);
            long readableEnd = region.end();
            // A table can straddle two adjacent readable mappings with different protections.
            for (MemoryRegion next : maps) {
                if (next.start() == readableEnd) readableEnd = next.end();
            }
            for (long cursor = Math.max(region.start(), gamePluginBase); cursor < end; cursor += step) {
                long candidateEnd = Math.min(end, cursor + step);
                int length = Math.toIntExact(Math.min(readableEnd, candidateEnd + tableBytes - 1) - cursor);
                if (length < tableBytes) continue;
                byte[] data;
                try {
                    data = reader.readBytes(cursor, length);
                } catch (IOException ex) {
                    unreadableChunks++;
                    continue;
                }
                ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int aligned = (int) ((Long.BYTES - cursor % Long.BYTES) % Long.BYTES);
                for (int offset = aligned; offset + tableBytes <= data.length && cursor + offset < candidateEnd; offset += Long.BYTES) {
                    long continentPtr = buffer.getLong(Math.toIntExact(offset + SLOTS.get("ContinentOffset")));
                    long regionPtr = buffer.getLong(Math.toIntExact(offset + SLOTS.get("RegionOffset")));
                    long peoplePtr = buffer.getLong(Math.toIntExact(offset + SLOTS.get("PeopleOffset")));
                    if (continentPtr == 0 || regionPtr == 0 || peoplePtr == 0
                            || !isReadable(maps, continentPtr + 0x80) || !isReadable(maps, continentPtr + 0x87)
                            || !isReadable(maps, regionPtr + 0x80) || !isReadable(maps, regionPtr + 0x87)
                            || !isReadable(maps, peoplePtr + 0x80) || !isReadable(maps, peoplePtr + 0x87)) {
                        continue;
                    }
                    long table = cursor + offset;
                    // Reject ordinary pointer arrays before reading all eleven registry chains.
                    if (!hasCount(reader, continentPtr, 7) || !hasCount(reader, regionPtr, 28)) continue;
                    int score = tableScore(reader, table);
                    if (score > bestScore) {
                        bestScore = score;
                        bestTable = table;
                    }
                    if (score >= MIN_VALID_TABLE_SCORE) return table;
                }
            }
        }
        if (bestScore < MIN_VALID_TABLE_SCORE) {
            log.warn("FM offset scan: best candidate scored {} (minimum {}) at 0x{}, {} chunks failed to read",
                    bestScore, MIN_VALID_TABLE_SCORE, Long.toHexString(bestTable), unreadableChunks);
            throw new IllegalStateException("FM offset table not found for game_plugin.dll base 0x"
                    + Long.toHexString(gamePluginBase));
        }
        return bestTable;
    }

    private static boolean hasCount(ProcessMemoryReader reader, long descriptor, long expected) {
        try {
            long header = reader.readU64(descriptor + 0x80);
            long start = reader.readU64(header);
            long end = reader.readU64(header + Long.BYTES);
            return start > 0 && end >= start && end - start == expected * Long.BYTES;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static int tableScore(ProcessMemoryReader reader, long tableBase) {
        Map<String, Long> counts = tableCounts(reader, tableBase);
        if (counts.isEmpty()) {
            return 0;
        }
        int score = 0;
        score += scoreExact(counts.get("ContinentOffset"), 7, 10);
        score += scoreExact(counts.get("RegionOffset"), 28, 10);
        score += scoreRange(counts.get("PeopleOffset"), 30_000, 500_000, 5);
        score += scoreRange(counts.get("TeamOffset"), 30_000, 200_000, 4);
        score += scoreRange(counts.get("ClubOffset"), 10_000, 100_000, 4);
        score += scoreRange(counts.get("CompetitionOffset"), 1_000, 50_000, 3);
        score += scoreRange(counts.get("NationOffset"), 150, 400, 3);
        score += scoreRange(counts.get("CurrencyOffset"), 50, 300, 2);
        score += scoreRange(counts.get("CityOffset"), 30_000, 300_000, 3);
        score += scoreRange(counts.get("StadiumOffset"), 5_000, 150_000, 2);
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
                if (count < 0 || count > 500_000) {
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

    /** Explicit known-build capabilities; dynamic table scanning remains available separately. */
    public static FmBuildProfile requireProfile(int build) {
        Long tableRva = BUILD_TO_TABLE_RVA.get(build);
        if (tableRva == null) {
            throw new IllegalArgumentException("Unsupported FM build 0x" + Integer.toHexString(build));
        }
        Long dateRva = BUILD_TO_CURRENT_DATE_RVA.get(build);
        return new FmBuildProfile(build, tableRva,
                dateRva == null ? OptionalLong.empty() : OptionalLong.of(dateRva),
                currentHumanManagerRva(build), null);
    }

    static Long currentDateRva(int build) {
        return BUILD_TO_CURRENT_DATE_RVA.get(build);
    }

    /**
     * Exact image identity, or null when the mapped game_plugin.dll is not a verified image.
     * The Steam build number cannot tell a Game Pass image apart, so roots prefer this.
     */
    public static String moduleProfile(ProcessMemoryReader reader, long gamePluginBase) {
        try {
            String override = System.getProperty(MODULE_PROPERTY);
            Path module = override != null && !override.isBlank() ? Path.of(override) : reader.maps().stream()
                    .filter(region -> region.contains(gamePluginBase, 1) && !region.path().isBlank())
                    .map(region -> Path.of(region.path()))
                    .findFirst().orElse(null);
            return module == null ? null : NativeModuleProfile.identify(module).orElse(null);
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    public static Long knownTableRva(String profile, int build) {
        return profileRva(profile, LEGACY_TABLE_RVA).orElseGet(() -> BUILD_TO_TABLE_RVA.get(build));
    }

    public static Long currentDateRva(String profile, int build) {
        return profileRva(profile, LEGACY_CURRENT_DATE_RVA).orElseGet(() -> currentDateRva(build));
    }

    public static OptionalLong currentHumanManagerRva(String profile, int build) {
        return profileRva(profile, LEGACY_CURRENT_HUMAN_MANAGER_RVA)
                .map(OptionalLong::of).orElseGet(() -> currentHumanManagerRva(build));
    }

    private static java.util.Optional<Long> profileRva(String profile, int legacyRva) {
        return NativeModuleProfile.mapsRva(profile, legacyRva)
                ? java.util.Optional.of((long) NativeModuleProfile.rva(profile, legacyRva))
                : java.util.Optional.empty();
    }

    public static OptionalLong currentHumanManagerRva(int build) {
        Long rva = BUILD_TO_CURRENT_HUMAN_MANAGER_RVA.get(build);
        return rva == null ? OptionalLong.empty() : OptionalLong.of(rva);
    }

    public record Bounds(long start, long end) {
        public long count() {
            return (end - start) / 8;
        }
    }
}
