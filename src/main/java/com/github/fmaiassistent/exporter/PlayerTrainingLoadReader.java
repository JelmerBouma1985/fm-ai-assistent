package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerTrainingLoadLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Set;

/** Passive translation of the native workload calculation; never invokes FM code. */
public final class PlayerTrainingLoadReader {
    private static final int MAX_ITEMS = 4096;
    private final PlayerTrainingLoadLayout layout;
    public PlayerTrainingLoadReader() { this(PlayerTrainingLoadLayout.current()); }
    PlayerTrainingLoadReader(PlayerTrainingLoadLayout layout) { this.layout = layout; }

    public Result read(ProcessMemoryReader reader, long player, long moduleBase, LocalDate date) throws IOException {
        validateLayout(layout);
        if (date == null || reader.platform() != ProcessMemoryReader.Platform.WINDOWS) return null;
        var p = layout.player();
        int flags = reader.readU8(player + p.flags());
        if ((flags & 2) != 0) return result(0, 0, 254, 0, 0, 0);
        long manager = reader.readU64(moduleBase + layout.globals().manager());
        if (manager == 0) return null;
        long international = reader.readU64(player + p.internationalTeam());
        if (international != 0 && reader.readU8(international + layout.team().dutyActive()) == 1) {
            int day = reader.readU16(international + layout.team().returnDay()) & 511;
            int year = reader.readU16(international + layout.team().returnYear());
            if (year >= date.getYear() && day > 0 && day <= 366
                    && LocalDate.ofYearDay(year, day).isAfter(date)) return null;
        }
        long team = reader.readU64(player + p.currentTeam());
        long configuration = team == 0 ? 0 : humanConfiguration(reader, manager, team, moduleBase);
        long schedule = configuration == 0 ? cachedSchedule(reader, manager, team, date)
                : configuredSchedule(reader, configuration, date);
        if (schedule == 0 && team != 0) return null;
        int baseline = schedule == 0 ? 100 : scheduleWorkload(reader, manager, schedule, moduleBase);
        int intensity = reader.readU8(player + (team != 0 && reader.readU8(team + layout.team().kind()) == 1
                ? p.nationalIntensity() : p.intensity()));
        if (intensity == 255) intensity = automaticIntensity(reader, player, configuration, moduleBase);
        int scalar = workload(reader, player, manager, team, baseline, intensity, flags, moduleBase);
        return result(scalar, baseline, intensity, team, schedule, configuration);
    }

    public static void validateLayout(PlayerTrainingLoadLayout layout) throws IOException {
        PlayerSnapshotValidator.validatePlayerTrainingLoadLayout(layout);
    }

    /** Existing human-owned TEAM context used by native 2E30B20 and medical staff selection. */
    public long teamContext(ProcessMemoryReader r, long team, long module) throws IOException {
        if (team == 0) return 0;
        long human = teamHuman(r, team, module);
        if (human == 0) return 0;
        var role = layout.role();
        long manager = r.readU64(module + role.contextManager());
        if (manager == 0) return 0;
        long[] contexts = vector(r, manager + role.contexts(), 8);
        int teamIndex = r.readI32(team + role.teamIndex());
        for (long at = contexts[0]; at < contexts[1]; at += 8) {
            long context = r.readU64(at);
            if (context == 0 || r.readU64(context + role.contextOwner()) != human) continue;
            return findIntTree(r, context, teamIndex);
        }
        return 0;
    }

    /** Native 167E9F0: current lineup position, personal position training, then natural primary position. */
    public Integer trainingRole(ProcessMemoryReader r, long player, long module) throws IOException {
        validateLayout(layout);
        var role = layout.role();
        long vb = r.readU64(player + layout.player().virtualBase());
        int index = r.readI32(player + r.readI32(vb + layout.player().personDisplacement())
                + layout.player().personIndex());
        long team = r.readU64(player + layout.player().currentTeam());
        if (team != 0) {
            long human = teamHuman(r, team, module);
            if (human != 0) {
                long node = teamContext(r, team, module);
                if (node != 0) {
                    int slot = playerSlot(r, node + role.lineup(), index, 26);
                    if (slot >= 0 && slot < 11) {
                        int selected = r.readU8(node + role.selectedTactic());
                        long[] tactics = vector(r, node + role.tactics(), 8);
                        if (selected <= 2 && selected < (tactics[1] - tactics[0]) / 8) {
                            long tactic = r.readU64(tactics[0] + selected * 8L);
                            if (tactic != 0) {
                                int bits = r.readI32(tactic + role.tacticPositions() + slot * (long) role.tacticSlotStride());
                                Integer category = tacticRole(bits);
                                if (category != null) return category;
                            }
                        }
                    }
                }
            } else {
                long state = r.readU64(team + role.aiState());
                if (state == 0) return null;
                long lineup = r.readU64(state + role.aiLineup());
                if (lineup == 0) return null; // Native would allocate and calculate an AI lineup.
                int slot = playerSlot(r, lineup + role.aiPlayers(), index, 11);
                if (slot >= 0) {
                    int position = r.readU16(lineup + slot * 2L);
                    int type = (short) (position << 10) >> 10;
                    if (type >= -15 && type <= 11 && type != -14) {
                        int bits = r.readI32(module + role.packedPositionTable() + (type + 15L) * 4);
                        return roleFromBits(bits);
                    }
                }
            }
        }
        Integer personal = personalPositionMask(r, player, module);
        if (personal != null && personal != 0) return roleFromBits(personal);
        return roleFromBits(primaryPositionMask(r, player));
    }

    /** Native 115CB0(...,true), before cached or personal training-position overrides. */
    public int primaryPositionMask(ProcessMemoryReader r, long player) throws IOException {
        validateLayout(layout);
        var role = layout.role();
        long positions = player + role.positionSource();
        int goalkeeper = r.readU8(positions + role.goalkeeper());
        if (goalkeeper > 20) throw new IOException("Player training position range anchor failed");
        if (goalkeeper >= 20) return 1;
        // Native 115CB0 chooses the first natural (20), otherwise accomplished (15), position in this order.
        int[] masks = {8,16,4,128,512,1024,256,4096,8192,2048,16384,64,32};
        int[] scores = new int[masks.length];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = r.readU8(positions + role.firstPosition() + i);
            if (scores[i] > 20) throw new IOException("Player training position range anchor failed");
        }
        for (int threshold : new int[]{20,15}) for (int i = 0; i < scores.length; i++)
            if (scores[i] >= threshold) return masks[i];
        return 0;
    }

    /** Native 16825D0 position mask: mature cached positional training then active personal training.
     * Attribute-bitset selection is deliberately supplied by the independent native role scorer.
     */
    public int preferredPositionMask(ProcessMemoryReader r, long player, long module, LocalDate date) throws IOException {
        int mask = cachedPositionMask(r,player,module,date);
        if ((mask & 1) == 0) {
            Integer personal = personalPositionMask(r, player, module);
            if (personal != null && personal != 0) mask = personal;
        }
        return mask;
    }

    /** Explicit personal attribute-role bitset from 3C512D0, before default role scoring. */
    public Long preferredAttributeRoleOverride(ProcessMemoryReader r, long player, long module, LocalDate date) throws IOException {
        if ((cachedPositionMask(r,player,module,date) & 1) != 0) return null;
        long record = personalRecord(r,player);
        if (record == 0 || personalPositionMaskAt(r,record,module) == 0) return null;
        int packed = r.readU16(record + layout.extra().type());
        int role = ((short)(packed << 3)) >> 9;
        long bits = role >= -31 && role <= 19
                ? r.readU64(module + layout.preferredPosition().attributeRoles() + (role + 31L) * 8) : 0;
        return bits == 0 ? null : bits;
    }

    private int cachedPositionMask(ProcessMemoryReader r, long player, long module, LocalDate date) throws IOException {
        int mask = primaryPositionMask(r, player);
        var preferred = layout.preferredPosition();
        long cache = r.readU64(player + preferred.cache());
        if (cache != 0) {
            long[] entries = vector(r, cache, 4);
            if (entries[0] != entries[1] && r.readU8(entries[0] + preferred.development()) >= 3) {
                long vb = r.readU64(player + layout.player().virtualBase());
                long attrs = player + r.readI32(vb + layout.player().attributesDisplacement());
                int year = r.readU16(attrs + preferred.startYear());
                int day = r.readU16(attrs + preferred.startDay()) & 511;
                if (!com.github.fmaiassistent.linux.GameDateFinder.validDayYear(day, year))
                    throw new IOException("Player preferred training position date anchor failed");
                if (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.ofYearDay(year,day), date) >= 30) {
                    int code = r.readU8(entries[0] + preferred.position());
                    mask = code <= 14 ? r.readI32(module + preferred.positionFlags() + code * 4L) : 0;
                }
            }
        }
        return mask;
    }

    /** 12E032F0's team-tactic modifier, using native 49A81C0 intensity calculation. */
    public Float teamRiskMultiplier(ProcessMemoryReader r, long player, long module) throws IOException {
        validateLayout(layout);
        long team = r.readU64(player + layout.player().currentTeam());
        if (team == 0) return 1f;
        long node = teamContext(r, team, module);
        if (node == 0) return 1f; // 2E2C720 cannot select a retained human tactic without a TEAM context.
        int selected = r.readU8(node + layout.role().selectedTactic());
        if (selected > 2) return 1f;
        long[] tactics = vector(r, node + layout.role().tactics(), 8);
        if (selected >= (tactics[1] - tactics[0]) / 8) return 1f;
        long tactic = r.readU64(tactics[0] + selected * 8L);
        if (tactic == 0) return 1f;
        int score = tacticIntensity(r, tactic);
        int value = score + 100;
        int bucket = value == 0 ? 0 : (((value * 249) & 65535) >>> 13) + 1;
        if (bucket != 5 && bucket != 6) return 1f;
        float multiplier = floatAt(r, module + (bucket == 5 ? layout.tactic().increasedRisk() : layout.tactic().highRisk()));
        if (multiplier != (bucket == 5 ? 1.1f : 1.2f)) throw new IOException("Team tactic risk multiplier anchor failed");
        return multiplier;
    }

    /** Exact signed-byte reductions in native 49A81C0,1B3DC00,1B3DCD0 and49E9C50. */
    public int tacticIntensity(ProcessMemoryReader r, long tactic) throws IOException {
        validateLayout(layout);
        var t = layout.tactic();
        int mentality = r.readU8(tactic + t.mentality());
        if (mentality > 7) throw new IOException("Team tactic mentality range anchor failed");
        long settings = r.readU64(tactic + t.settings());
        long extended = r.readU64(tactic + t.extendedSettings());
        int desired = r.readI32(tactic + t.packedPressing()) & 255;
        if (desired == 0) desired = 11;
        int base = mentality < 7 ? new int[]{19,7,7,7,10,13,16}[mentality] : 19;
        int pressing = Math.max((byte)(desired-5),Math.min((byte)(desired+5),base));
        pressing = Math.max((byte)(base-10),Math.min((byte)(base+10),pressing));
        pressing = Math.max(1,Math.min(20,pressing));
        if (bit(settings,46)) pressing = Math.max(15,pressing);
        else if (bit(settings,47)) pressing = Math.max(12,pressing);
        else if (bit(settings,49)) pressing = Math.min(5,pressing);
        else if (bit(settings,48)) pressing = Math.min(8,pressing);
        int tempo = new int[]{1,10,8,6,4,2,0,1}[mentality];
        if (mentality != 7) {
            long tempoSettings = r.readU64(tactic + t.tempo());
            if (bit(tempoSettings,14)) tempo += 10;
            else if (bit(tempoSettings,15)) tempo += 5;
            tempo = Math.max(1,tempo);
        }
        int team = bit(settings,61) ? 10 : bit(settings,63) ? -10 : 0;
        if (bit(extended,2)) team += 5;
        else if (bit(extended,1)) team += 10;
        else if (bit(extended,3)) team -= 5;
        else if (bit(extended,4)) team -= 10;
        int positional = 0, running = 0;
        for (int slot = 0; slot < 11; slot++) {
            long definition = tactic + t.slots() + slot * (long)t.slotStride();
            if (instruction(r,definition,29)) positional++;
            else if (instruction(r,definition,30)) positional--;
            if (instruction(r,definition,31)) running += 2;
            else if (instruction(r,definition,32)) running -= 2;
        }
        int mental = mentality < 7 ? new int[]{20,1,3,7,10,13,16}[mentality] : 20;
        int value = pressing * 2 + (bit(extended,8) ? 10 : bit(extended,9) ? -10 : 0)
                + (bit(extended,6) ? 5 : bit(extended,7) ? -5 : 0)
                + (bit(settings,54) ? 5 : bit(settings,55) ? -5 : 0)
                + mental - tempo + (bit(settings,42) ? 5 : bit(settings,43) ? -5 : 0)
                + team + positional + running - 20;
        return Math.max(-100,Math.min(100,(byte)value));
    }

    private boolean instruction(ProcessMemoryReader r, long slot, int instruction) throws IOException {
        var t = layout.tactic();
        long role = r.readU64(slot + t.slotRole());
        int position = r.readI32(slot + t.slotPosition());
        int type = instructionType(position,role,instruction);
        if (type != 0) return type == 3;
        long[] cache = vector(r,slot + t.instructions(),t.instructionStride());
        long key = role & 0xFFFFFFFBF91FFFFFL;
        for (long at=cache[0]; at<cache[1]; at+=t.instructionStride())
            if (r.readU64(at+t.instructionKey()) == key)
                return bit(r.readU64(at+t.instructionBits()),instruction);
        return false; // Native inserts an all-zero instruction entry when no matching cache entry exists.
    }

    private static int instructionType(int position, long role, int instruction) {
        if (instruction == 31 || instruction == 32) return 0;
        if ((position & 1) != 0) return 1;
        if (instruction == 29) {
            if ((role & 0x20) != 0) return 2;
            if ((role & 0x10) != 0) return 3;
            if ((role & 0x800) != 0) return 2;
            if ((role & 0x400) != 0) return 3;
            if ((role & 0x4000) != 0) return 2;
            if ((role & 0x22000) != 0) return 3;
            if ((role & 0x40000) != 0) return 2;
            if ((role & 0x8000000) != 0) return 3;
            return (role & 0x10000000) != 0 ? 2 : 0;
        }
        if ((role & 0x20) != 0) return 3;
        if ((role & 0x10) != 0) return 2;
        if ((role & 0x800) != 0) return 3;
        if ((role & 0x400) != 0) return 2;
        if ((role & 0x4000) != 0) return 3;
        if ((role & 0x2000) != 0) return 2;
        if ((role & 0x40000) != 0) return 3;
        if ((role & 0x20000) != 0) return 2;
        if ((role & 0x10000000) != 0) return 3;
        return (role & 0x8000000) != 0 ? 2 : 0;
    }

    private static boolean bit(long bits,int bit) { return (bits & (1L << bit)) != 0; }

    private Integer personalPositionMask(ProcessMemoryReader r, long player, long module) throws IOException {
        long record = personalRecord(r,player);
        return record == 0 ? null : personalPositionMaskAt(r,record,module);
    }
    private int personalPositionMaskAt(ProcessMemoryReader r,long record,long module) throws IOException {
        int type = (short) (r.readU16(record + layout.extra().type()) << 10) >> 10;
        return type >= -15 && type <= 10
                ? r.readI32(module + layout.role().extraPositionTable() + (type + 15L) * 4) : 0;
    }
    private long personalRecord(ProcessMemoryReader r,long player) throws IOException {
        long extra = r.readU64(player + layout.player().extra());
        if (extra == 0) return 0;
        long[] entries = vector(r, extra, layout.extra().stride());
        for (long at = entries[0]; at < entries[1]; at += layout.extra().stride()) {
            if (r.readI16(at + layout.extra().remaining()) <= 0 || r.readU8(at + layout.extra().inactive()) != 0) continue;
            return at;
        }
        return 0;
    }

    private static int roleFromBits(int bits) {
        if ((bits & 1) != 0) return 0;
        if ((bits & 0x1F800) != 0) return 3;
        return (bits & 0x7E0) != 0 ? 2 : 1;
    }
    /** 46D4470's position-group priority followed by the 167E9F0 role reduction. */
    private static Integer tacticRole(int bits) {
        if ((bits & 1) != 0) return 0;
        if ((bits & 0x1C) != 0) return 1;
        if ((bits & 0x7E0) != 0) return 2;
        return (bits & 0x1F800) != 0 ? 3 : null;
    }
    private static int playerSlot(ProcessMemoryReader r, long slots, int index, int count) throws IOException {
        if (index < 0) return -1;
        for (int slot = 0; slot < count; slot++) if (r.readI32(slots + slot * 4L) == index) return slot;
        return -1;
    }

    private long teamHuman(ProcessMemoryReader r, long team, long module) throws IOException {
        if (team == 0) return 0;
        long coach = r.readU64(team + layout.team().coach());
        long human = coach == 0 ? 0 : humanRoot(r, coach, module);
        if (human != 0) return human;
        long club = r.readU64(team + layout.team().club());
        if (club == 0) return 0;
        long[] teams = vector(r, club + layout.team().clubTeams(), 8);
        if (teams[0] != teams[1]) {
            long first = r.readU64(teams[0]);
            coach = first == 0 ? 0 : r.readU64(first + layout.team().coach());
            human = coach == 0 ? 0 : humanRoot(r, coach, module);
            if (human != 0) return human;
        }
        var t = layout.team();
        if ((r.readU8(club + t.affiliateFlags()) & 0x10) != 0) return 0;
        long[] relations = vector(r,club + t.affiliates(),8);
        for (long at=relations[0]; at<relations[1]; at+=8) {
            long relation = r.readU64(at);
            int kind = r.readU8(relation + t.affiliateKind());
            if (r.readU64(relation + t.affiliateClub()) != club || kind > 20 || (0x1001F0 & (1 << kind)) == 0) continue;
            long owner = r.readU64(relation + t.affiliateOwner());
            if (owner == 0) return 0;
            long[] ownerTeams = vector(r,owner + t.clubTeams(),8);
            if (ownerTeams[0] == ownerTeams[1]) return 0;
            long first = r.readU64(ownerTeams[0]);
            coach = first == 0 ? 0 : r.readU64(first + t.coach());
            return coach == 0 ? 0 : humanRoot(r,coach,module);
        }
        return 0;
    }

    private long humanRoot(ProcessMemoryReader r, long coach, long module) throws IOException {
        var h = layout.human();
        long vb = r.readU64(coach);
        long person = coach + r.readI32(vb + h.virtualBaseDisplacement());
        long vt = r.readU64(person);
        if (r.readU64(vt + h.predicateSlot()) != module + layout.globals().humanPredicate()
                || r.readU64(vt + h.getterSlot()) != module + layout.globals().humanGetter()) return 0;
        return person - r.readI32(person - h.interfaceCorrection()) - h.rootDisplacement();
    }

    private long findIntTree(ProcessMemoryReader r, long address, int key) throws IOException {
        var t = layout.tree();
        long head = r.readU64(address), node = r.readU64(head + t.root()), candidate = head;
        Set<Long> visited = new HashSet<>();
        while (r.readU8(node + t.nil()) == 0) {
            if (!visited.add(node) || visited.size() > MAX_ITEMS) throw new IOException("Player training context tree cycle");
            if (r.readI32(node + t.key()) < key) node = r.readU64(node + t.right());
            else { candidate = node; node = r.readU64(node); }
        }
        return candidate != head && r.readI32(candidate + t.key()) == key ? candidate : 0;
    }

    /** Native 1682100 effects and two role-independent 168A270 scores, with personal rest-day filtering. */
    public RiskTrainingInputs riskInputs(ProcessMemoryReader r, long player, long module, LocalDate date) throws IOException {
        Result result = read(r, player, module, date);
        if (result == null) return null;
        int personalDays = personalDayMask(r,player,module,date);
        long manager = r.readU64(module + layout.globals().manager());
        int score = result.schedule() == 0 ? 85 : scheduleWorkloadRaw(r, manager, result.schedule());
        byte[] focus = focusEffects(r, manager, result.schedule(), result.intensity(), module,personalDays);
        int modified = result.workload();
        return new RiskTrainingInputs(manager, result.team(), result.schedule(), result.configuration(),
                score, score, result.intensity(), modified, focus,personalDays);
    }

    /** Native 166AD10/17BF1E0: player-specific rest intervals intersected with the loaded training week. */
    public int personalDayMask(ProcessMemoryReader r,long player,long module,LocalDate date) throws IOException {
        validateLayout(layout);
        int flags = r.readU8(player + layout.player().flags());
        if ((flags & layout.focus().personalDaysFlag()) == 0 || r.readU8(module + layout.globals().enabled()) != 1) return 0;
        long team = r.readU64(player + layout.player().currentTeam());
        long human = teamHuman(r,team,module);
        if (human == 0) return 0;
        long manager = r.readU64(module + layout.globals().manager());
        long[] contexts = vector(r,manager + layout.manager().contexts(),8);
        long context = 0;
        for (long at=contexts[0]; at<contexts[1]; at+=8) {
            long candidate = r.readU64(at);
            if (r.readU64(candidate + layout.human().contextOwner()) != human + layout.human().rootDisplacement()) continue;
            if (findTree(r,candidate + layout.human().configurations(),team) != 0) { context=candidate;break; }
            long club = r.readU64(team + layout.team().club());
            if (club == 0) continue;
            long[] teams = vector(r,club + layout.team().clubTeams(),8);
            if (teams[0] != teams[1] && findTree(r,candidate + layout.human().configurations(),r.readU64(teams[0])) != 0) {
                context=candidate;break;
            }
        }
        if (context == 0) return 0;
        var c = layout.restCalendar();
        long[] records = vector(r,context + c.records(),8);
        long vb = r.readU64(player + layout.player().virtualBase());
        long person = player + layout.player().virtualBase() + r.readI32(vb + layout.player().personDisplacement());
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int mask = 0;
        for (long at=records[0]; at<records[1]; at+=8) {
            long record = r.readU64(at);
            if (r.readU64(record + c.person()) != person) continue;
            LocalDate start = calendarDate(r,record + c.startDay(),record + c.startYear());
            LocalDate end = calendarDate(r,record + c.endDay(),record + c.endYear());
            if (end.isBefore(start)) throw new IOException("Player personal rest interval anchor failed");
            for (int day=0; day<7; day++) {
                LocalDate current = monday.plusDays(day);
                if (!current.isBefore(start) && current.isBefore(end)) mask |= 1 << day;
            }
        }
        return mask;
    }

    private static LocalDate calendarDate(ProcessMemoryReader r,long dayAddress,long yearAddress) throws IOException {
        int day = r.readU16(dayAddress) & 511,year = r.readU16(yearAddress);
        if (!com.github.fmaiassistent.linux.GameDateFinder.validDayYear(day,year))
            throw new IOException("Player personal rest date anchor failed");
        return LocalDate.ofYearDay(year,day);
    }

    /** Native 167A1F0 weekly path, then 16CC1C0 intensity scaling. */
    private byte[] focusEffects(ProcessMemoryReader r, long manager, long schedule, int intensity, long module,int personalDays) throws IOException {
        byte[] effects = new byte[6];
        if (schedule == 0) return effects;
        long[] definitions = vector(r, manager + layout.manager().definitions(), 8);
        var s = layout.schedule();
        for (int day = 0; day < 7; day++) for (int session = 0; session < 3; session++) {
            int code = r.readU16(schedule + day * (long) s.dayStride() + s.firstSession() + session * 2L);
            if ((personalDays & (1 << day)) != 0 && PlayerInjuryRiskReader.isExcludedOnPersonalDay(r,module,code)) continue;
            long definition = sessionDefinition(r, definitions, code);
            if (definition == 0) continue;
            for (int i = 0; i < effects.length; i++) {
                int total = (byte) (effects[i] + r.readU8(definition + layout.focus().sessionEffects() + i));
                effects[i] = (byte) Math.max(-100, Math.min(100, total));
            }
        }
        if (intensity == 0 || intensity == 254 || intensity == 2) {
            var f = layout.focus();
            float multiplier = floatAt(r, module + (intensity == 2 ? f.increasedMultiplier() : f.reducedMultiplier()));
            float positive = floatAt(r, module + f.positiveRounding());
            float negative = floatAt(r, module + f.negativeRounding());
            if (!Float.isFinite(multiplier) || multiplier < 0 || multiplier > 2
                    || !Float.isFinite(positive) || positive < 0 || positive > 1
                    || !Float.isFinite(negative) || negative < -1 || negative > 0)
                throw new IOException("Player training focus constant anchor failed");
            for (int i = 0; i < effects.length; i++) {
                float scaled = effects[i] * multiplier;
                int rounded = (int) (scaled > 0 ? scaled + positive : scaled < 0 ? scaled + negative : scaled);
                effects[i] = (byte) Math.max(-100, Math.min(100, (byte) rounded));
            }
        }
        return effects;
    }

    private Result result(int scalar, int baseline, int intensity, long team, long schedule, long configuration) throws IOException {
        if (scalar < -50 || scalar > 1000 || baseline < 0 || baseline > 236)
            throw new IOException("Player training workload range anchor failed");
        int category = scalar == 0 ? 1 : scalar < 35 ? 2 : scalar < 50 ? 3 : scalar < 90 ? 4 : scalar < 110 ? 5 : 6;
        String label = switch (category) {
            case 1 -> "None"; case 2 -> "Very Light"; case 3 -> "Light";
            case 4 -> "Medium"; case 5 -> "Heavy"; default -> "Very Heavy";
        };
        return new Result(category, label, scalar, baseline, intensity, team, schedule, configuration);
    }

    private int workload(ProcessMemoryReader r, long player, long manager, long team, int base,
                         int intensity, int flags, long module) throws IOException {
        var p = layout.player(); var e = layout.extra();
        if (r.readU8(manager + layout.manager().overrideIntensity()) != 255) {
            long vb = r.readU64(player + p.virtualBase());
            int index = r.readI32(player + r.readI32(vb + p.personDisplacement()) + p.personIndex());
            long[] bounds = vector(r, manager + layout.manager().overridePlayers(), 4);
            for (long at = bounds[0]; at < bounds[1]; at += 4)
                if (r.readI32(at) == index) return 0;
        }
        int value = base;
        long extra = r.readU64(player + p.extra());
        if (extra != 0) {
            long[] bounds = vector(r, extra, e.stride());
            for (long at = bounds[0]; at < bounds[1]; at += e.stride()) {
                int type = (short) (r.readU16(at + e.type()) << 10) >> 10;
                if (r.readU8(at + e.inactive()) == 0 && r.readI16(at + e.remaining()) > 0
                        && ((type >= -15 && type <= -1 && type != -14) || (type >= 0 && type < 12))) value += 10;
            }
        }
        value += intensity == 254 ? -50 : intensity == 0 ? -25 : intensity == 2 ? 10 : 0;
        long individual = r.readU64(player + p.individual());
        if (individual != 0 && (byte) r.readU8(individual + e.individualState()) >= 0) value += 10;
        long focus = r.readU64(player + p.focus());
        if (focus != 0 && r.readI16(focus + e.focusState()) != 50) value += 10;
        long position = r.readU64(player + p.position());
        if (position != 0 && (byte) r.readU8(position + e.positionState()) >= 0) value += 5;
        long vb = r.readU64(player + p.virtualBase());
        long attrs = player + r.readI32(vb + p.attributesDisplacement());
        int professional = (byte) r.readU8(attrs + p.professionalism());
        int ambition = (byte) r.readU8(attrs + p.ambition());
        if (professional < 0 || professional > 20 || ambition < 0 || ambition > 20)
            throw new IOException("Player training personality range anchor failed");
        int personality = 0;
        if ((flags & 0x20) != 0) {
            int total = professional * 3 + ambition * 2;
            if (professional >= 7 && total >= 30) personality = total;
        } else if (professional >= 17 && ambition >= 15) {
            personality = ((professional * 9 + ambition * 4 - 200) * 70) / 60 + 30;
        }
        float divisor = floatAt(r, module + layout.globals().personalityDivisor());
        float multiplier = floatAt(r, module + layout.globals().personalityMultiplier());
        if (divisor != 100f || multiplier != 3f) throw new IOException("Player training constant anchor failed");
        return value + (int) (personality / divisor * multiplier);
    }

    private int automaticIntensity(ProcessMemoryReader r, long player, long configuration, long module) throws IOException {
        if (configuration == 0 || r.readU8(module + layout.globals().enabled()) != 1) return 254;
        int sharpness = r.readI16(player + layout.player().sharpness());
        if (sharpness < 0 || sharpness > 10000) throw new IOException("Player training sharpness range anchor failed");
        int percentage = (sharpness + 50) / 100;
        int slot = percentage > 85 ? 4 : percentage > 75 ? 3 : percentage > 65 ? 2 : percentage >= 51 ? 1 : 0;
        int intensity = r.readU8(configuration + layout.human().intensityTable() + slot);
        return intensity < 3 ? intensity : 254;
    }

    private int scheduleWorkload(ProcessMemoryReader r, long manager, long schedule, long module) throws IOException {
        int score = scheduleWorkloadRaw(r, manager, schedule);
        float scale = floatAt(r, module + layout.globals().scheduleScale());
        if (Float.floatToIntBits(scale) != Float.floatToIntBits(1.1764706373214722f))
            throw new IOException("Player training scale anchor failed");
        return (int) (score * scale);
    }

    /** Native 168A270 does not apply the later 2DB3330 scale of 100/85. */
    public int scheduleWorkloadRaw(ProcessMemoryReader r, long manager, long schedule) throws IOException {
        var s = layout.schedule(); var d = layout.session();
        long[] definitions = vector(r, manager + layout.manager().definitions(), 8);
        int total = 0;
        for (int day = 0; day < 7; day++) for (int session = 0; session < 3; session++) {
            int code = r.readU16(schedule + day * (long) s.dayStride() + s.firstSession() + session * 2L);
            long definition = sessionDefinition(r, definitions, code);
            if (definition == 0) continue; // Native unmatched sessions contribute zero.
            int amount;
            if (code == 300 || code == 320 || code == 402) amount = 0;
            else if (code == 500) amount = 100;
            else if (code == 600) amount = 10;
            else amount = Math.max(0, Math.min(100, (byte) (5 - 6 * r.readU8(definition + d.physical())
                    - 4 * r.readU8(definition + d.tactical()) - 5 * r.readU8(definition + d.technical()))));
            total += amount;
        }
        return Math.min(200, (total / 7) & 255);
    }

    private long sessionDefinition(ProcessMemoryReader r, long[] definitions, int code) throws IOException {
        for (long at = definitions[0]; at < definitions[1]; at += 8) {
            long candidate = r.readU64(at);
            if (r.readU16(candidate + layout.session().code()) == code) return candidate;
        }
        return 0;
    }

    private long humanConfiguration(ProcessMemoryReader r, long manager, long team, long module) throws IOException {
        long coach = r.readU64(team + layout.team().coach());
        long configuration = coach == 0 ? 0 : coachConfiguration(r, manager, team, coach, module);
        if (configuration != 0) return configuration;
        long club = r.readU64(team + layout.team().club());
        if (club == 0) return 0;
        long[] teams = vector(r, club + layout.team().clubTeams(), 8);
        if (teams[0] == teams[1]) return 0;
        long first = r.readU64(teams[0]);
        if (first == 0) return 0;
        coach = r.readU64(first + layout.team().coach());
        return coach == 0 ? 0 : coachConfiguration(r, manager, team, coach, module);
    }

    private long coachConfiguration(ProcessMemoryReader r, long manager, long team, long coach, long module) throws IOException {
        var h = layout.human();
        long vb = r.readU64(coach);
        long person = coach + r.readI32(vb + h.virtualBaseDisplacement());
        long vt = r.readU64(person);
        if (r.readU64(vt + h.predicateSlot()) != module + layout.globals().humanPredicate()) return 0;
        if (r.readU64(vt + h.getterSlot()) != module + layout.globals().humanGetter()) return 0;
        long human = person - r.readI32(person - h.interfaceCorrection()) - h.rootDisplacement();
        long[] contexts = vector(r, manager + layout.manager().contexts(), 8);
        for (long at = contexts[0]; at < contexts[1]; at += 8) {
            long context = r.readU64(at);
            if (r.readU64(context + h.contextOwner()) == human + h.rootDisplacement()) {
                long node = findTree(r, context + h.configurations(), team);
                return node == 0 ? 0 : node + h.configurationValue();
            }
        }
        return 0;
    }

    private long configuredSchedule(ProcessMemoryReader r, long config, LocalDate date) throws IOException {
        var s = layout.schedule();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long[] weeks = vector(r, config + s.weeks(), 8);
        for (long at = weeks[0]; at < weeks[1]; at += 8) {
            long week = r.readU64(at);
            if ((r.readU16(week + s.weekDay()) & 511) == monday.getDayOfYear()
                    && r.readU16(week + s.weekYear()) == monday.getYear()) return week + s.days();
        }
        return 0;
    }

    private long cachedSchedule(ProcessMemoryReader r, long manager, long team, LocalDate date) throws IOException {
        if (team == 0) return 0;
        var s = layout.schedule();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long key = (r.readU32(team + layout.team().uid()) << 32) | ((long) monday.getYear() << 17)
                | ((long) monday.getDayOfYear() << 8);
        long count = r.readU64(manager + layout.manager().cacheCount());
        if (count > MAX_ITEMS * 16L) throw new IOException("Player training cache range anchor failed");
        if (count == 0) return 0;
        long head = r.readU64(manager + layout.manager().cacheHead());
        long node = r.readU64(head + s.cacheNext());
        Set<Long> visited = new HashSet<>();
        while (node != head) {
            if (!visited.add(node) || visited.size() > count) throw new IOException("Player training cache cycle");
            if (r.readU64(node + s.cacheKey()) == key) return r.readU64(node + s.cacheValue());
            node = r.readU64(node + s.cacheNext());
        }
        return 0;
    }

    private long findTree(ProcessMemoryReader r, long address, long key) throws IOException {
        var t = layout.tree();
        long head = r.readU64(address), node = r.readU64(head + t.root()), candidate = head;
        Set<Long> visited = new HashSet<>();
        while (r.readU8(node + t.nil()) == 0) {
            if (!visited.add(node) || visited.size() > MAX_ITEMS) throw new IOException("Player training tree cycle");
            if (Long.compareUnsigned(r.readU64(node + t.key()), key) < 0) node = r.readU64(node + t.right());
            else { candidate = node; node = r.readU64(node); }
        }
        return candidate != head && r.readU64(candidate + t.key()) == key ? candidate : 0;
    }

    private static long[] vector(ProcessMemoryReader r, long address, int stride) throws IOException {
        long begin = r.readU64(address), end = r.readU64(address + 8);
        if (begin < 0 || end < begin || end > ProcessMemoryReader.MAX_USER_ADDRESS
                || (end - begin) % stride != 0 || (end - begin) / stride > MAX_ITEMS)
            throw new IOException("Player training vector range anchor failed");
        return new long[]{begin, end};
    }
    private static float floatAt(ProcessMemoryReader r, long address) throws IOException {
        return Float.intBitsToFloat(r.readI32(address));
    }
    public record Result(int category, String label, int workload, int baseline, int intensity,
                         long team, long schedule, long configuration) {}
    public record RiskTrainingInputs(long manager, long team, long schedule, long configuration,
                                     int roleMedicalScore, int generalMedicalScore, int individualIntensity,
                                     int modifiedBaseline, byte[] focus, int personalDayMask) {
        public RiskTrainingInputs(long manager, long team, long schedule, long configuration,
                                  int roleMedicalScore, int generalMedicalScore, int individualIntensity,
                                  int modifiedBaseline, byte[] focus) {
            this(manager,team,schedule,configuration,roleMedicalScore,generalMedicalScore,
                    individualIntensity,modifiedBaseline,focus,0);
        }
        public RiskTrainingInputs { focus = focus.clone(); }
        @Override public byte[] focus() { return focus.clone(); }
    }
}
