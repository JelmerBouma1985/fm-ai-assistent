package com.github.fmaiassistent.windows.scouting;

import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ScoutingReportLayout;
import com.github.fmaiassistent.windows.WindowsProcessReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Creates genuine FM-owned reports, one short transaction on FM's window thread per player. */
@Component
public class WindowsScoutingWriter {
    private final ScoutingReportLayout layout = ScoutingReportLayout.current();

    public boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                && com.sun.jna.Native.POINTER_SIZE == Long.BYTES;
    }

    public synchronized Result scout(int pid, List<Long> playerUids, boolean perfectJudgement,
                                     BiConsumer<Integer, Integer> progress) throws IOException {
        return scout(pid, playerUids, perfectJudgement, progress, () -> false);
    }

    public synchronized Result scout(int pid, List<Long> playerUids, boolean perfectJudgement,
                                     BiConsumer<Integer, Integer> progress, BooleanSupplier cancelled) throws IOException {
        if (!isSupported()) throw new IOException("Creating scout reports requires 64-bit Windows");
        PlayerSnapshotValidator.validateScoutingReportLayout(layout);
        List<Long> ids = List.copyOf(playerUids);
        if (ids.stream().anyMatch(uid -> uid == null || uid <= 0 || uid > 0xffff_ffffL)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IOException("Scouting targets must have distinct valid player IDs");
        }
        if (ids.isEmpty()) return new Result(0, 0, 0, "");
        int created = 0;
        int updated = 0;
        if (cancelled.getAsBoolean()) return new Result(ids.size(), 0, 0, "", true);
        try (WindowsScoutingSession session = new WindowsScoutingSession(pid);
             ProcessMemoryReader reader = new WindowsProcessReader(pid)) {
            Context context = resolve(reader, session.base(), new HashSet<>(ids), layout);
            progress.accept(0, ids.size());
            for (long uid : ids) {
                if (cancelled.getAsBoolean()) {
                    return new Result(ids.size(), created, updated, context.scout().name(), true);
                }
                if (Thread.currentThread().isInterrupted()) throw new IOException("Scouting was interrupted");
                Target target = context.targets().get(uid);
                long[] state = control(context, target, perfectJudgement, session.base(), layout);
                long[] completed = session.execute(state);
                verifyCompleted(reader, context, target, completed, layout);
                if (completed[31] == 1) created++; else updated++;
                progress.accept(created + updated, ids.size());
            }
            return new Result(ids.size(), created, updated, context.scout().name());
        } catch (IOException exception) {
            throw new IOException("Scouting stopped after " + (created + updated) + " of " + ids.size()
                    + " players. " + exception.getMessage(), exception);
        }
    }

    static Context resolve(ProcessMemoryReader reader, long base, Set<Long> requested,
                           ScoutingReportLayout layout) throws IOException {
        PlayerSnapshotValidator.validateScoutingReportLayout(layout);
        long manager = pointer(reader, base + layout.managerRva());
        if (displacement(reader, manager, layout) != layout.humanDisplacement()) {
            throw new IOException("FM's active human-manager identity changed");
        }
        long managerUid = reader.readU32(manager + layout.uidRel());
        if (managerUid == 0 || managerUid == 0xffff_ffffL) throw new IOException("No active human manager");
        long team = pointer(reader, pointer(reader, manager + layout.employmentRel()) + layout.contractTeamRel());
        long club = pointer(reader, team + layout.teamClubRel());
        long descriptor = pointer(reader, base + layout.peopleRva());
        long header = pointer(reader, descriptor + layout.peopleHeaderRel());
        byte[] bounds = reader.readBytes(header, 24);
        ByteBuffer vector = ByteBuffer.wrap(bounds).order(ByteOrder.LITTLE_ENDIAN);
        long begin = vector.getLong();
        long end = vector.getLong();
        long capacity = vector.getLong();
        if (begin <= 0 || begin > end || end > capacity || (end - begin) % 8 != 0
                || end - begin > 4_000_000) throw new IOException("FM's people registry is invalid");
        byte[] people = reader.readBytes(begin, Math.toIntExact(end - begin));
        Map<Long, Target> targets = new HashMap<>();
        List<Scout> scouts = new ArrayList<>();
        for (int index = 0; index < people.length / 8; index++) {
            long person = littleLong(people, index * 8);
            if (person == 0) continue;
            byte[] identity = reader.readBytes(person, 16);
            long uid = PlayerSnapshotValidator.validatePersonIdentity(index, identity,
                    com.github.fmaiassistent.memory.PersonIdentityLayout.current());
            if (requested.contains(uid)) {
                long complete = playerComplete(reader, person, layout);
                targets.put(uid, new Target(uid, person, complete, index));
            }
            // Only genuine club scouts/chief scouts, never an unrelated coach or foreign scout.
            if (displacement(reader, person, layout) != layout.staffDisplacement()) continue;
            long employment = reader.readU64(person + layout.employmentRel());
            if (employment <= 0 || employment > ProcessMemoryReader.MAX_USER_ADDRESS) continue;
            if (reader.readU64(employment + layout.contractTeamRel()) != team) continue;
            int job = reader.readU8(employment + layout.jobRel());
            if (job != 14 && job != 44) continue;
            long complete = person - layout.staffDisplacement();
            int jca = reader.readU8(complete + layout.judgementRel());
            int jpa = reader.readU8(complete + layout.judgementRel() + 1);
            if (jca < 1 || jca > 100 || jpa < 1 || jpa > 100) throw new IOException("Scout judgement layout drifted");
            String name = FmMemoryStrings.playerName(reader, person).orElse("Scout " + uid);
            scouts.add(new Scout(uid, person, complete, jca, jpa, job, name));
        }
        if (targets.size() != requested.size()) {
            throw new IOException("Some selected players are absent from the loaded save; reload the app's player data");
        }
        Scout scout = bestScout(scouts);
        if (!Arrays.equals(bounds, reader.readBytes(header, 24))
                || !Arrays.equals(people, reader.readBytes(begin, people.length))
                || reader.readU64(base + layout.managerRva()) != manager) {
            throw new IOException("FM changed save or manager while preparing scouting; retry once loading finishes");
        }
        long clock = reader.readU32(base + layout.clockRva());
        return new Context(manager, managerUid, manager - layout.humanDisplacement(), team, club, clock, scout, targets);
    }

    static Scout bestScout(List<Scout> scouts) throws IOException {
        return scouts.stream().max(Comparator.comparingInt((Scout scout) -> scout.jca() + scout.jpa())
                        .thenComparingInt(scout -> Math.min(scout.jca(), scout.jpa()))
                        .thenComparingInt(Scout::jca).thenComparingLong(scout -> -scout.uid()))
                .orElseThrow(() -> new IOException("Your club has no supported scout or chief scout"));
    }

    static long[] control(Context context, Target target, boolean perfect, long base, ScoutingReportLayout layout) {
        long[] state = new long[85];
        state[10] = base; state[11] = context.manager(); state[12] = context.managerUid();
        state[13] = context.human(); state[14] = target.complete(); state[15] = target.person();
        state[16] = target.uid(); state[17] = context.scout().complete(); state[18] = context.scout().person();
        state[19] = context.scout().uid(); state[20] = context.team(); state[21] = context.club();
        state[22] = context.clock(); state[23] = perfect ? 1 : 0;
        state[32] = layout.managerRva(); state[33] = layout.clockRva(); state[34] = layout.uidRel();
        state[35] = layout.employmentRel(); state[36] = layout.contractTeamRel(); state[37] = layout.teamClubRel();
        state[38] = layout.staffDisplacement(); state[39] = layout.judgementRel(); state[40] = layout.reportsRel();
        state[41] = layout.extraRel(); state[42] = layout.percentRel(); state[43] = layout.categoryRel();
        state[44] = layout.extraJcaRel(); state[45] = layout.factoryRva(); state[46] = layout.registerRva();
        state[47] = layout.lookupRva(); state[48] = layout.extraDestructorRva(); state[49] = layout.extraFreeRva();
        state[50] = layout.tagFreeRva(); state[51] = layout.humanDisplacement(); state[52] = layout.indexRel();
        state[57] = layout.tagPersonRel(); state[58] = layout.tagStaffRel(); state[59] = layout.tagTeamRel();
        state[60] = layout.recruitmentEntryAllocateRva(); state[61] = layout.humanDataRel();
        state[62] = layout.recruitmentContextRel(); state[63] = layout.recruitmentEntriesRel();
        state[64] = layout.recruitmentEntryTagRel(); state[65] = layout.recruitmentEntryVtableRva();
        state[70] = layout.recruitmentEntryPoolRva(); state[71] = layout.recruitmentEntryInitRva();
        state[72] = layout.recruitmentEntryDeleteRva(); state[73] = layout.recruitmentEntryAddRva();
        state[74] = layout.recruitmentEntryBytes(); state[75] = layout.recruitmentEntryDateRel();
        state[76] = layout.recruitmentEntryIdRel(); state[77] = layout.recruitmentEntryDefaultsRel();
        state[78] = layout.recruitmentEntryDeliveryRel(); state[79] = layout.recruitmentEntryPoolGuardRva();
        state[82] = layout.recruitmentPoolsBeginRel(); state[83] = layout.recruitmentPoolsEndRel();
        state[84] = layout.recruitmentPoolPointerBytes();
        return state;
    }

    static void verifyCompleted(ProcessMemoryReader reader, Context context, Target target, long[] state,
                                ScoutingReportLayout layout) throws IOException {
        long tag = state[24];
        int original = (int) state[28];
        if (state[9] != 2 || state[54] != 1 || state[29] != 1 || tag <= 0
                || (state[31] != 1 && state[31] != 2)
                || reader.readU16(context.scout().complete() + layout.judgementRel()) != original
                || reader.readU64(tag + layout.tagPersonRel()) != target.person()
                || reader.readU64(tag + layout.tagStaffRel()) != context.scout().person()
                || reader.readU64(tag + layout.tagTeamRel()) != context.team()
                || reader.readU8(tag + layout.percentRel()) != 100
                || reader.readU8(tag + layout.categoryRel()) != 5) {
            throw new IOException("FM report verification failed (native status " + state[9] + ")");
        }
        long extra = pointer(reader, tag + layout.extraRel());
        int jca = reader.readU8(extra + layout.extraJcaRel());
        if (jca < 1 || jca > 20 || jca != state[30] || state[23] == 1 && jca != 20) {
            throw new IOException("FM's generated report judgement failed verification");
        }
        if (state[69] != 1 || state[68] <= 0) throw new IOException("FM recruitment report enrollment failed verification");
        long recruitmentTag = pointer(reader, state[68] + layout.recruitmentEntryTagRel());
        long recruitmentExtra = pointer(reader, recruitmentTag + layout.extraRel());
        if (recruitmentTag == tag || reader.readU64(recruitmentTag + layout.tagPersonRel()) != target.person()
                || reader.readU64(recruitmentTag + layout.tagStaffRel()) != context.scout().person()
                || reader.readU64(recruitmentTag + layout.tagTeamRel()) != context.team()
                || reader.readU8(recruitmentTag + layout.percentRel()) != 100
                || reader.readU8(recruitmentExtra + layout.extraJcaRel()) != jca) {
            throw new IOException("FM's recruitment report copy failed verification");
        }
    }

    private static long pointer(ProcessMemoryReader reader, long address) throws IOException {
        long pointer = reader.readU64(address);
        if (pointer <= 0 || pointer > ProcessMemoryReader.MAX_USER_ADDRESS) throw new IOException("Invalid FM pointer");
        return pointer;
    }

    static long playerComplete(ProcessMemoryReader reader, long person, ScoutingReportLayout layout) throws IOException {
        int displacement = displacement(reader, person, layout);
        if (displacement != layout.playerDisplacement() && displacement != layout.playerStaffDisplacement()) {
            throw new IOException("The player has an unsupported native player subtype");
        }
        long complete = person - displacement;
        long vbtable = pointer(reader, complete + layout.playerVbtableRel());
        int personDelta = reader.readI32(vbtable + layout.playerVbtablePersonRel());
        // This is the same virtual PERSON getter used by the native factory and lookup.
        if (complete + layout.playerVbtableRel() + personDelta != person) {
            throw new IOException("The native player interface no longer matches its PERSON identity");
        }
        return complete;
    }

    private static int displacement(ProcessMemoryReader reader, long person, ScoutingReportLayout layout) throws IOException {
        long vtable = pointer(reader, person);
        long metadata = pointer(reader, vtable - 8);
        return reader.readI32(metadata + layout.rttiDisplacementRel());
    }

    private static long littleLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public record Result(int requested, int created, int updated, String scoutName, boolean cancelled) {
        public Result(int requested, int created, int updated, String scoutName) {
            this(requested, created, updated, scoutName, false);
        }
    }
    record Target(long uid, long person, long complete, int index) { }
    record Scout(long uid, long person, long complete, int jca, int jpa, int job, String name) { }
    record Context(long manager, long managerUid, long human, long team, long club, long clock,
                   Scout scout, Map<Long, Target> targets) { }
}
