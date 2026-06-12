package com.github.fmaiscout.linux;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class LinuxProcessReader implements AutoCloseable {
    private static final long MAX_USER_ADDRESS = 0x00007FFFFFFFFFFFL;

    private final int pid;
    private final FileChannel mem;

    public LinuxProcessReader(int pid) throws IOException {
        this.pid = pid;
        this.mem = FileChannel.open(Path.of("/proc", Integer.toString(pid), "mem"), StandardOpenOption.READ);
    }

    public int pid() {
        return pid;
    }

    public static List<ProcessInfo> findProcesses(String query) throws IOException {
        String needle = query.toLowerCase();
        List<ProcessInfo> matches = new ArrayList<>();
        try (var paths = Files.list(Path.of("/proc"))) {
            for (Path entry : paths.toList()) {
                String fileName = entry.getFileName().toString();
                if (!fileName.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                try {
                    String name = Files.readString(entry.resolve("comm")).trim();
                    String cmdline = new String(Files.readAllBytes(entry.resolve("cmdline")), StandardCharsets.UTF_8)
                            .replace('\0', ' ')
                            .trim();
                    String haystack = (name + " " + cmdline).toLowerCase();
                    if (haystack.contains(needle)) {
                        matches.add(new ProcessInfo(Integer.parseInt(fileName), name, cmdline));
                    }
                } catch (IOException | NumberFormatException ignored) {
                }
            }
        }
        matches.sort(Comparator.comparingInt(ProcessInfo::pid));
        return matches;
    }

    public byte[] readBytes(long address, int size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int total = 0;
        while (total < size) {
            int n = mem.read(buffer, address + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        if (total != size) {
            throw new IOException("short read at 0x" + Long.toHexString(address));
        }
        return buffer.array();
    }

    public List<MemoryRegion> maps() throws IOException {
        List<MemoryRegion> regions = new ArrayList<>();
        for (String line : Files.readString(Path.of("/proc", Integer.toString(pid), "maps")).split("\\R")) {
            String[] parts = line.split("\\s+", 6);
            if (parts.length < 5) {
                continue;
            }
            String[] range = parts[0].split("-", 2);
            regions.add(new MemoryRegion(
                    Long.parseUnsignedLong(range[0], 16),
                    Long.parseUnsignedLong(range[1], 16),
                    parts[1],
                    Long.parseUnsignedLong(parts[2], 16),
                    parts[3],
                    parts[4],
                    parts.length == 6 ? parts[5] : ""));
        }
        return regions;
    }

    public int readU8(long address) throws IOException {
        return readBytes(address, 1)[0] & 0xff;
    }

    public int readU16(long address) throws IOException {
        return le(readBytes(address, 2)).getShort() & 0xffff;
    }

    public short readI16(long address) throws IOException {
        return le(readBytes(address, 2)).getShort();
    }

    public long readU32(long address) throws IOException {
        return le(readBytes(address, 4)).getInt() & 0xffffffffL;
    }

    public int readI32(long address) throws IOException {
        return le(readBytes(address, 4)).getInt();
    }

    public long readU64(long address) throws IOException {
        return le(readBytes(address, 8)).getLong();
    }

    public Optional<Long> qwordOrNull(long address) {
        if (address <= 0 || address > MAX_USER_ADDRESS) {
            return Optional.empty();
        }
        try {
            long value = readU64(address);
            if (value <= 0 || value > MAX_USER_ADDRESS) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public Optional<String> readFmLenString(long address, int maxLen) {
        if (address <= 0 || address > MAX_USER_ADDRESS) {
            return Optional.empty();
        }
        try {
            byte[] header = readBytes(address, 4);
            int size = le(header).getInt();
            if (size <= 0 || size > maxLen) {
                return Optional.empty();
            }
            byte[] data = readBytes(address + 4, size);
            for (byte b : data) {
                int ch = b & 0xff;
                if (ch != 9 && ch != 10 && ch != 13 && (ch < 32 || ch >= 0xf5)) {
                    return Optional.empty();
                }
            }
            return Optional.of(new String(data, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public Optional<String> readFmStringObject(long address, int maxLen) {
        return qwordOrNull(address).flatMap(target -> readFmLenString(target, maxLen));
    }

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void close() throws IOException {
        mem.close();
    }
}
