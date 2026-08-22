package com.github.fmaiassistent.config;

import com.github.fmaiassistent.mcp.FmAiAssistentTools;
import com.github.fmaiassistent.mcp.FmDecisionTools;
import com.github.fmaiassistent.mcp.FmSnapshotTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FmAiAssistentMcpConfiguration {

    @Bean
    ToolCallbackProvider fmAiAssistentToolCallbackProvider(
            FmAiAssistentTools tools,
            FmSnapshotTools snapshotTools,
            FmDecisionTools decisionTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools, snapshotTools, decisionTools)
                .build();
    }
}
