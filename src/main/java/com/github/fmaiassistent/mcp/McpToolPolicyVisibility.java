package com.github.fmaiassistent.mcp;

import com.github.fmaiassistent.tools.ToolPolicy;
import com.github.fmaiassistent.tools.ToolRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.stream.Collectors;

/** Uses the SDK's supported dynamic registration API, including list-changed notifications. */
@Component
public final class McpToolPolicyVisibility {
    private final ToolRegistry registry;
    private final ObjectProvider<McpSyncServer> servers;
    private AutoCloseable subscription;
    public McpToolPolicyVisibility(ToolRegistry registry, ObjectProvider<McpSyncServer> servers) {
        this.registry = registry; this.servers = servers;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        subscription = registry.policy().subscribe(this::update);
        update();
    }
    private synchronized void update() {
        servers.orderedStream().forEach(server -> {
            var present = server.listTools().stream().map(tool -> tool.name()).collect(Collectors.toSet());
            for (var callback : registry.getToolCallbacks()) {
                String id = callback.getToolDefinition().name();
                boolean enabled = registry.policy().global(id, registry.descriptor(id).effect()) != ToolPolicy.Access.DISABLED;
                if (enabled && !present.contains(id)) server.addTool(McpToolUtils.toSyncToolSpecification(callback));
                else if (!enabled && present.contains(id)) server.removeTool(id);
            }
        });
    }
    @PreDestroy void close() {
        if (subscription != null) { try { subscription.close(); } catch (Exception ignored) { } }
    }
}
