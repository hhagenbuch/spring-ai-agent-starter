package io.github.hhagenbuch.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.config.AgentProperties.McpServer;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Connects to each configured stdio MCP server once the application is ready,
 * discovers its tools, and registers them with the {@link ToolRegistry} as
 * {@link McpToolAdapter}s. A server that fails to start or handshake is logged
 * and skipped — a bad MCP config must not take down the agent.
 */
@Component
public class McpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private final AgentProperties props;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final List<McpClient> clients = new ArrayList<>();

    public McpConnectionManager(AgentProperties props, ToolRegistry registry, ObjectMapper mapper) {
        this.props = props;
        this.registry = registry;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connectAll() {
        for (McpServer server : props.mcpServers()) {
            if (server.command().isEmpty()) {
                log.warn("MCP server '{}' has no command; skipping", server.name());
                continue;
            }
            connect(server);
        }
    }

    private void connect(McpServer server) {
        try {
            McpClient client = new McpClient(server.command(), mapper);
            clients.add(client);
            JsonNode tools = client.handshakeAndListTools();
            int mounted = 0;
            for (JsonNode definition : tools) {
                registry.register(new McpToolAdapter(client, mapper, definition));
                mounted++;
            }
            log.info("Mounted {} tool(s) from MCP server '{}'", mounted, server.name());
        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}' ({}): {}",
                    server.name(), server.command(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.forEach(McpClient::close);
    }
}
