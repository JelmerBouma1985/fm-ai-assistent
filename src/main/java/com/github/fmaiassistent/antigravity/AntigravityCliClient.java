package com.github.fmaiassistent.antigravity;

import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
class AntigravityCliClient {
    private final AntigravityProperties properties;
    private final AntigravityExecutableResolver executableResolver;
    private final AntigravityCommandFactory commandFactory;
    private final AntigravityProcessRunner runner;

    AntigravityCliClient(
            AntigravityProperties properties,
            AntigravityExecutableResolver executableResolver,
            AntigravityCommandFactory commandFactory,
            AntigravityProcessRunner runner) {
        this.properties = properties;
        this.executableResolver = executableResolver;
        this.commandFactory = commandFactory;
        this.runner = runner;
    }

    AntigravityTurnHandle start(
            String turnId,
            String conversationId,
            String prompt,
            Consumer<AntigravityStreamEvent> listener) {
        if (!properties.enabled()) {
            throw new AntigravityException(
                    AntigravityException.Code.EXECUTABLE_NOT_FOUND, "Antigravity integration is disabled");
        }
        if (!executableResolver.isAvailable()) {
            throw new AntigravityException(
                    AntigravityException.Code.EXECUTABLE_NOT_FOUND,
                    "Antigravity CLI is not installed. Make sure `" + properties.executable() + "` is available on PATH.");
        }
        return runner.start(turnId, commandFactory.command(conversationId, prompt), listener);
    }

    boolean available() {
        return properties.enabled() && executableResolver.isAvailable();
    }
}
