package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerMatchLoadLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.time.LocalDate;

/** Passive translation of native 2DB44D0 and 303F130; never invokes FM code. */
public final class PlayerMatchLoadReader {
    private static final int MAX_BUCKETS = 256;
    private static final int MAX_OVERRIDE_PLAYERS = 500_000;
    private final PlayerMatchLoadLayout layout;

    public PlayerMatchLoadReader() { this(PlayerMatchLoadLayout.current()); }
    PlayerMatchLoadReader(PlayerMatchLoadLayout layout) { this.layout = layout; }

    /** Null means the date or a history subtype is not supported, distinct from Light. */
    public Result read(ProcessMemoryReader reader, long player, long moduleBase, LocalDate date) throws IOException {
        PlayerSnapshotValidator.validatePlayerMatchLoadLayout(layout);
        if (date == null) return null;
        int days = lookbackDays(reader, player, moduleBase);
        if (days == 0) return new Result(0, "Light", 0, 0, 0, days);
        long medical = reader.readU64(player + layout.medicalReference());
        if (medical == 0) return new Result(0, "Light", 0, 0, 0, days);
        long history = reader.readU64(medical + layout.historyReference());
        if (history == 0) return new Result(0, "Light", 0, 0, 0, days);
        long begin = reader.readU64(history), end = reader.readU64(history + Long.BYTES);
        checkVector(begin, end, Long.BYTES, MAX_BUCKETS);
        LocalDate cutoff = date.minusDays(days + 1L);
        int appearances = 0, minutes = 0;
        float ratings = 0;
        for (long address = begin; address < end; address += Long.BYTES) {
            long bucket = reader.readU64(address);
            if (reader.readU64(bucket) != moduleBase + layout.bucketVtableRva()) return null;
            int count = reader.readU8(bucket + layout.bucketCount());
            int head = reader.readU8(bucket + layout.bucketHead());
            if (count > layout.bucketCapacity() || head >= layout.bucketCapacity())
                throw new IOException("Player match history ring range anchor failed");
            for (int index = count - 1; index >= 0; index--) {
                int slot = (head + index) % layout.bucketCapacity();
                long item = reader.readU64(bucket + layout.bucketItems() + slot * (long) Long.BYTES);
                if (item == 0) continue;
                int year = reader.readU16(item + layout.itemYear());
                int day = reader.readU16(item + layout.itemDay()) & 511;
                if (!com.github.fmaiassistent.linux.GameDateFinder.validDayYear(day, year))
                    throw new IOException("Player match history date range anchor failed");
                LocalDate played = LocalDate.ofYearDay(year, day);
                if (!played.isAfter(cutoff)) break;
                long detail = reader.readU64(item + layout.itemDetail());
                if (detail == 0) continue;
                int playedMinutes = reader.readU8(detail + layout.detailMinutes());
                if (playedMinutes == 0) continue;
                appearances = (appearances + 1) & 255; // Native byte accumulator.
                minutes = (minutes + playedMinutes) & 65535;
                ratings += reader.readU8(detail + layout.detailRating()) / 10f;
            }
        }
        float average = appearances == 0 ? 0 : ratings / appearances;
        int category = classify(appearances, minutes, average, days,
                reader.readU8(player + layout.goalkeeper()) >= 15);
        return new Result(category, label(category), appearances, minutes, average, days);
    }

    private int lookbackDays(ProcessMemoryReader reader, long player, long moduleBase) throws IOException {
        long manager = reader.readU64(moduleBase + layout.managerRva());
        if (manager == 0) throw new IOException("Player match load manager is unavailable");
        int override = reader.readU8(manager + layout.overrideDays());
        if (override == 255) return 14;
        long begin = reader.readU64(manager + layout.overrideBegin());
        long end = reader.readU64(manager + layout.overrideEnd());
        checkVector(begin, end, Integer.BYTES, MAX_OVERRIDE_PLAYERS);
        if (begin == end) return 14;
        long virtualBase = reader.readU64(player + layout.virtualBasePointer());
        int displacement = reader.readI32(virtualBase + layout.personDisplacement());
        int playerIndex = reader.readI32(player + displacement + layout.personIndex());
        long low = 0, high = (end - begin) / Integer.BYTES;
        while (low < high) {
            long middle = (low + high) >>> 1;
            if (reader.readI32(begin + middle * Integer.BYTES) < playerIndex) low = middle + 1;
            else high = middle;
        }
        return begin + low * Integer.BYTES < end
                && reader.readI32(begin + low * Integer.BYTES) == playerIndex ? override : 14;
    }

    private static void checkVector(long begin, long end, int stride, int limit) throws IOException {
        if (begin < 0 || end < begin || end > ProcessMemoryReader.MAX_USER_ADDRESS
                || (end - begin) % stride != 0 || (end - begin) / stride > limit)
            throw new IOException("Player match history vector range anchor failed");
    }

    static int classify(int appearances, int minutes, float averageRating, int days, boolean goalkeeper) {
        if (appearances == 0 || days == 0) return 0;
        float frequency = appearances / (days / 7f);
        float ratio = minutes / (appearances * 90f) * (goalkeeper ? .5f : 1f);
        if (frequency >= 2.5f && ratio > .65f && averageRating >= 8) return 3;
        if (frequency > 1 && ratio > .5f && averageRating >= 6) return 2;
        if (frequency < 1) return 0;
        return ratio >= .5f || frequency >= 2 ? 1 : 0;
    }

    private static String label(int category) {
        return switch (category) { case 0 -> "Light"; case 1 -> "Medium"; case 2 -> "Heavy";
            case 3 -> "Very Heavy"; default -> throw new IllegalArgumentException("Unknown match load"); };
    }

    public record Result(int category, String label, int appearances, int minutes, float averageRating, int days) { }
}
