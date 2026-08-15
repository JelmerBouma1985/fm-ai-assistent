package com.github.fmaiassistent.ai;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CompositeAiPromptContext implements AiPromptContext {
    private final List<AiPromptContextContributor> contributors;

    CompositeAiPromptContext(List<AiPromptContextContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    @Override
    public String enrich(String conversationKey, String userMessage) {
        List<String> context = contributors.stream()
                .map(contributor -> contributor.contextFor(conversationKey))
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (context.isEmpty()) {
            return userMessage;
        }
        return """
                The following Football Manager 2026 context was loaded locally by the application. Treat it as factual context for the current save. Use it when relevant, and do not invent values that are absent.

                %s

                User message:
                %s
                """.formatted(String.join("\n\n", context), userMessage);
    }
}
