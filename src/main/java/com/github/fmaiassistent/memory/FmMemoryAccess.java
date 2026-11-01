package com.github.fmaiassistent.memory;

import java.io.IOException;

/** Keeps snapshot reads separate from temporary changes made while generating reports. */
public final class FmMemoryAccess {
    private FmMemoryAccess() {
    }

    public static synchronized <T> T exclusive(Operation<T> operation) throws IOException {
        return operation.run();
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws IOException;
    }
}
