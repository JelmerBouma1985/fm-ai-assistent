package com.github.fmaiassistent.config;

import com.github.fmaiassistent.mcp.FmAiAssistentTools;
import com.github.fmaiassistent.mcp.FmDecisionTools;
import com.github.fmaiassistent.mcp.FmSnapshotTools;
import com.github.fmaiassistent.mcp.CompactFmToolCallback;
import com.github.fmaiassistent.mcp.FmToolResultFormatter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

@Configuration
class FmAiAssistentMcpConfiguration {

    @Bean
    ToolCallbackProvider fmAiAssistentToolCallbackProvider(
            FmAiAssistentTools tools,
            FmSnapshotTools snapshotTools,
            FmDecisionTools decisionTools,
            ObjectMapper json) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools, snapshotTools, decisionTools)
                .build().getToolCallbacks();
        return ToolCallbackProvider.from(Arrays.stream(callbacks)
                .map(callback -> FmToolResultFormatter.supports(callback.getToolDefinition().name())
                        ? new CompactFmToolCallback(callback, json) : callback)
                .toArray(ToolCallback[]::new));
    }
}
