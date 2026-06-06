package com.example.fmgenie26.fm;

import com.example.fmgenie26.linux.LinuxProcessReader;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FmOffsets {
    public static final int DEFAULT_BUILD = 0x235144;
    public static final String PEOPLE_SLOT = "PeopleOffset";

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

    public static long findGamePluginBase(LinuxProcessReader reader) throws IOException {
        return reader.maps().stream()
                .filter(region -> region.path().contains("game_plugin.dll"))
                .mapToLong(region -> region.start())
                .min()
                .orElseThrow(() -> new IllegalStateException("game_plugin.dll not found in maps"));
    }

    public static Bounds peopleBounds(LinuxProcessReader reader, int build, Long gamePluginBase) throws IOException {
        return tableBounds(reader, build, gamePluginBase, PEOPLE_SLOT);
    }

    public static Bounds tableBounds(LinuxProcessReader reader, int build, Long gamePluginBase, String slotName) throws IOException {
        long base = gamePluginBase == null ? findGamePluginBase(reader) : gamePluginBase;
        long tableRva = tableRva(build);
        Long slot = SLOTS.get(slotName);
        if (slot == null) {
            throw new IllegalArgumentException("unknown table slot: " + slotName);
        }
        long slotPtr = reader.readU64(base + tableRva + slot);
        long offsetValue = reader.readU64(slotPtr + 0x80);
        return new Bounds(reader.readU64(offsetValue), reader.readU64(offsetValue + 8));
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
