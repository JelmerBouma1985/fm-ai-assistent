package com.github.fmaiassistent.ai;

@FunctionalInterface
public interface AiPromptContext {
    String enrich(String conversationKey, String userMessage);

    static AiPromptContext none() {
        return (conversationKey, userMessage) -> userMessage;
    }
}
