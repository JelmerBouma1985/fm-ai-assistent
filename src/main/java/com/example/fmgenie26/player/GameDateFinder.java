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
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b(20\\d{2})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern SLASH_DATE_PATTERN = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(20\\d{2})\\b");
    private static final Pattern DASH_DATE_PATTERN = Pattern.compile("\\b(\\d{1,2})-(\\d{1,2})-(20\\d{2})\\b");
    private static final byte[] CURRENT_DATE_ASCII = "Current date:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CURRENT_DATE_UTF16 = "Current date:".getBytes(StandardCharsets.UTF_16LE);
    private static final Pattern PLAYER_COUNT = Pattern.compile("Player Count:\\s*(\\d+)");
    private static final Pattern SAVES_LOADED = Pattern.compile("Saves Loaded:\\s*(\\d+)");
    private static final Pattern CONNECTIONS = Pattern.compile("Connections:\\s*(\\d+)");
    private static final Pattern GAME_VERSION = Pattern.compile("\"game_(?:creation|last_saved)_version\":\"(\\d+)\"");
    private static final long MAX_SCAN_REGION_SIZE = 512L * 1024 * 1024;
    private static final int SCAN_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int SCAN_CHUNK_OVERLAP = 4096;

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
            if (!scannable(region, MAX_SCAN_REGION_SIZE)) {
                continue;
            }
            scanRegion(reader, region, data -> {
                scanTelemetryBuffer(data, CURRENT_DATE_ASCII, StandardCharsets.UTF_8, candidates);
                scanTelemetryBuffer(data, CURRENT_DATE_UTF16, StandardCharsets.UTF_16LE, candidates);
            });
            Optional<LocalDate> confident = confidentTelemetryDate(candidates, expectedPlayerCount);
            if (confident.isPresent()) {
                return confident;
            }
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

    private static Optional<LocalDate> confidentTelemetryDate(List<TelemetryDate> candidates, long expectedPlayerCount) {
        return candidates.stream()
                .filter(candidate -> candidate.activeManager())
                .filter(candidate -> candidate.playerCount() > 0)
                .filter(candidate -> candidate.connections() == 0)
                .filter(candidate -> expectedPlayerCount <= 0
                        || Math.abs(candidate.playerCount() - expectedPlayerCount) <= Math.max(10, expectedPlayerCount / 20))
                .max(Comparator.<TelemetryDate>comparingInt(TelemetryDate::savesLoaded)
                        .thenComparing(TelemetryDate::date)
                        .thenComparingInt(TelemetryDate::gameVersion))
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
            for (DateMatch dateMatch : currentDateMatches(text)) {
                if (!text.contains("Game Started: true")) {
                    continue;
                }
                LocalDate date = dateMatch.date();
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
            if (!scannable(region, MAX_SCAN_REGION_SIZE) || !region.writable()) {
                continue;
            }
            try {
                scanRegion(reader, region, data -> {
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
                                throw new DateFoundException(dayYearToDate(day, year));
                            }
                        }
                        cursor = index + 1;
                    }
                });
            } catch (DateFoundException ex) {
                return Optional.of(ex.date());
            }
        }
        return Optional.empty();
    }

    private static void scanRegion(LinuxProcessReader reader, MemoryRegion region, BufferScanner scanner) throws IOException {
        long cursor = region.start();
        while (cursor < region.end()) {
            int size = (int) Math.min(SCAN_CHUNK_SIZE, region.end() - cursor);
            try {
                scanner.scan(reader.readBytes(cursor, size));
            } catch (DateFoundException ex) {
                throw ex;
            } catch (IOException | RuntimeException ignored) {
            }
            long next = cursor + size;
            if (next >= region.end()) {
                break;
            }
            cursor = Math.max(cursor + 1, next - SCAN_CHUNK_OVERLAP);
        }
    }

    private Optional<LocalDate> findRenderedGameDate(LinuxProcessReader reader) throws IOException {
        Map<LocalDate, Integer> counts = new HashMap<>();
        Map<LocalDate, Integer> scores = new HashMap<>();
        for (MemoryRegion region : reader.maps()) {
            if (!scannable(region, MAX_SCAN_REGION_SIZE)) {
                continue;
            }
            scanRegion(reader, region, data -> {
                scanRenderedBuffer(data, StandardCharsets.UTF_16LE, counts, scores);
                scanRenderedBuffer(data, StandardCharsets.UTF_8, counts, scores);
            });
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

    private static boolean scannable(MemoryRegion region, long maxSize) {
        if (!region.readable() || region.size() <= 0 || region.size() > maxSize) {
            return false;
        }
        String path = region.path();
        return path.isBlank()
                || path.startsWith("[")
                || path.contains("/steamapps/")
                || path.contains("/SteamLibrary/")
                || path.contains("/compatdata/")
                || path.contains("/Proton")
                || path.contains("/wine")
                || path.startsWith("/memfd:wine")
                || path.startsWith("/dev/shm/wine")
                || path.contains("/fm.exe")
                || path.contains("/FM26");
    }

    private static void scanRenderedBuffer(
            byte[] data,
            java.nio.charset.Charset charset,
            Map<LocalDate, Integer> counts,
            Map<LocalDate, Integer> scores) {
        for (String month : MONTHS) {
            byte[] needle = month.getBytes(charset);
            int cursor = 0;
            while (true) {
                int index = indexOf(data, needle, cursor);
                if (index < 0) {
                    break;
                }
                int from = Math.max(0, index - 160);
                int to = Math.min(data.length, index + 240);
                if (charset.equals(StandardCharsets.UTF_16LE) && ((to - from) % 2 != 0)) {
                    to--;
                }
                String text = new String(Arrays.copyOfRange(data, from, to), charset).replace('\0', '.');
                scoreDateMatches(text, counts, scores);
                cursor = index + Math.max(1, needle.length);
            }
        }
        int cursor = 0;
        while (true) {
            byte[] needle = CURRENT_DATE_ASCII;
            if (charset.equals(StandardCharsets.UTF_16LE)) {
                needle = CURRENT_DATE_UTF16;
            }
            int index = indexOf(data, needle, cursor);
            if (index < 0) {
                break;
            }
            int from = Math.max(0, index - 160);
            int to = Math.min(data.length, index + 240);
            if (charset.equals(StandardCharsets.UTF_16LE) && ((to - from) % 2 != 0)) {
                to--;
            }
            String text = new String(Arrays.copyOfRange(data, from, to), charset).replace('\0', '.');
            scoreDateMatches(text, counts, scores);
            cursor = index + Math.max(1, needle.length);
        }
    }

    private static void scoreDateMatches(String text, Map<LocalDate, Integer> counts, Map<LocalDate, Integer> scores) {
        for (DateMatch match : dateMatches(text)) {
            LocalDate value = match.date();
            if (value.isBefore(LocalDate.of(2024, 1, 1)) || value.isAfter(LocalDate.of(2035, 12, 31))) {
                continue;
            }
            counts.merge(value, 1, Integer::sum);
            String context = text.substring(Math.max(0, match.start() - 120), Math.min(text.length(), match.end() + 120));
            int score = 0;
            if (context.contains("Current date:")) {
                score += 200;
            }
            if (context.contains("QuickChatHeader")) {
                score += 100;
            }
            if (context.contains("AdditionalDetails")) {
                score += 80;
            }
            if (context.contains("PortalMessagesTool")) {
                score += 120;
            }
            if (context.contains("InProgress:")) {
                score += 40;
            }
            if (context.contains("last_saved") || context.contains("LastSave")) {
                score -= 25;
            }
            if (context.contains("announced on") || context.contains("Frame ")) {
                score -= 25;
            }
            if (score != 0) {
                scores.merge(value, score, Integer::sum);
            }
        }
    }

    private static List<DateMatch> dateMatches(String text) {
        List<DateMatch> matches = new java.util.ArrayList<>();
        Matcher wordDate = DATE_PATTERN.matcher(text);
        while (wordDate.find()) {
            addDate(matches, wordDate.start(), wordDate.end(),
                    Integer.parseInt(wordDate.group(3)),
                    MONTHS.indexOf(wordDate.group(2)) + 1,
                    Integer.parseInt(wordDate.group(1)));
        }
        Matcher slashDate = SLASH_DATE_PATTERN.matcher(text);
        while (slashDate.find()) {
            addDate(matches, slashDate.start(), slashDate.end(),
                    Integer.parseInt(slashDate.group(3)),
                    Integer.parseInt(slashDate.group(2)),
                    Integer.parseInt(slashDate.group(1)));
        }
        Matcher dashDate = DASH_DATE_PATTERN.matcher(text);
        while (dashDate.find()) {
            addDate(matches, dashDate.start(), dashDate.end(),
                    Integer.parseInt(dashDate.group(3)),
                    Integer.parseInt(dashDate.group(2)),
                    Integer.parseInt(dashDate.group(1)));
        }
        Matcher isoDate = ISO_DATE_PATTERN.matcher(text);
        while (isoDate.find()) {
            addDate(matches, isoDate.start(), isoDate.end(),
                    Integer.parseInt(isoDate.group(1)),
                    Integer.parseInt(isoDate.group(2)),
                    Integer.parseInt(isoDate.group(3)));
        }
        return matches;
    }

    private static List<DateMatch> currentDateMatches(String text) {
        List<DateMatch> matches = new java.util.ArrayList<>();
        int cursor = 0;
        while (true) {
            int currentDate = text.indexOf("Current date:", cursor);
            if (currentDate < 0) {
                break;
            }
            int from = currentDate + "Current date:".length();
            String tail = text.substring(from, Math.min(text.length(), from + 80));
            for (DateMatch match : dateMatches(tail)) {
                matches.add(new DateMatch(match.date(), from + match.start(), from + match.end()));
            }
            cursor = from;
        }
        return matches;
    }

    private static void addDate(List<DateMatch> matches, int start, int end, int year, int month, int day) {
        try {
            matches.add(new DateMatch(LocalDate.of(year, month, day), start, end));
        } catch (DateTimeException ignored) {
        }
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

    @FunctionalInterface
    private interface BufferScanner {
        void scan(byte[] data) throws IOException;
    }

    private static class DateFoundException extends RuntimeException {
        private final LocalDate date;

        DateFoundException(LocalDate date) {
            this.date = date;
        }

        LocalDate date() {
            return date;
        }
    }

    private record TelemetryDate(LocalDate date, int playerCount, int savesLoaded, int connections, int gameVersion, boolean activeManager) {
    }

    private record DateMatch(LocalDate date, int start, int end) {
    }
}
