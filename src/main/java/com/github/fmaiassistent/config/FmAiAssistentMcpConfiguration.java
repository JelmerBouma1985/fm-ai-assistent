package com.github.fmaiassistent.config;

import com.github.fmaiassistent.mcp.FmAiAssistentTools;
import com.github.fmaiassistent.mcp.FmDecisionTools;
import com.github.fmaiassistent.mcp.FmFixtureTools;
import com.github.fmaiassistent.mcp.FmSnapshotTools;
import com.github.fmaiassistent.mcp.CompactFmToolCallback;
import com.github.fmaiassistent.mcp.FmToolResultFormatter;
import com.github.fmaiassistent.tools.ToolRegistry;
import com.github.fmaiassistent.tools.ToolPolicy;
import com.github.fmaiassistent.tools.ToolActivity;
import com.github.fmaiassistent.tools.ToolAvailability;
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
    ToolRegistry fmAiAssistentToolCallbackProvider(
            FmAiAssistentTools tools,
            FmSnapshotTools snapshotTools,
            FmDecisionTools decisionTools,
            FmFixtureTools fixtureTools,
            ObjectMapper json, ToolPolicy policy, ToolAvailability availability, ToolActivity activity) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools, snapshotTools, decisionTools, fixtureTools)
                .build().getToolCallbacks();
        return new ToolRegistry(Arrays.stream(callbacks)
                .map(callback -> FmToolResultFormatter.supports(callback.getToolDefinition().name())
                        ? new CompactFmToolCallback(callback, json) : callback)
                .toArray(ToolCallback[]::new), new Object[] { tools, snapshotTools, decisionTools, fixtureTools },
                json, policy, availability, activity);
    }
}
