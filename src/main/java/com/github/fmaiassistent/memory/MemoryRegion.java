package com.github.fmaiassistent.memory;

public record MemoryRegion(long start, long end, String perms, long offset, String device, String inode, String path) {
    public MemoryRegion {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid memory region bounds");
        }
        perms = perms == null ? "" : perms;
        device = device == null ? "" : device;
        inode = inode == null ? "" : inode;
        path = path == null ? "" : path;
    }

    public long size() {
        return end - start;
    }

    public boolean readable() {
        return perms.startsWith("r");
    }

    public boolean writable() {
        return perms.length() > 1 && perms.charAt(1) == 'w';
    }

    public boolean contains(long address, int length) {
        return address >= start && length >= 0 && address <= end - length;
    }
}
