package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.ProcessInfo;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * One-off spike: locate structures in FM26 memory by searching for known
 * content (e.g. the active tactic name). Read-only. Runs only when
 * FM_SPIKE_TACTIC is set, never in CI.
 *
 * <p>Modes via FM_SPIKE_MODE: {@code addrs} prints hit addresses plus the
 * preceding 8 qwords as a struct signature (for diffing two game states);
 * {@code full} also dumps and classifies the surroundings.</p>
 */
@EnabledIfEnvironmentVariable(named = "FM_SPIKE_TACTIC", matches = ".+")
class TacticNameSpikeTest {
    private static final int CHUNK = 1 << 20;
    private static final int MAX_HITS = 200;

    @Test
    void scanForTacticName() throws Exception {
        String name = System.getenv("FM_SPIKE_TACTIC");
        boolean full = "full".equalsIgnoreCase(System.getenv("FM_SPIKE_MODE"));
        byte[] needle = name.getBytes(StandardCharsets.UTF_8);
        int pid = findGamePid();
        System.out.println("SPIKE pid=" + pid + " needle=" + name + " full=" + full);
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            int hits = 0;
            long scanned = 0;
            int regions = 0;
            for (MemoryRegion region : reader.maps()) {
                if (!scannable(region)) {
                    continue;
                }
                regions++;
                for (long base = region.start(); base < region.end() && hits < MAX_HITS; base += CHUNK) {
                    int size = (int) Math.min((long) CHUNK + needle.length, region.end() - base);
                    byte[] data;
                    try {
                        data = reader.readBytes(base, size);
                    } catch (IOException | RuntimeException unreadable) {
                        continue;
                    }
                    scanned += size;
                    for (int index = indexOf(data, needle, 0);
                            index >= 0 && hits < MAX_HITS;
                            index = indexOf(data, needle, index + 1)) {
                        long address = base + index;
                        if (!isFmString(data, index, needle.length)) {
                            continue;
                        }
                        hits++;
                        if (full) {
                            reportFull(reader, classifier, address);
                        } else {
                            reportCompact(reader, data, index, base, address, region.path());
                        }
                    }
                }
                if (hits >= MAX_HITS) {
                    break;
                }
            }
            System.out.println("SPIKE done regions=" + regions + " scannedBytes=" + scanned + " hits=" + hits);
        }
    }

    /**
     * Reads one absolute address as a qword probe for targeted follow-ups.
     * Enabled with FM_SPIKE_PROBE=0x... (comma-separated) alongside FM_SPIKE_TACTIC.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "FM_SPIKE_PROBE", matches = ".+")
    void probeAddresses() throws Exception {
        int pid = findGamePid();
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            PersonMemoryClassifier classifier = new PersonMemoryClassifier(reader);
            for (String token : System.getenv("FM_SPIKE_PROBE").split(",")) {
                long address = Long.decode(token.trim());
                System.out.println("SPIKE probe 0x" + Long.toHexString(address));
                reportFull(reader, classifier, address);
            }
        }
    }

    private static int findGamePid() throws IOException {
        List<ProcessInfo> candidates = ProcessReaders.findProcesses("fm.exe");
        for (ProcessInfo candidate : candidates) {
            try (ProcessMemoryReader reader = ProcessReaders.open(candidate.pid())) {
                for (MemoryRegion region : reader.maps()) {
                    if (region.path().toLowerCase(Locale.ROOT).endsWith("fm.exe")) {
                        return candidate.pid();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        throw new IOException("fm.exe process not found among " + candidates.size() + " candidates");
    }

    private static boolean scannable(MemoryRegion region) {
        if (!region.readable() || !region.writable()) {
            return false;
        }
        String path = region.path();
        return path.isEmpty() || path.startsWith("[heap]") || path.startsWith("[anon");
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean isFmString(byte[] chunk, int index, int length) {
        if (index < Integer.BYTES) {
            return false;
        }
        int prefix = (chunk[index - 4] & 0xff)
                | ((chunk[index - 3] & 0xff) << 8)
                | ((chunk[index - 2] & 0xff) << 16)
                | ((chunk[index - 1] & 0xff) << 24);
        return prefix == length;
    }

    private static void reportCompact(
            ProcessMemoryReader reader, byte[] chunk, int index, long base, long address, String regionPath) {
        StringBuilder line = new StringBuilder("SPIKE hit at 0x").append(Long.toHexString(address));
        String path = regionPath.length() > 40 ? "..." + regionPath.substring(regionPath.length() - 37) : regionPath;
        line.append(" [").append(path).append(']');
        for (int offset = -0x40; offset < 0; offset += Long.BYTES) {
            if (index + offset < 0) {
                line.append(" ????????");
                continue;
            }
            long value = 0;
            for (int b = 0; b < Long.BYTES; b++) {
                value |= ((long) (chunk[index + offset + b] & 0xff)) << (8 * b);
            }
            line.append(' ').append(Long.toHexString(value));
        }
        System.out.println(line);
    }

    private static void reportFull(
            ProcessMemoryReader reader, PersonMemoryClassifier classifier, long address) {
        System.out.println("SPIKE hit at 0x" + Long.toHexString(address));
        for (long pointer = address - 0x200; pointer < address + 0x200; pointer += Long.BYTES) {
            long value;
            try {
                value = reader.readU64(pointer);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            StringBuilder line = new StringBuilder("  +0x")
                    .append(Long.toHexString(pointer - address))
                    .append(" = 0x")
                    .append(Long.toHexString(value));
            if (value > 0 && value <= ProcessMemoryReader.MAX_USER_ADDRESS) {
                try {
                    PersonMemoryClassifier.Classification classification = classifier.classify(value);
                    if (classification.type() != PersonMemoryClassifier.PersonType.UNKNOWN) {
                        line.append("  <-- person record: ").append(classification.type());
                    }
                } catch (IOException | RuntimeException notAPerson) {
                }
            }
            System.out.println(line);
        }
    }
}
