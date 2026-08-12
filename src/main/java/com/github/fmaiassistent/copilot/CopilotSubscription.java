package com.github.fmaiassistent.copilot;

@FunctionalInterface
public interface CopilotSubscription extends AutoCloseable {
    @Override
    void close();
}
