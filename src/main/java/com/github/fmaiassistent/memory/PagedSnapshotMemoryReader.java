package com.github.fmaiassistent.memory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, single-phase read cache. Closing it leaves the caller-owned reader open. */
public final class PagedSnapshotMemoryReader implements ProcessMemoryReader {
    private static final int PAGE_BYTES = 4096;
    private static final int MAX_READ_BYTES = 8 * 1024 * 1024;
    private final ProcessMemoryReader delegate;
    private final Map<Long, byte[]> pages;
    private boolean closed;

    public PagedSnapshotMemoryReader(ProcessMemoryReader delegate, int maximumPages) {
        if (maximumPages < 1 || maximumPages > 16_384) throw new IllegalArgumentException("Invalid snapshot page cap");
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.pages = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> entry) {
                return size() > maximumPages;
            }
        };
    }

    @Override public int pid() { return delegate.pid(); }
    @Override public Platform platform() { return delegate.platform(); }
    @Override public List<MemoryRegion> maps() throws IOException { return delegate.maps(); }

    @Override public byte[] readBytes(long address, int size) throws IOException {
        check(address, size);
        byte[] result = new byte[size];
        readInto(address, result, 0, size);
        return result;
    }

    @Override public void readInto(long address, byte[] target, int offset, int length) throws IOException {
        check(address, length);
        ProcessMemoryReader.validateRead(address, target, offset, length);
        for (int copied = 0; copied < length;) {
            long current = address + copied;
            long page = current & -((long) PAGE_BYTES);
            int inPage = (int) (current - page);
            int chunk = Math.min(PAGE_BYTES - inPage, length - copied);
            byte[] bytes = pages.get(page);
            if (bytes == null) {
                try {
                    bytes = delegate.readBytes(page, PAGE_BYTES);
                    if (bytes.length != PAGE_BYTES) throw new IOException("Incomplete snapshot page");
                    pages.put(page, bytes);
                } catch (IOException exception) {
                    // A valid narrow read may sit beside inaccessible memory or a
                    // deliberately sparse fixture. Preserve the underlying reader's semantics.
                    delegate.readInto(current, target, offset + copied, chunk);
                    copied += chunk;
                    continue;
                }
            }
            System.arraycopy(bytes, inPage, target, offset + copied, chunk);
            copied += chunk;
        }
    }

    private void check(long address, int size) throws IOException {
        if (closed) throw new IOException("Snapshot reader is closed");
        if (size < 0 || size > MAX_READ_BYTES || address < 0 || address > MAX_USER_ADDRESS
                || address > MAX_USER_ADDRESS - size + 1)
            throw new IOException("Snapshot read exceeds address or size bounds");
    }

    @Override public void close() {
        pages.clear();
        closed = true;
    }
}
