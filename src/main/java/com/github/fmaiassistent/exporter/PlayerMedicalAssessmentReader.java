package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.PlayerMedicalAssessmentLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;

/** Passive translation of native active-injury selection; never invokes FM code. */
public final class PlayerMedicalAssessmentReader {
    private static final int MAX_INJURIES = 128;
    private final PlayerMedicalAssessmentLayout layout;
    public static final String ARCHIVE_PROPERTY = "fmaiassistent.fitness.injuryHistoryArchive";
    private static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
    private ArchiveCache archiveCache;

    public PlayerMedicalAssessmentReader() { this(PlayerMedicalAssessmentLayout.current()); }

    public PlayerMedicalAssessmentReader(PlayerMedicalAssessmentLayout layout) {
        this.layout = Objects.requireNonNull(layout);
    }

    /** Native1648DC0 via35D0B80 with null match/team arguments and the current game date. */
    public Boolean unavailable(ProcessMemoryReader reader, long player, long module, LocalDate date)
            throws IOException {
        PlayerSnapshotValidator.validatePlayerMedicalAssessmentLayout(layout);
        if (date == null) return null;
        long medical = reader.readU64(player + layout.medicalRel());
        if (medical == 0) return false;
        long[] statuses = vector(reader, medical + layout.statusVectorRel(), 4096);
        // Native rejects the whole collection when its first entry is null.
        if (statuses.length == 0 || statuses[0] == 0) return false;
        for (int i = statuses.length - 1; i >= 0; i--) {
            long status = statuses[i];
            if (status == 0) continue;
            int kind = (byte) reader.readU8(status + layout.statusKindRel());
            if (kind < 26) continue;
            int duration = reader.readI16(status + layout.statusDurationRel());
            if (duration < 0) continue;
            int day = reader.readU16(status + layout.statusDayRel()) & 0x1FF;
            int year = reader.readU16(status + layout.statusYearRel());
            LocalDate start;
            if (year < 1900 || year > 2500) throw new IOException("Player status year range anchor failed");
            try { start = LocalDate.ofYearDay(year, day); }
            catch (DateTimeException ex) { throw new IOException("Player status date range anchor failed", ex); }
            LocalDate end = start.plusDays(duration);
            if (date.isBefore(start) || date.isAfter(end)) continue;
            if (kind == 31 && reader.readU8(module + layout.statusCutoffFlagRva()) == 0) {
                long manager = reader.readU64(module + layout.statusManagerRva());
                if (manager == 0) return null;
                int cutoffDay = reader.readU16(manager + layout.statusCutoffDayRel()) & 0x1FF;
                int cutoffYear = reader.readU16(manager + layout.statusCutoffYearRel());
                if (cutoffYear < 1900 || cutoffYear > 2500) throw new IOException("Player status cutoff year anchor failed");
                try {
                    if (!end.isAfter(LocalDate.ofYearDay(cutoffYear, cutoffDay).plusDays(90))) continue;
                } catch (DateTimeException ex) { throw new IOException("Player status cutoff date anchor failed", ex); }
            }
            // Select the newest valid descriptor before testing the unavailability kinds.
            return (kind <= 33 || kind == 36 || kind == 38)
                    && (duration == 0 || date.isBefore(end));
        }
        return false;
    }

    /** 15D4D30 keeps the most severe unexpired injury, scanning newest entries first. */
    public ActiveInjury activeInjury(ProcessMemoryReader reader, long player, LocalDate date)
            throws IOException {
        PlayerSnapshotValidator.validatePlayerMedicalAssessmentLayout(layout);
        Objects.requireNonNull(date, "Game date required for injury selection");
        long medical = reader.readU64(player + layout.medicalRel());
        if (medical == 0) return null;
        long begin = reader.readU64(medical + layout.vectorBeginRel());
        long end = reader.readU64(medical + layout.vectorEndRel());
        long capacity = reader.readU64(medical + layout.vectorCapacityRel());
        if (begin < 0 || begin > end || end > capacity || (end - begin) % 8 != 0
                || capacity - begin > MAX_INJURIES * 8L) {
            throw new IOException("Player injury vector anchor failed");
        }
        ActiveInjury selected = null;
        for (long at = end - 8; at >= begin && at >= 0; at -= 8) {
            long injury = reader.readU64(at);
            if (injury == 0) throw new IOException("Player injury pointer anchor failed");
            ActiveInjury candidate = readInjury(reader, injury);
            if (candidate.start().plusDays(candidate.fullDuration()).isBefore(date)) continue;
            if (selected == null || candidate.fullDaysRemaining(date) > selected.fullDaysRemaining(date)) {
                selected = candidate;
            }
        }
        return selected;
    }

    /** The caller supplies the physician's deterministic seed and any native availability flags. */
    public Assessment read(ProcessMemoryReader reader, long player, long module, LocalDate date,
            StaffContext staff) throws IOException {
        PlayerSnapshotValidator.validatePlayerMedicalAssessmentLayout(layout);
        int rawProne = reader.readU8(player + layout.pronenessRel());
        int rawFitness = reader.readU8(player + layout.naturalFitnessRel());
        boolean scaled = (reader.readU8(player + layout.encodingRel()) & 128) != 0;
        int reputation = reader.readU16(player + layout.reputationRel());
        if (rawProne > 100 || rawFitness > 100 || reputation > 10000) {
            throw new IOException("Medical assessment attributes range anchor failed");
        }
        int prone = scaled ? Math.clamp((rawProne + 2) / 5, 1, 20) : rawProne;
        int fitness = scaled ? Math.clamp((rawFitness + 2) / 5, 1, 20) : rawFitness;
        boolean recurring = false;
        for (int i = 0; i < 3; i++) {
            if (reader.readI16(player + layout.recurringRel() + (long) i * layout.recurringStride()) >= 0) {
                recurring = true;
            }
        }
        int flags = 4;
        if (prone >= 18) {
            if (reputation >= 4251 || staff != null && staff.seed() > 80 + 2 * prone) flags = 2;
            else if (staff == null) return new Assessment(0, 0, 0, "Physician context unavailable");
        }
        if (fitness <= 5) {
            if (reputation >= 4251 || staff != null && staff.seed() > 80 + 2 * fitness) flags |= 0x40;
            else if (staff == null) return new Assessment(0, 0, 0, "Physician context unavailable");
        }
        if (recurring) {
            return new Assessment(flags, 0, 0, "Native recurring-injury selection requires RNG state");
        }
        List<HistoricalInjury> history;
        try {
            history = history(reader, player, module);
        } catch (HistoryUnavailableException unavailable) {
            return new Assessment(flags, 0, 0, unavailable.getMessage());
        }
        long selected = 0;
        if (history != null) {
            HistoricalInjury recent = recent(history, date, 20, 2);
            if (recent != null) { selected = recent.type(); flags |= 0x80; }
            else if (prone > 9) {
                HistoricalInjury repeated = repeated(history, date, true);
                if (repeated != null) { selected = repeated.type(); flags |= 0x100; }
                else {
                    repeated = repeated(history, date, false);
                    if (repeated != null) { selected = repeated.type(); flags |= 0x200; }
                }
            }
        }
        ActiveInjury active = activeInjury(reader, player, date);
        int availability;
        boolean unavailable;
        if (staff != null && staff.availabilityFlags() != null) {
            availability = staff.availabilityFlags();
            unavailable = staff.unavailableWithoutInjury();
        } else {
            int condition = reader.readU16(player + layout.conditionRel());
            int sharpness = reader.readU16(player + layout.sharpnessRel());
            int fatigue = reader.readI16(player + layout.fatigueRel());
            if (condition > 10000 || sharpness > 10000 || fatigue < -1000 || fatigue > 1000) {
                throw new IOException("Medical availability physical range anchor failed");
            }
            availability = recoveryAvailability(condition, sharpness, fatigue);
            if (active == null && (sharpness <= 6499 || fatigue >= 401)) {
                return new Assessment(flags, selected, 0, "Native rest/availability context required");
            }
            unavailable = false;
        }
        if (active == null && unavailable) flags |= 0x1000;
        if (history != null && (availability & 0x8C) != 0
                && (recent(history, date, 14, 4) != null
                    || active != null && active.susceptibilityRecoveryComplete(date))) flags |= 0x2000;
        return new Assessment(flags, selected, 0, null);
    }

    /** Native service35D6500/2DC5170 storage path, without constructing its temporary copy. */
    public List<HistoricalInjury> history(ProcessMemoryReader reader, long player, long module)
            throws IOException {
        PlayerSnapshotValidator.validatePlayerMedicalAssessmentLayout(layout);
        int index = reader.readI32(player + layout.historyIndexRel());
        if (index < 0) return null;
        long manager = reader.readU64(module + layout.managerRva());
        long store = reader.readU64(manager + layout.historyStoreRel());
        if (store == 0) return null;
        long memory = reader.readU64(store + layout.storeMemoryRel());
        if (memory == 0) return archivedHistory(reader, store, index, module);
        long entry = indexedPointer(reader, memory, index, 500000);
        if (entry <= 0) return null;
        List<HistoricalInjury> result = new ArrayList<>();
        for (long record : vector(reader, entry, 4096)) {
            if (record == 0) continue;
            int typeIndex = reader.readI16(record + layout.historyTypeRel());
            if (typeIndex < 0) continue;
            long type = type(reader, module, typeIndex);
            if (type == 0) continue;
            int duration = reader.readI16(record + layout.historyDurationRel());
            int year = reader.readU16(record + layout.historyYearRel());
            int day = reader.readU16(record + layout.historyDayRel()) & 0x1FF;
            int state = reader.readU8(record + layout.historyStateRel());
            int occurrence = reader.readU8(type + layout.typeOccurrenceRel());
            int body = reader.readU8(type + layout.typeBodyPartRel());
            int repeatable = reader.readU8(type + layout.typeRepeatableRel());
            if (year < 1900 || year > 2500 || state > 7 || occurrence > 16 || repeatable > 1) {
                throw new IOException("Native injury history range anchor failed");
            }
            try {
                result.add(new HistoricalInjury(type, LocalDate.ofYearDay(year, day),
                        duration, state, occurrence, body, repeatable == 1));
            } catch (DateTimeException ex) { throw new IOException("Native injury history date anchor failed", ex); }
        }
        return List.copyOf(result);
    }

    /** FM's2DD1250 fallback uses this same temporary archive when the vector is unloaded. */
    private List<HistoricalInjury> archivedHistory(ProcessMemoryReader reader, long store,
            int index, long module) throws IOException {
        long owner = reader.readU64(store + layout.archiveOwnerRel());
        if (owner == 0) return null;
        int version = reader.readU16(owner + layout.archiveVersionRel());
        if (version != 2) throw new HistoryUnavailableException("Unsupported native injury history archive version");
        long begin = reader.readU64(owner + layout.archiveIndexBeginRel());
        long end = reader.readU64(owner + layout.archiveIndexEndRel());
        long capacity = reader.readU64(owner + layout.archiveIndexCapacityRel());
        int stride = layout.archiveIndexStride();
        if (begin < 0 || begin > end || end > capacity || (end - begin) % stride != 0
                || capacity - begin > 500000L * stride) {
            throw new IOException("Injury archive player index range anchor failed");
        }
        if (index >= (end - begin) / stride) return null;
        long header = begin + (long) index * stride;
        long entries = reader.readU64(header + layout.vectorBeginRel());
        long entriesEnd = reader.readU64(header + layout.vectorEndRel());
        long entriesCapacity = reader.readU64(header + layout.vectorCapacityRel());
        if (entries < 0 || entries > entriesEnd || entriesEnd > entriesCapacity
                || (entriesEnd - entries) % 4 != 0 || entriesCapacity - entries > 4096L * 4) {
            throw new IOException("Injury archive record index range anchor failed");
        }
        if (entries == entriesEnd) return null;
        Path path;
        String override = System.getProperty(ARCHIVE_PROPERTY);
        if (override != null && !override.isBlank()) path = Path.of(override);
        else {
            long pathItem = reader.readU64(owner + layout.archivePathRel());
            if (reader.readU64(pathItem) != module + layout.pathVtableRva()) {
                throw new HistoryUnavailableException("Native injury history path implementation is unsupported");
            }
            String directory = nativeString(reader, reader.readU64(pathItem + layout.pathTextRel()), 4096);
            String name = nativeString(reader, reader.readU64(owner + layout.archiveNameRel()), 128);
            if (!name.equals("injury_histories_dt")) throw new IOException("Injury archive name anchor failed");
            path = Path.of(directory).resolve(name + ".cmt");
        }
        byte[] archive = archive(path, owner);
        List<HistoricalInjury> result = new ArrayList<>();
        for (long at = entries; at < entriesEnd; at += 4) {
            long offset = reader.readU32(at);
            if (offset % 16 != 0 || offset > archive.length - 24L) {
                throw new IOException("Injury archive record offset range anchor failed");
            }
            ByteBuffer record = ByteBuffer.wrap(archive, (int) offset + 8, 16).slice().order(ByteOrder.LITTLE_ENDIAN);
            int typeIndex = record.getShort();
            if (typeIndex < 0) continue;
            record.getInt(); // Unique ID of the originating injury.
            int state = Byte.toUnsignedInt(record.get());
            record.get();
            int day = Short.toUnsignedInt(record.getShort()) & 0x1FF;
            int year = Short.toUnsignedInt(record.getShort());
            int duration = record.getShort();
            long type = type(reader, module, typeIndex);
            if (type == 0) continue;
            int occurrence = reader.readU8(type + layout.typeOccurrenceRel());
            int body = reader.readU8(type + layout.typeBodyPartRel());
            int repeatable = reader.readU8(type + layout.typeRepeatableRel());
            if (year < 1900 || year > 2500 || state > 7 || occurrence > 16 || repeatable > 1) {
                throw new IOException("Injury archive record range anchor failed");
            }
            try {
                result.add(new HistoricalInjury(type, LocalDate.ofYearDay(year, day), duration,
                        state, occurrence, body, repeatable == 1));
            } catch (DateTimeException ex) { throw new IOException("Injury archive date anchor failed", ex); }
        }
        return List.copyOf(result);
    }

    private static String nativeString(ProcessMemoryReader reader, long address, int maximum) throws IOException {
        int length = reader.readI32(address);
        if (length < 1 || length > maximum) throw new IOException("Injury archive path string anchor failed");
        return new String(reader.readBytes(address + 4, length), StandardCharsets.UTF_8);
    }

    private byte[] archive(Path path, long owner) throws IOException {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            long size = Files.size(path);
            if (size < 8 || size > MAX_ARCHIVE_BYTES) throw new IOException("Injury archive size anchor failed");
            if (archiveCache != null && archiveCache.owner() == owner && archiveCache.path().equals(path)
                    && archiveCache.modified().equals(modified) && archiveCache.bytes().length == size) return archiveCache.bytes();
            byte[] data;
            try (var input = Files.newInputStream(path)) { data = input.readNBytes(MAX_ARCHIVE_BYTES + 1); }
            if (data.length > MAX_ARCHIVE_BYTES || data.length != size
                    || Files.size(path) != size || !Files.getLastModifiedTime(path).equals(modified)) {
                throw new IOException("Injury archive changed during capture");
            }
            byte[] header = {3, 1, 't', 'm', 'c', '.', 2, 0};
            if (!java.util.Arrays.equals(header, java.util.Arrays.copyOf(data, 8)) || (data.length - 8) % 16 != 0) {
                throw new IOException("Injury archive format anchor failed");
            }
            archiveCache = new ArchiveCache(path, owner, modified, data);
            return data;
        } catch (NoSuchFileException missing) {
            throw new HistoryUnavailableException("Native injury history archive is unavailable");
        }
    }

    private record ArchiveCache(Path path, long owner, FileTime modified, byte[] bytes) { }

    private long type(ProcessMemoryReader reader, long module, int index) throws IOException {
        long registry = reader.readU64(module + layout.typeRegistryRva());
        long vector = reader.readU64(registry + layout.registryVectorRel());
        long fallback = indexedPointer(reader, vector, index, 32768);
        if (fallback < 0) return 0;
        if (reader.readU8(module + layout.registryModeRva()) == 0) {
            long begin = reader.readU64(registry + layout.registryDirectBeginRel());
            long end = reader.readU64(registry + layout.registryDirectEndRel());
            if (begin > end || (end - begin) % 8 != 0 || end - begin > 32768L * 8) {
                throw new IOException("Injury registry direct-vector anchor failed");
            }
            if (index < (end - begin) / 8) return reader.readU64(begin + index * 8L);
        }
        return fallback;
    }

    private long indexedPointer(ProcessMemoryReader reader, long vector, int index, int maximum)
            throws IOException {
        long begin = reader.readU64(vector + layout.vectorBeginRel());
        long end = reader.readU64(vector + layout.vectorEndRel());
        long capacity = reader.readU64(vector + layout.vectorCapacityRel());
        if (begin < 0 || begin > end || end > capacity || (end - begin) % 8 != 0
                || capacity - begin > maximum * 8L) throw new IOException("Injury pointer-vector anchor failed");
        return index >= (end - begin) / 8 ? -1 : reader.readU64(begin + index * 8L);
    }

    private long[] vector(ProcessMemoryReader reader, long vector, int maximum) throws IOException {
        long begin = reader.readU64(vector + layout.vectorBeginRel());
        long end = reader.readU64(vector + layout.vectorEndRel());
        long capacity = reader.readU64(vector + layout.vectorCapacityRel());
        if (begin < 0 || begin > end || end > capacity || (end - begin) % 8 != 0
                || capacity - begin > maximum * 8L) throw new IOException("Injury pointer-vector anchor failed");
        long[] result = new long[(int) ((end - begin) / 8)];
        for (int i = 0; i < result.length; i++) result[i] = reader.readU64(begin + i * 8L);
        return result;
    }

    public static HistoricalInjury recent(List<HistoricalInjury> history, LocalDate date,
            int lookback, int maximumOccurrence) {
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoricalInjury injury = history.get(i);
            if (nativeDelta(injury.end(), date) > lookback) return null;
            if (injury.occurrence() > 0 && injury.occurrence() <= maximumOccurrence) return injury;
        }
        return null;
    }

    public static HistoricalInjury repeated(List<HistoricalInjury> history, LocalDate date,
            boolean sameType) {
        HistoricalInjury first = null;
        int count = 0, duration = 0;
        boolean sameBody = true;
        for (HistoricalInjury injury : history) {
            if (nativeDelta(injury.end(), date) > 250 || !injury.repeatable()
                    || injury.duration() <= 5 && injury.state() == 3) continue;
            if (first == null) first = injury;
            else if (sameType && first.type() != injury.type()) break;
            else if (first.bodyPart() != injury.bodyPart()) sameBody = false;
            count++; duration += injury.duration();
        }
        if (count < 3 || duration / (float) count <= 7f) return null;
        return sameBody ? first : new HistoricalInjury(0, first.start(), first.duration(),
                first.state(), first.occurrence(), first.bodyPart(), first.repeatable());
    }

    /** Exact low bits used by the recovery branch of native1BBA6E0(mode3). */
    public static int recoveryAvailability(int condition, int sharpness, int fatigue) {
        if (condition <= 6999) return sharpness > 6499 ? 4 : 0x80;
        if (condition > 8999) return 0;
        if (sharpness < 6500 && fatigue < -500) return 0x80;
        if (fatigue <= -501) return 4;
        return fatigue < -250 || condition < 8000 ? 8 : 0;
    }

    private static int nativeDelta(LocalDate from, LocalDate to) {
        return (to.getYear() - from.getYear()) * 365 + to.getDayOfYear() - from.getDayOfYear();
    }

    public record StaffContext(int uniqueId, int sportsScience, Integer availabilityFlags,
            boolean unavailableWithoutInjury) {
        int seed() { return uniqueId % 120 - 60 + 10 * sportsScience; }
    }
    public record Assessment(int flags, long primaryType, long secondaryType, String unresolvedReason) {
        public boolean resolved() { return unresolvedReason == null; }
        /** Native12E036B0's first assessment mask imposes85 regardless of injury selection. */
        public String guaranteedSusceptibilityLabel() {
            return (flags & 0x102) != 0 ? "Very High" : null;
        }
    }
    public static final class HistoryUnavailableException extends IOException {
        public HistoryUnavailableException(String message) { super(message); }
    }
    public record HistoricalInjury(long type, LocalDate start, int duration, int state,
            int occurrence, int bodyPart, boolean repeatable) {
        LocalDate end() { return start.plusDays(duration); }
    }

    private ActiveInjury readInjury(ProcessMemoryReader reader, long injury) throws IOException {
        long type = reader.readU64(injury + layout.typeRel());
        int day = reader.readU16(injury + layout.startDayRel()) & 0x1FF;
        int year = reader.readU16(injury + layout.startYearRel());
        int full = reader.readI16(injury + layout.fullDurationRel());
        int light = reader.readI16(injury + layout.lightDurationRel());
        int state = reader.readU8(injury + layout.stateRel());
        int weeks = (byte) reader.readU8(injury + layout.weeksRemainingRel());
        if (type <= 0 || type > ProcessMemoryReader.MAX_USER_ADDRESS || year < 1900
                || year > 2500 || full < -1 || full > 7300 || light < -1 || light > 7300
                || state > 7 || weeks < -1) {
            throw new IOException("Player active injury range anchor failed");
        }
        try {
            return new ActiveInjury(injury, type, LocalDate.ofYearDay(year, day),
                    full, light, state, weeks);
        } catch (DateTimeException ex) {
            throw new IOException("Player injury date anchor failed", ex);
        }
    }

    public record ActiveInjury(long address, long type, LocalDate start, int fullDuration,
            int lightDuration, int state, int weeksRemaining) {
        /** Native 2EE3400 uses the week estimate when one is recorded. */
        public int fullDaysRemaining(LocalDate date) {
            return remaining(date, fullDuration);
        }
        /** Native 2EE2B50 uses light training duration, including its week override. */
        public int lightDaysRemaining(LocalDate date) {
            return remaining(date, lightDuration);
        }
        private int remaining(LocalDate date, int duration) {
            if (weeksRemaining >= 0) return weeksRemaining * 7;
            if (date.equals(LocalDate.of(1900, 1, 1))) return duration;
            return (short) ChronoUnit.DAYS.between(date, start.plusDays(duration));
        }
        /** Native 409A1B0 recovery flag; its date delta deliberately assumes 365-day years. */
        public boolean susceptibilityRecoveryComplete(LocalDate date) {
            if (state == 3 && fullDuration > lightDuration
                    && (weeksRemaining >= 0 ? weeksRemaining * 7 : fullDuration) < 6) return true;
            int duration = weeksRemaining >= 0 ? weeksRemaining * 7 : lightDuration;
            int elapsed = (date.getYear() - start.getYear()) * 365
                    + date.getDayOfYear() - start.getDayOfYear();
            return elapsed >= duration;
        }
    }
}
