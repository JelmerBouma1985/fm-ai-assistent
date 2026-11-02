package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerMedicalStaffLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Passive native 11592A0 physician selection used by the player fitness model. */
public final class PlayerMedicalStaffReader {
    private static final int MAX_STAFF = 4096;
    private final PlayerMedicalStaffLayout layout;
    public PlayerMedicalStaffReader() { this(PlayerMedicalStaffLayout.current()); }
    PlayerMedicalStaffReader(PlayerMedicalStaffLayout layout) { this.layout = layout; }

    public PlayerMedicalAssessmentReader.StaffContext defaultStaff(ProcessMemoryReader r,
            long player, long module, LocalDate date) throws IOException {
        PlayerSnapshotValidator.validatePlayerMedicalStaffLayout(layout);
        if (r.platform() != ProcessMemoryReader.Platform.WINDOWS) return null;
        long team = r.readU64(player + layout.currentTeam());
        if (team == 0 || r.readU8(team + layout.teamKind()) != 0) return null;
        long club = r.readU64(team + layout.teamClub());
        if (club == 0) return null;
        long begin = r.readU64(club + layout.clubStaff()), end = r.readU64(club + layout.clubStaff() + 8L);
        if (begin < 0 || begin > end || (end - begin) % 8 != 0 || end - begin > MAX_STAFF * 8L)
            throw new IOException("Player medical staff vector range anchor failed");
        List<Candidate> candidates = new ArrayList<>();
        for (long at=begin; at<end; at+=8) {
            long staff = r.readU64(at);
            if (staff == 0) continue;
            long vb = r.readU64(staff + layout.staffVirtualBase());
            int displacement = r.readI32(vb + layout.personDisplacement());
            if (displacement < 0x80 || displacement > 0x800)
                throw new IOException("Player medical staff PERSON displacement anchor failed");
            long person = staff + displacement;
            long contract = r.readU64(person + layout.contract());
            candidates.add(new Candidate(staff,person,contract));
            long matching = matchingContract(r,person,contract,club);
            if (matching != 0 && physioRole(r,matching,module)) return context(r,staff,person);
        }
        Candidate selected = null;
        byte best = 0;
        for (Candidate candidate : candidates) {
            if (candidate.contract() == 0 || !medicalRole(r,candidate.contract(),module)) continue;
            int ability = r.readU16(candidate.staff() + layout.currentAbility());
            if (ability > 200) throw new IOException("Player medical staff ability range anchor failed");
            int science = science(r,candidate.staff());
            byte score = (byte) ((ability / 2) * (science * 5) / 100);
            if (score > best) { selected = candidate; best = score; }
        }
        return selected == null ? null : context(r,selected.staff(),selected.person());
    }

    private long matchingContract(ProcessMemoryReader r,long person,long contract,long club) throws IOException {
        if (contract != 0 && contractClub(r,contract) == club) return contract;
        long secondary = r.readU64(person + layout.secondaryContracts());
        if (secondary == 0) return 0;
        for (int i=0;i<4;i++) {
            long alternative = r.readU64(secondary + i * 8L);
            if (alternative != 0 && contractClub(r,alternative) == club) return alternative;
        }
        return 0;
    }
    private long contractClub(ProcessMemoryReader r,long contract) throws IOException {
        long team = r.readU64(contract + layout.contractTeam());
        return team == 0 ? 0 : r.readU64(team + layout.contractClub());
    }
    private boolean physioRole(ProcessMemoryReader r,long contract,long module) throws IOException {
        int primary = r.readU8(contract + layout.primaryRole());
        return (primary & 0xFE) == 40 || (secondaryRole(r,contract,module) & 0xFE) == 40;
    }
    private boolean medicalRole(ProcessMemoryReader r,long contract,long module) throws IOException {
        int primary = r.readU8(contract + layout.primaryRole());
        return eligible(primary) || eligible(secondaryRole(r,contract,module));
    }
    private static boolean eligible(int role) { return (role & 0xFE) == 40 || (role & 0xFE) == 48; }
    private int secondaryRole(ProcessMemoryReader r,long contract,long module) throws IOException {
        long vt = r.readU64(contract);
        long getter = r.readU64(vt + layout.roleGetterSlot());
        if (getter == module + layout.zeroRoleGetterRva()) return 0;
        if (getter != module + layout.roleGetterRva())
            throw new IOException("Player medical staff contract role getter anchor failed");
        return r.readU8(contract + layout.secondaryRole());
    }
    private int science(ProcessMemoryReader r,long staff) throws IOException {
        int raw = r.readU8(staff + layout.sportsScience());
        if (raw > 100) throw new IOException("Player medical staff sports science range anchor failed");
        return Math.clamp((raw + 2) / 5,1,20);
    }
    private PlayerMedicalAssessmentReader.StaffContext context(ProcessMemoryReader r,long staff,long person) throws IOException {
        int uid = r.readI32(person + layout.uniqueId());
        if (uid <= 0) throw new IOException("Player medical staff identity range anchor failed");
        return new PlayerMedicalAssessmentReader.StaffContext(uid,science(r,staff),null,false);
    }
    private record Candidate(long staff,long person,long contract) {}
}
