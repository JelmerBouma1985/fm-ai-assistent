package com.github.fmaiassistent.managedclub;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ManagedClubMemoryReader {
    private static final long PERSON_EMPLOYMENT_REL = 0xA8;
    private static final long EMPLOYMENT_TEAM_REL = 0x10;
    private static final long TEAM_CLUB_REL = 0x30;

    public ManagedClubIdentity read(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            return read(reader, build, gamePluginBase);
        }
    }

    ManagedClubIdentity read(ProcessMemoryReader reader, int build, Long gamePluginBase) throws IOException {
        long managerRva = FmOffsets.currentHumanManagerRva(build)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The current human-manager pointer is not known for FM build 0x"
                                + Integer.toHexString(build)));
        long pluginBase = gamePluginBase == null ? FmOffsets.findGamePluginBase(reader) : gamePluginBase;
        long manager = requiredPointer(reader, pluginBase + managerRva, "human manager");
        String managerName = FmMemoryStrings.playerName(reader, manager)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("The current human manager name could not be read"));

        long employment = requiredPointer(reader, manager + PERSON_EMPLOYMENT_REL, "manager employment");
        long team = requiredPointer(reader, employment + EMPLOYMENT_TEAM_REL, "manager team");
        long club = requiredPointer(reader, team + TEAM_CLUB_REL, "managed club");
        String clubName = FmMemoryStrings.clubDisplayName(reader, club)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("The managed club name could not be read"));
        return new ManagedClubIdentity(manager, managerName, team, club, clubName);
    }

    private static long requiredPointer(ProcessMemoryReader reader, long address, String description) {
        return reader.qwordOrNull(address)
                .orElseThrow(() -> new IllegalStateException("FM did not expose a valid " + description + " pointer"));
    }
}
