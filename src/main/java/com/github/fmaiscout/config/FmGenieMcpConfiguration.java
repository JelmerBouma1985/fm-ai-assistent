package com.github.fmaiscout.config;

import com.github.fmaiscout.mcp.FmGenieMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FmGenieMcpConfiguration {

    @Bean
    ToolCallbackProvider fmGenieToolCallbackProvider(FmGenieMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
