package com.github.fmaiassistent.antigravity;

import java.util.concurrent.CompletableFuture;

public record AntigravityTurnHandle(
        String turnId,
        CompletableFuture<AntigravityProcessResult> completion,
        Runnable cancelAction) {

    public void cancel() {
        cancelAction.run();
    }
}
