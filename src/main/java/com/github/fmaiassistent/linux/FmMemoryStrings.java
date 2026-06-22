package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.util.Optional;

public final class FmMemoryStrings {
    private FmMemoryStrings() {
    }

    public static Optional<String> probeString(ProcessMemoryReader reader, long value) {
        Optional<String> direct = reader.readFmLenString(value, 512);
        if (direct.isPresent()) {
            return direct;
        }
        return reader.qwordOrNull(value).flatMap(target -> reader.readFmLenString(target, 512));
    }

    public static Optional<String> objectStringAt(ProcessMemoryReader reader, Long obj, long offset) {
        if (obj == null) {
            return Optional.empty();
        }
        return reader.qwordOrNull(obj + offset).flatMap(value -> probeString(reader, value));
    }

    public static Optional<String> clubDisplayName(ProcessMemoryReader reader, Long club) {
        Optional<String> longName = objectStringAt(reader, club, 0xC8);
        return longName.isPresent() ? longName : objectStringAt(reader, club, 0xC0);
    }

    public static Optional<String> clubNation(ProcessMemoryReader reader, Long club) {
        if (club == null) {
            return Optional.empty();
        }
        Optional<Long> nation = reader.qwordOrNull(club + 0xD8);
        if (nation.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> primary = objectStringAt(reader, nation.get(), 0x18);
        return primary.isPresent() ? primary : objectStringAt(reader, nation.get(), 0x20);
    }

    public static Optional<String> competitionDisplayName(ProcessMemoryReader reader, Long competition) {
        Optional<String> shortName = objectStringAt(reader, competition, 0x48);
        Optional<String> longName = objectStringAt(reader, competition, 0x40);
        if (shortName.isPresent()) {
            String shortText = shortName.get();
            if ("First Division".equals(shortText) && longName.orElse("").startsWith("Spanish ")) {
                return longName;
            }
            return shortName;
        }
        return longName;
    }

    public static Optional<String> recordString(ProcessMemoryReader reader, long record, long offset) {
        return reader.qwordOrNull(record + offset).flatMap(value -> probeString(reader, value));
    }

    public static Optional<String> playerName(ProcessMemoryReader reader, long record) {
        Optional<String> full = recordString(reader, record, 0x40);
        if (full.isPresent()) {
            return full;
        }
        String first = recordString(reader, record, 0x50).orElse("");
        String last = recordString(reader, record, 0x58).orElse("");
        String joined = (first + " " + last).trim();
        return joined.isBlank() ? Optional.empty() : Optional.of(joined);
    }

    public static Optional<String> currentClubName(ProcessMemoryReader reader, long record) {
        Optional<Long> registration = reader.qwordOrNull(record + 0xA8);
        if (registration.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> registrationBody = reader.qwordOrNull(registration.get() + 0x10);
        if (registrationBody.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> club = reader.qwordOrNull(registrationBody.get() + 0x30);
        return clubDisplayName(reader, club.orElse(null));
    }

    public static Optional<String> playingClubName(ProcessMemoryReader reader, long record) {
        Optional<Long> teamBody = reader.qwordOrNull(record - 0x158);
        if (teamBody.isEmpty()) {
            return Optional.empty();
        }
        Optional<Long> club = reader.qwordOrNull(teamBody.get() + 0x30);
        return clubDisplayName(reader, club.orElse(null));
    }

    public static Optional<String> playerNationality(ProcessMemoryReader reader, long record) {
        Optional<Long> nation = reader.qwordOrNull(record + 0x68);
        if (nation.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> primary = objectStringAt(reader, nation.get(), 0x18);
        if (primary.isPresent()) {
            return primary;
        }
        Optional<String> secondary = objectStringAt(reader, nation.get(), 0x20);
        if (secondary.isPresent()) {
            return secondary;
        }
        Optional<String> tertiary = objectStringAt(reader, nation.get(), 0x28);
        return tertiary.isPresent() ? tertiary : objectStringAt(reader, nation.get(), 0x30);
    }
}
