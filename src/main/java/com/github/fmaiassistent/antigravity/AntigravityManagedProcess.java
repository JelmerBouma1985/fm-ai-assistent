package com.github.fmaiassistent.antigravity;

import java.io.BufferedReader;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

interface AntigravityManagedProcess {
    BufferedReader stdout();

    BufferedReader stderr();

    long pid();

    boolean isAlive();

    CompletableFuture<Integer> onExit();

    void terminate(Duration timeout);
}
