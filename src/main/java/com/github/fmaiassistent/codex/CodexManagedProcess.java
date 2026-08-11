package com.github.fmaiassistent.codex;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

interface CodexManagedProcess {
    BufferedReader stdout();

    BufferedReader stderr();

    BufferedWriter stdin();

    long pid();

    boolean isAlive();

    CompletableFuture<Integer> onExit();

    void closeInput() throws IOException;

    void terminate(Duration timeout);
}
