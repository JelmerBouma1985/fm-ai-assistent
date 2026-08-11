package com.github.fmaiassistent.antigravity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
class AntigravityCommandFactory {
    private final AntigravityProperties properties;
    private final AntigravityExecutableResolver executableResolver;

    AntigravityCommandFactory(
            AntigravityProperties properties,
            AntigravityExecutableResolver executableResolver) {
        this.properties = properties;
        this.executableResolver = executableResolver;
    }

    List<String> command(String conversationId, String prompt) {
        List<String> command = new ArrayList<>();
        command.add(executableResolver.resolve());
        command.add("-p");
        command.add(prompt);
        if (conversationId != null && !conversationId.isBlank()) {
            command.add("--conversation");
            command.add(conversationId);
        }
        command.add("--output-format");
        command.add("stream-json");
        command.add("--print-timeout");
        command.add(properties.printTimeout().toSeconds() + "s");
        addOption(command, "--model", properties.model());
        addOption(command, "--effort", properties.effort());
        addOption(command, "--agent", properties.agent());
        if (properties.sandbox()) {
            command.add("--sandbox");
        }
        return List.copyOf(command);
    }

    private static void addOption(List<String> command, String flag, String value) {
        if (value != null) {
            command.add(flag);
            command.add(value);
        }
    }
}
