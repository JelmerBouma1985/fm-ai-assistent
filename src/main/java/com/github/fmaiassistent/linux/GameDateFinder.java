package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;

public class GameDateFinder {
    private static final int CURRENT_DATE_DAY_MASK = 0x01FF;

    public Optional<LocalDate> find(ProcessMemoryReader reader) throws IOException {
        return find(reader, 0);
    }

    public Optional<LocalDate> find(ProcessMemoryReader reader, long expectedPlayerCount) throws IOException {
        return find(reader, expectedPlayerCount, FmOffsets.DEFAULT_BUILD, null);
    }

    public Optional<LocalDate> find(
            ProcessMemoryReader reader,
            long expectedPlayerCount,
            int build,
            Long gamePluginBase) {
        Long dateRva = FmOffsets.currentDateRva(build);
        if (dateRva == null) {
            return Optional.empty();
        }

        try {
            long base = gamePluginBase == null ? FmOffsets.findGamePluginBase(reader) : gamePluginBase;
            int day = reader.readU16(base + dateRva) & CURRENT_DATE_DAY_MASK;
            int year = reader.readU16(base + dateRva + Short.BYTES);
            return year >= 2024 && validDayYear(day, year)
                    ? Optional.of(dayYearToDate(day, year))
                    : Optional.empty();
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static boolean validDayYear(int day, int year) {
        if (year < 1901 || year > 2100) {
            return false;
        }
        int maxDay = LocalDate.of(year, 12, 31).getDayOfYear();
        return day >= 1 && day <= maxDay;
    }

    public static LocalDate dayYearToDate(int day, int year) {
        return LocalDate.of(year, 1, 1).plusDays(day - 1L);
    }
}
