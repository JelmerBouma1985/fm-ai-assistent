package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.MemoryRegion;

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

public class LinuxProcessReader implements ProcessMemoryReader {
    private final int pid;
    private final FileChannel mem;

    public LinuxProcessReader(int pid) throws IOException {
        this.pid = pid;
        this.mem = FileChannel.open(Path.of("/proc", Integer.toString(pid), "mem"), StandardOpenOption.READ);
    }

    public int pid() {
        return pid;
    }

    @Override
    public Platform platform() {
        return Platform.LINUX;
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
            throw new IllegalArgumentException("size must not be negative");
        }
        byte[] result = new byte[size];
        readInto(address, result, 0, size);
        return result;
    }

    @Override
    public void readInto(long address, byte[] target, int offset, int length) throws IOException {
        ProcessMemoryReader.validateRead(address, target, offset, length);
        ByteBuffer buffer = ByteBuffer.wrap(target, offset, length);
        int startPosition = buffer.position();
        while (buffer.hasRemaining()) {
            int total = buffer.position() - startPosition;
            int n = mem.read(buffer, address + total);
            if (n < 0) {
                break;
            }
            if (n == 0) {
                throw new IOException("zero-byte read at 0x" + Long.toHexString(address + total));
            }
        }
        if (buffer.hasRemaining()) {
            throw new IOException("short read at 0x" + Long.toHexString(address));
        }
    }

    public List<MemoryRegion> maps() throws IOException {
        return parseMaps(Files.readString(Path.of("/proc", Integer.toString(pid), "maps")));
    }

    static List<MemoryRegion> parseMaps(String contents) {
        List<MemoryRegion> regions = new ArrayList<>();
        for (String line : contents.split("\\R")) {
            String[] parts = line.split("\\s+", 6);
            if (parts.length < 5) {
                continue;
            }
            String[] range = parts[0].split("-", 2);
            if (range.length != 2) {
                continue;
            }
            try {
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                // /proc/<pid>/maps includes kernel-space entries such as
                // ffffffffff600000-ffffffffff601000 [vsyscall]. Those unsigned
                // addresses cannot be represented as positive Java longs and are
                // outside the user-space range this reader can access.
                if (start < 0 || end < 0 || start > ProcessMemoryReader.MAX_USER_ADDRESS || end <= start) {
                    continue;
                }
                regions.add(new MemoryRegion(
                        start,
                        end,
                        parts[1],
                        Long.parseUnsignedLong(parts[2], 16),
                        parts[3],
                        parts[4],
                        parts.length == 6 ? parts[5] : ""));
            } catch (NumberFormatException ignored) {
                // A concurrently exiting process can expose an incomplete line.
                // Keep the valid snapshot entries instead of aborting every scan.
            }
        }
        return regions;
    }

    @Override
    public void close() throws IOException {
        mem.close();
    }
}
