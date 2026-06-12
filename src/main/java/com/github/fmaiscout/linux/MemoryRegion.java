package com.github.fmaiscout.linux;

public record MemoryRegion(long start, long end, String perms, long offset, String device, String inode, String path) {
    public long size() {
        return end - start;
    }

    public boolean readable() {
        return perms.startsWith("r");
    }

    public boolean writable() {
        return perms.length() > 1 && perms.charAt(1) == 'w';
    }
}
