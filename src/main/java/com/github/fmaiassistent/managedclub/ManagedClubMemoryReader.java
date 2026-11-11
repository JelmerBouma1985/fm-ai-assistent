package com.github.fmaiassistent.managedclub;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.ClubIdentityLayout;
import com.github.fmaiassistent.memory.NationalTeamLayout;
import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ManagedClubMemoryReader {
    private static final long PERSON_EMPLOYMENT_REL = 0xA8;
    private static final long PERSON_UNIQUE_ID_REL = 0x0C;
    private static final long EMPLOYMENT_TEAM_REL = 0x10;
    private static final long TEAM_CLUB_REL = 0x30;

    public ManagedClubIdentity read(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            return read(reader, build, gamePluginBase);
        }
    }

    public ManagedClubIdentity read(ProcessMemoryReader reader, int build, Long gamePluginBase) throws IOException {
        long pluginBase = gamePluginBase == null ? FmOffsets.findGamePluginBase(reader) : gamePluginBase;
        return read(reader, build, pluginBase, FmOffsets.moduleProfile(reader, pluginBase));
    }

    ManagedClubIdentity read(ProcessMemoryReader reader, int build, long pluginBase, String profile) throws IOException {
        long managerRva = FmOffsets.currentHumanManagerRva(profile, build)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The current human-manager pointer is not known for FM build 0x"
                                + Integer.toHexString(build)));
        long manager = requiredPointer(reader, pluginBase + managerRva, "human manager");
        String managerName = FmMemoryStrings.playerName(reader, manager)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("The current human manager name could not be read"));
        Long managerUniqueId = optionalUniqueId(reader, manager + PERSON_UNIQUE_ID_REL);

        // A readable null relationship is valid unemployment; read failures must still propagate.
        long employment = reader.readU64(manager + PERSON_EMPLOYMENT_REL);
        if (employment < 0 || employment > ProcessMemoryReader.MAX_USER_ADDRESS) {
            throw new IllegalStateException("FM did not expose a valid manager employment pointer");
        }
        ManagedNationalTeam national = readNationalTeam(reader, pluginBase, profile, manager, employment, NationalTeamLayout.current());
        if (employment == 0) {
            return new ManagedClubIdentity(manager, managerUniqueId, managerName, 0, 0, "", national);
        }
        long team = requiredPointer(reader, employment + EMPLOYMENT_TEAM_REL, "manager team");
        if (national != null && team == national.teamAddress()) {
            return new ManagedClubIdentity(manager, managerUniqueId, managerName, 0, 0, "", national);
        }
        long club = requiredPointer(reader, team + TEAM_CLUB_REL, "managed club");
        String clubName = FmMemoryStrings.clubDisplayName(reader, club)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("The managed club name could not be read"));
        return new ManagedClubIdentity(manager, managerUniqueId, managerName, team, club, clubName, national);
    }

    ManagedNationalTeam readNationalTeam(ProcessMemoryReader reader, long base, String profile,
                                        long manager, long primary, NationalTeamLayout nationLayout) throws IOException {
        PlayerSnapshotValidator.validateNationalTeamLayout(nationLayout);
        if (profile == null) {
            // Existing club-only builds can still load, but unknown images cannot vouch for a national job.
            if (reader.readU64(manager + ContractRecordLayout.current().links().additional()) != 0) {
                throw new IOException("National employment discovery requires a verified FM27 module profile");
            }
            return null;
        }
        ContractRecordLayout contracts = ContractRecordLayout.forModule(profile);
        PlayerSnapshotValidator.validateContractLayout(contracts);
        var links = contracts.links();
        long additional = reader.readU64(manager + links.additional());
        byte[] slots = additional == 0 ? new byte[0] : reader.readBytes(additional, links.additionalSlots() * Long.BYTES);
        var buffer = java.nio.ByteBuffer.wrap(slots).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ManagedNationalTeam result = nationalEmployment(reader, base, profile, manager, primary, nationLayout);
        for (int i = 0; i < links.additionalSlots() && additional != 0; i++) {
            ManagedNationalTeam found = nationalEmployment(reader, base, profile, manager, buffer.getLong(i * Long.BYTES), nationLayout);
            if (found != null) {
                if (result != null && result.teamAddress() != found.teamAddress()) {
                    throw new IOException("Multiple national-team jobs were found for the human manager");
                }
                result = found;
            }
        }
        if (reader.readU64(manager + links.primary()) != primary
                || reader.readU64(manager + links.additional()) != additional
                || additional != 0 && !java.util.Arrays.equals(slots, reader.readBytes(additional, slots.length))) {
            throw new IOException("Manager employment links changed during national-team detection");
        }
        return result;
    }

    private ManagedNationalTeam nationalEmployment(ProcessMemoryReader reader, long base, String profile,
            long manager, long employment, NationalTeamLayout layout) throws IOException {
        if (employment == 0) return null;
        PlayerSnapshotValidator.validateContractPointer(employment, 24);
        var contracts = ContractRecordLayout.forModule(profile);
        long team = requiredPointer(reader, employment + contracts.links().team(), "manager team");
        var owner = contracts.teamOwner();
        // Club contracts retain their existing decoding path; national jobs need the typed namespace.
        if (reader.readU8(team + owner.flag()) == 0) return null;
        if (!com.github.fmaiassistent.memory.NativeModuleProfile.FM27_2701_SHA256.equals(profile)) {
            throw new IOException("National-team nation link is not verified for this FM27 module profile");
        }
        if (reader.readU64(employment) != base + contracts.full().vtable()
                || reader.readU64(employment + contracts.links().owner()) != manager
                || reader.readU64(team) != base + ClubIdentityLayout.forModule(profile).nationalTeamVtable()) {
            throw new IOException("National employment owner/type anchor failed");
        }
        long container = requiredPointer(reader, team + owner.reference(), "national-team container");
        if (reader.readU64(container) != base + owner.nationalContainerVtable()) {
            throw new IOException("National-team container type anchor failed");
        }
        long nation = requiredPointer(reader, container + layout.nationRel(), "managed nation");
        long uid = reader.readU32(nation + contracts.links().uid());
        if (uid == 0 || uid != reader.readU32(container + contracts.links().uid())) {
            throw new IOException("National-team nation identity anchor failed");
        }
        String name = FmMemoryStrings.objectStringAt(reader, nation, layout.nameRel())
                .or(() -> FmMemoryStrings.objectStringAt(reader, nation, layout.alternateNameRel()))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IOException("Managed national-team name could not be read"));
        if (reader.readU64(container + layout.nationRel()) != nation
                || reader.readU64(team + owner.reference()) != container
                || reader.readU64(employment + contracts.links().team()) != team) {
            throw new IOException("National employment changed during detection");
        }
        return new ManagedNationalTeam(team, container, nation, uid, name);
    }

    private static long requiredPointer(ProcessMemoryReader reader, long address, String description) {
        return reader.qwordOrNull(address)
                .orElseThrow(() -> new IllegalStateException("FM did not expose a valid " + description + " pointer"));
    }

    private static Long optionalUniqueId(ProcessMemoryReader reader, long address) {
        try {
            long value = reader.readU32(address);
            return value > 0 ? value : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }
}
