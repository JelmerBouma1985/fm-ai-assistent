package com.github.fmaiassistent.copilot;

import java.util.LinkedHashMap;
import java.util.Map;

/** Combines Copilot's streamed deltas and full messages without losing tool-following answers. */
final class CopilotAssistantTextAccumulator {
    private final StringBuilder text = new StringBuilder();
    private final Map<String, StringBuilder> messages = new LinkedHashMap<>();

    String appendDelta(String messageId, String delta) {
        StringBuilder message = messages.get(messageId);
        String prefix = "";
        if (message == null) {
            message = new StringBuilder();
            messages.put(messageId, message);
            prefix = text.isEmpty() ? "" : "\n\n";
        }
        message.append(delta);
        String addition = prefix + delta;
        text.append(addition);
        return addition;
    }

    String appendFinal(String messageId, String finalText) {
        StringBuilder message = messages.get(messageId);
        if (message == null) {
            messages.put(messageId, new StringBuilder(finalText));
            String addition = text.isEmpty() ? finalText : "\n\n" + finalText;
            text.append(addition);
            return addition;
        }

        String streamed = message.toString();
        String addition;
        if (streamed.equals(finalText) || streamed.endsWith(finalText)) {
            addition = "";
        } else if (finalText.startsWith(streamed)) {
            addition = finalText.substring(streamed.length());
        } else {
            addition = "\n\n" + finalText;
        }
        message.append(addition);
        text.append(addition);
        return addition;
    }

    String text() {
        return text.toString();
    }

    boolean isEmpty() {
        return text.isEmpty();
    }

    void clear() {
        text.setLength(0);
        messages.clear();
    }
}
