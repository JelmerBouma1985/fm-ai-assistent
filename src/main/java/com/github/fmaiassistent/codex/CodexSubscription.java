package com.github.fmaiassistent.codex;

@FunctionalInterface
public interface CodexSubscription extends AutoCloseable {
    @Override
    void close();
}
