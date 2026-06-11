package com.example.fmgenie26.player;

import com.example.fmgenie26.linux.LinuxProcessReader;
import com.example.fmgenie26.linux.MemoryRegion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameDateFinder {
    private static final byte[] SIGNATURE = "client_periodic_api_stats1".getBytes(StandardCharsets.US_ASCII);
    private static final int SIGNATURE_DATE_DELTA = 0xAE;
    private static final List<String> MONTHS = Arrays.asList(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+("
            + String.join("|", MONTHS) + ")\\s+(20\\d{2})\\b");
    private static final byte[] CURRENT_DATE_ASCII = "Current date:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CURRENT_DATE_UTF16 = "Current date:".getBytes(StandardCharsets.UTF_16LE);
    private static final Pattern TELEMETRY_CURRENT_DATE = Pattern.compile("Current date:\\s*(\\d{1,2})/(\\d{1,2})/(20\\d{2})");
    private static final Pattern PLAYER_COUNT = Pattern.compile("Player Count:\\s*(\\d+)");
    private static final Pattern SAVES_LOADED = Pattern.compile("Saves Loaded:\\s*(\\d+)");
    private static final Pattern CONNECTIONS = Pattern.compile("Connections:\\s*(\\d+)");
    private static final Pattern GAME_VERSION = Pattern.compile("\"game_(?:creation|last_saved)_version\":\"(\\d+)\"");

    public Optional<LocalDate> find(LinuxProcessReader reader) throws IOException {
        return find(reader, 0);
    }

    public Optional<LocalDate> find(LinuxProcessReader reader, long expectedPlayerCount) throws IOException {
        Optional<LocalDate> telemetry = findTelemetryGameDate(reader, expectedPlayerCount);
        if (telemetry.isPresent()) {
            return telemetry;
        }
        Optional<LocalDate> marker = findMarkerGameDate(reader);
        return marker.isPresent() ? marker : findRenderedGameDate(reader);
    }

    private Optional<LocalDate> findTelemetryGameDate(LinuxProcessReader reader, long expectedPlayerCount) throws IOException {
        List<TelemetryDate> candidates = new java.util.ArrayList<>();
        for (MemoryRegion region : reader.maps()) {
            if (!region.readable() || region.size() > 128L * 1024 * 1024 || region.start() < 0x70000000L) {
                continue;
            }
            byte[] data;
            try {
                data = reader.readBytes(region.start(), Math.toIntExact(region.size()));
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            scanTelemetryBuffer(data, CURRENT_DATE_ASCII, StandardCharsets.UTF_8, candidates);
            scanTelemetryBuffer(data, CURRENT_DATE_UTF16, StandardCharsets.UTF_16LE, candidates);
        }
        if (expectedPlayerCount > 0) {
            return candidates.stream()
                    .filter(candidate -> candidate.playerCount() > 0)
                    .min(Comparator.<TelemetryDate>comparingLong(candidate -> Math.abs(candidate.playerCount() - expectedPlayerCount))
                            .thenComparing(candidate -> candidate.activeManager() ? 0 : 1)
                            .thenComparing(candidate -> candidate.connections() == 0 ? 0 : 1)
                            .thenComparing(Comparator.comparingInt(TelemetryDate::savesLoaded).reversed())
                            .thenComparing(Comparator.comparing(TelemetryDate::date).reversed())
                            .thenComparingInt(TelemetryDate::playerCount))
                    .map(TelemetryDate::date);
        }
        int newestVersion = candidates.stream().mapToInt(TelemetryDate::gameVersion).max().orElse(0);
        if (newestVersion > 0) {
            return candidates.stream()
                    .filter(candidate -> candidate.gameVersion() == newestVersion)
                    .max(Comparator.<TelemetryDate, LocalDate>comparing(TelemetryDate::date)
                            .thenComparingInt(TelemetryDate::playerCount))
                    .map(TelemetryDate::date);
        }
        return candidates.stream()
                .max(Comparator.<TelemetryDate>comparingInt(TelemetryDate::playerCount)
                        .thenComparing(TelemetryDate::date))
                .map(TelemetryDate::date);
    }

    private static void scanTelemetryBuffer(byte[] data, byte[] needle, java.nio.charset.Charset charset, List<TelemetryDate> candidates) {
        int cursor = 0;
        while (true) {
            int index = indexOf(data, needle, cursor);
            if (index < 0) {
                break;
            }
            int from = Math.max(0, index - 260);
            int to = Math.min(data.length, index + 520);
            if (charset.equals(StandardCharsets.UTF_16LE) && ((to - from) % 2 != 0)) {
                to--;
            }
            String text = new String(Arrays.copyOfRange(data, from, to), charset).replace('\0', '.');
            Matcher dateMatch = TELEMETRY_CURRENT_DATE.matcher(text);
            while (dateMatch.find()) {
                if (!text.contains("Game Started: true")) {
                    continue;
                }
                LocalDate date;
                try {
                    date = LocalDate.of(
                            Integer.parseInt(dateMatch.group(3)),
                            Integer.parseInt(dateMatch.group(2)),
                            Integer.parseInt(dateMatch.group(1)));
                } catch (DateTimeException ex) {
                    continue;
                }
                if (date.isBefore(LocalDate.of(2024, 1, 1)) || date.isAfter(LocalDate.of(2035, 12, 31))) {
                    continue;
                }
                Matcher playerCount = PLAYER_COUNT.matcher(text);
                int count = 0;
                if (playerCount.find()) {
                    count = Integer.parseInt(playerCount.group(1));
                }
                candidates.add(new TelemetryDate(date, count, savesLoaded(text), connections(text), gameVersion(text), activeManager(text)));
            }
            cursor = index + needle.length;
        }
    }

    private static int savesLoaded(String text) {
        Matcher matcher = SAVES_LOADED.matcher(text);
        int saves = 0;
        while (matcher.find()) {
            saves = Math.max(saves, Integer.parseInt(matcher.group(1)));
        }
        return saves;
    }

    private static int connections(String text) {
        Matcher matcher = CONNECTIONS.matcher(text);
        int connections = Integer.MAX_VALUE;
        while (matcher.find()) {
            connections = Math.min(connections, Integer.parseInt(matcher.group(1)));
        }
        return connections == Integer.MAX_VALUE ? -1 : connections;
    }

    private static int gameVersion(String text) {
        Matcher matcher = GAME_VERSION.matcher(text);
        int version = 0;
        while (matcher.find()) {
            version = Math.max(version, Integer.parseInt(matcher.group(1)));
        }
        return version;
    }

    private static boolean activeManager(String text) {
        int currentDate = text.indexOf("Current date:");
        int connections = text.indexOf("Connections:", currentDate);
        if (currentDate < 0 || connections < 0 || connections <= currentDate) {
            return false;
        }
        String between = text.substring(currentDate, connections);
        return between.contains(" - ") && !between.contains("(on holiday)");
    }

    private Optional<LocalDate> findMarkerGameDate(LinuxProcessReader reader) throws IOException {
        for (MemoryRegion region : reader.maps()) {
            if (!region.readable() || !region.writable() || region.size() > 64L * 1024 * 1024 || region.start() < 0x70000000L) {
                continue;
            }
            byte[] data;
            try {
                data = reader.readBytes(region.start(), Math.toIntExact(region.size()));
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            int cursor = 0;
            while (true) {
                int index = indexOf(data, SIGNATURE, cursor);
                if (index < 0) {
                    break;
                }
                int dateOffset = index - SIGNATURE_DATE_DELTA;
                if (dateOffset >= 0 && dateOffset + 4 <= data.length) {
                    int day = u16(data, dateOffset);
                    int year = u16(data, dateOffset + 2);
                    if (validDayYear(day, year) && year >= 2020) {
                        return Optional.of(dayYearToDate(day, year));
                    }
                }
                cursor = index + 1;
            }
        }
        return Optional.empty();
    }

    private Optional<LocalDate> findRenderedGameDate(LinuxProcessReader reader) throws IOException {
        Map<LocalDate, Integer> counts = new HashMap<>();
        Map<LocalDate, Integer> scores = new HashMap<>();
        for (MemoryRegion region : reader.maps()) {
            if (!region.readable() || !region.writable() || region.size() > 128L * 1024 * 1024 || region.start() < 0x70000000L) {
                continue;
            }
            byte[] data;
            try {
                data = reader.readBytes(region.start(), Math.toIntExact(region.size()));
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            for (String month : MONTHS) {
                byte[] needle = month.getBytes(StandardCharsets.UTF_16LE);
                int cursor = 0;
                while (true) {
                    int index = indexOf(data, needle, cursor);
                    if (index < 0) {
                        break;
                    }
                    int from = Math.max(0, index - 80);
                    int to = Math.min(data.length, index + 120);
                    if ((to - from) % 2 != 0) {
                        to--;
                    }
                    String text = new String(Arrays.copyOfRange(data, from, to), StandardCharsets.UTF_16LE);
                    var matcher = DATE_PATTERN.matcher(text);
                    while (matcher.find()) {
                        int day = Integer.parseInt(matcher.group(1));
                        int monthIndex = MONTHS.indexOf(matcher.group(2)) + 1;
                        int year = Integer.parseInt(matcher.group(3));
                        LocalDate value;
                        try {
                            value = LocalDate.of(year, monthIndex, day);
                        } catch (DateTimeException ex) {
                            continue;
                        }
                        if (value.isBefore(LocalDate.of(2024, 1, 1)) || value.isAfter(LocalDate.of(2035, 12, 31))) {
                            continue;
                        }
                        counts.merge(value, 1, Integer::sum);
                        String context = text.substring(Math.max(0, matcher.start() - 80), Math.min(text.length(), matcher.end() + 80));
                        if (context.contains("QuickChatHeader")) {
                            scores.merge(value, 100, Integer::sum);
                        }
                        if (context.contains("AdditionalDetails")) {
                            scores.merge(value, 80, Integer::sum);
                        }
                        if (context.contains("PortalMessagesTool")) {
                            scores.merge(value, 120, Integer::sum);
                        }
                        if (context.contains("InProgress:")) {
                            scores.merge(value, 40, Integer::sum);
                        }
                        if (context.contains("last_saved") || context.contains("LastSave")) {
                            scores.merge(value, -25, Integer::sum);
                        }
                        if (context.contains("announced on") || context.contains("Frame ")) {
                            scores.merge(value, -25, Integer::sum);
                        }
                    }
                    cursor = index + 2;
                }
            }
        }
        Optional<Map.Entry<LocalDate, Integer>> positive = scores.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .max(Comparator.<Map.Entry<LocalDate, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(e -> counts.getOrDefault(e.getKey(), 0)));
        if (positive.isPresent()) {
            return Optional.of(positive.get().getKey());
        }
        return Optional.empty();
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

    private static int u16(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private record TelemetryDate(LocalDate date, int playerCount, int savesLoaded, int connections, int gameVersion, boolean activeManager) {
    }
}
