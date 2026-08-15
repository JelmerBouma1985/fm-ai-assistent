package com.github.fmaiassistent.ai;

@FunctionalInterface
public interface AiPromptContextContributor {
    String contextFor(String conversationKey);
}
