package com.github.fmaiassistent.antigravity;

@FunctionalInterface
public interface AntigravitySubscription extends AutoCloseable {
    @Override
    void close();
}
