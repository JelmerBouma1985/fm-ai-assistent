package com.github.fmaiassistent.linux;

import com.github.fmaiassistent.memory.ProcessMemoryReader;

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

    @Override
    public void close() throws IOException {
        mem.close();
    }
}
