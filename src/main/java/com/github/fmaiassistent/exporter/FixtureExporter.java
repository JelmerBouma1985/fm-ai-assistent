package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.linux.GameDateFinder;
import com.github.fmaiassistent.managedclub.ManagedClubIdentity;
import com.github.fmaiassistent.managedclub.ManagedClubMemoryReader;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the human-managed first team's match records from FM26 memory. */
@Component
public class FixtureExporter {
    public static final List<String> FIELD_NAMES = List.of(
            "sourceAddress", "kickoffDate", "kickoffTime", "competition",
            "homeClub", "awayClub", "opponent", "venue", "home");

    static final int MATCH_RECORD_BYTES = 0x48;
    static final int SECOND_TEAM_REL = 0x08;
    static final int COMPETITION_ENTRY_REL = 0x18;
    static final int STADIUM_REL = 0x20;
    static final int KICKOFF_REL = 0x44;
    static final int COMPETITION_FROM_ENTRY_REL = 0x18;
    static final int TEAM_CLUB_REL = 0x30;
    static final int TEAM_COMPETITION_REL = 0x50;
    static final int TEAM_STADIUM_REL = 0x78;
    static final int TEAM_REPUTATION_REL = 0xA8;
    private static final int DATE_DAY_MASK = 0x01ff;
    private static final int TIME_QUARTER_OFFSET = 23;
    private static final int SCAN_CHUNK_BYTES = 16 * 1024 * 1024;

    private final ManagedClubMemoryReader managedClubReader;

    public FixtureExporter(ManagedClubMemoryReader managedClubReader) {
        this.managedClubReader = managedClubReader;
    }

    public ExportResult exportManagedClubFixtures(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            ManagedClubIdentity managed = managedClubReader.read(reader, build, gamePluginBase);
            LocalDate gameDate = new GameDateFinder().find(reader, 0, build, gamePluginBase).orElse(null);
            return export(reader, build, gamePluginBase, managed, gameDate);
        }
    }

    ExportResult export(
            ProcessMemoryReader reader,
            int build,
            Long gamePluginBase,
            ManagedClubIdentity managed,
            LocalDate gameDate) throws IOException {
        Map<Long, TeamInfo> teams = readTeams(reader, build, gamePluginBase);
        TeamInfo managedTeam = teams.get(managed.teamAddress());
        if (managedTeam == null) {
            throw new IllegalStateException("The managed first team is absent from FM's team table");
        }

        LocalDate earliest = gameDate == null ? LocalDate.of(2024, 1, 1) : gameDate.minusYears(2);
        LocalDate latest = gameDate == null ? LocalDate.of(2100, 12, 31) : gameDate.plusYears(2);
        Map<FixtureKey, Map<String, Object>> fixtures = new HashMap<>();
        for (MemoryRegion region : reader.maps()) {
            if (!region.readable() || !region.writable()) {
                continue;
            }
            scanRegion(reader, region, managed.teamAddress(), managedTeam, teams, earliest, latest, fixtures);
        }

        List<Map<String, Object>> rows = new ArrayList<>(fixtures.values());
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("kickoffDate")))
                .thenComparing(row -> String.valueOf(row.get("kickoffTime")))
                .thenComparing(row -> String.valueOf(row.get("opponent"))));
        return new ExportResult(rows, gameDate == null ? "" : gameDate.toString());
    }

    private static Map<Long, TeamInfo> readTeams(
            ProcessMemoryReader reader, int build, Long gamePluginBase) throws IOException {
        FmOffsets.Bounds bounds = FmOffsets.tableBounds(reader, build, gamePluginBase, "TeamOffset");
        Map<Long, TeamInfo> teams = new HashMap<>();
        for (long address = bounds.start(); address < bounds.end(); address += Long.BYTES) {
            try {
                long team = reader.readU64(address);
                long club = reader.readU64(team + TEAM_CLUB_REL);
                String name = FmMemoryStrings.clubDisplayName(reader, club).orElse("");
                int reputation = reader.readU16(team + TEAM_REPUTATION_REL);
                if (name.isBlank() || reputation <= 0 || reputation > 10_000) {
                    continue;
                }
                long competition = reader.qwordOrNull(team + TEAM_COMPETITION_REL).orElse(0L);
                long stadium = reader.qwordOrNull(team + TEAM_STADIUM_REL).orElse(0L);
                teams.put(team, new TeamInfo(team, club, name, competition, stadium, reputation));
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return teams;
    }

    private static void scanRegion(
            ProcessMemoryReader reader,
            MemoryRegion region,
            long managedTeamAddress,
            TeamInfo managedTeam,
            Map<Long, TeamInfo> teams,
            LocalDate earliest,
            LocalDate latest,
            Map<FixtureKey, Map<String, Object>> fixtures) {
        long cursor = region.start();
        while (cursor < region.end()) {
            int length = (int) Math.min(SCAN_CHUNK_BYTES, region.end() - cursor);
            byte[] bytes;
            try {
                bytes = reader.readBytes(cursor, length);
            } catch (IOException | RuntimeException exception) {
                return;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int aligned = (int) ((Long.BYTES - (cursor & (Long.BYTES - 1))) & (Long.BYTES - 1));
            for (int offset = aligned; offset + MATCH_RECORD_BYTES <= length; offset += Long.BYTES) {
                long firstTeamAddress = buffer.getLong(offset);
                long secondTeamAddress = buffer.getLong(offset + SECOND_TEAM_REL);
                if (firstTeamAddress != managedTeamAddress && secondTeamAddress != managedTeamAddress) {
                    continue;
                }
                TeamInfo firstTeam = teams.get(firstTeamAddress);
                TeamInfo secondTeam = teams.get(secondTeamAddress);
                if (firstTeam == null || secondTeam == null || firstTeam.clubAddress() == secondTeam.clubAddress()) {
                    continue;
                }
                long stadiumAddress = buffer.getLong(offset + STADIUM_REL);
                String venue = stadiumName(reader, stadiumAddress);
                if (venue.isBlank()) {
                    continue;
                }
                long entryAddress = buffer.getLong(offset + COMPETITION_ENTRY_REL);
                long competitionAddress;
                try {
                    competitionAddress = reader.readU64(entryAddress + COMPETITION_FROM_ENTRY_REL);
                } catch (IOException | RuntimeException exception) {
                    continue;
                }
                String competition = FmMemoryStrings.competitionDisplayName(reader, competitionAddress).orElse("");
                if (competition.isBlank()) {
                    continue;
                }
                int rawDate = Short.toUnsignedInt(buffer.getShort(offset + KICKOFF_REL));
                int day = rawDate & DATE_DAY_MASK;
                int year = Short.toUnsignedInt(buffer.getShort(offset + KICKOFF_REL + Short.BYTES));
                if (!GameDateFinder.validDayYear(day, year)) {
                    continue;
                }
                LocalDate date = GameDateFinder.dayYearToDate(day, year);
                LocalTime time = kickoffTime(rawDate);
                if (date.isBefore(earliest) || date.isAfter(latest) || time == null) {
                    continue;
                }

                boolean home = firstTeamAddress == managedTeamAddress;
                TeamInfo opponent = home ? secondTeam : firstTeam;
                FixtureKey key = new FixtureKey(date, time, competitionAddress,
                        firstTeam.clubAddress(), secondTeam.clubAddress());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceAddress", cursor + offset);
                row.put("kickoffDate", date.toString());
                row.put("kickoffTime", time.toString());
                row.put("competition", competition);
                row.put("homeClub", firstTeam.clubName());
                row.put("awayClub", secondTeam.clubName());
                row.put("opponent", opponent.clubName());
                row.put("venue", venue);
                row.put("home", home);
                row.put("_home_club_address", firstTeam.clubAddress());
                row.put("_away_club_address", secondTeam.clubAddress());
                row.put("_opponent_club_address", opponent.clubAddress());
                fixtures.merge(key, row, FixtureExporter::lowerSourceAddress);
            }
            if (length <= MATCH_RECORD_BYTES) {
                break;
            }
            cursor += length - MATCH_RECORD_BYTES;
        }
    }

    private static Map<String, Object> lowerSourceAddress(
            Map<String, Object> left, Map<String, Object> right) {
        long leftAddress = ((Number) left.get("sourceAddress")).longValue();
        long rightAddress = ((Number) right.get("sourceAddress")).longValue();
        return rightAddress < leftAddress ? right : left;
    }

    static LocalTime kickoffTime(int rawDate) {
        int quarter = (rawDate >>> 9) + TIME_QUARTER_OFFSET;
        int minutes = quarter * 15;
        return minutes >= 0 && minutes < 24 * 60 ? LocalTime.of(minutes / 60, minutes % 60) : null;
    }

    private static String stadiumName(ProcessMemoryReader reader, long stadium) {
        if (stadium <= 0 || stadium > ProcessMemoryReader.MAX_USER_ADDRESS) {
            return "";
        }
        return FmMemoryStrings.objectStringAt(reader, stadium, 0x48)
                .or(() -> FmMemoryStrings.objectStringAt(reader, stadium, 0x40))
                .orElse("");
    }

    private record TeamInfo(
            long teamAddress,
            long clubAddress,
            String clubName,
            long competitionAddress,
            long stadiumAddress,
            int reputation) {
    }

    private record FixtureKey(
            LocalDate date,
            LocalTime time,
            long competitionAddress,
            long homeClubAddress,
            long awayClubAddress) {
    }

    public record ExportResult(List<Map<String, Object>> rows, String gameDate) {
    }
}
