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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Connects to each configured stdio MCP server once the application is ready,
 * discovers its tools, and registers them with the {@link ToolRegistry} as
 * {@link McpToolAdapter}s. A server that fails to start or handshake is logged
 * and skipped — a bad MCP config must not take down the agent.
 */
@Component
public class McpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    /** Cap on a single server's start-up handshake so one hung config can't freeze app startup. */
    private static final long HANDSHAKE_TIMEOUT_SECONDS = 10;

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
        McpClient client = null;
        try {
            client = new McpClient(server.command(), mapper);
            clients.add(client);
            // readLine() in the handshake blocks indefinitely; bound it so a
            // hung or silent server can't stall ApplicationReadyEvent forever.
            JsonNode tools = handshakeWithTimeout(client);
            int mounted = 0;
            for (JsonNode definition : tools) {
                registry.register(new McpToolAdapter(client, mapper, definition));
                mounted++;
            }
            log.info("Mounted {} tool(s) from MCP server '{}'", mounted, server.name());
        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}' ({}): {}",
                    server.name(), server.command(), e.getMessage());
            if (e instanceof TimeoutException && client != null) {
                client.close(); // kill the child so the hung process doesn't linger
            }
        }
    }

    private JsonNode handshakeWithTimeout(McpClient client)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.handshakeAndListTools();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        clients.forEach(McpClient::close);
    }
}
