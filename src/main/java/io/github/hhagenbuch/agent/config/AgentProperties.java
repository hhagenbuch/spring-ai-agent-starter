package io.github.hhagenbuch.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Central agent configuration, bound from the {@code agent.*} namespace.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("claude-sonnet-5") String model,
        @DefaultValue("1024") int maxTokens,
        @DefaultValue("6") int maxToolIterations,
        @DefaultValue("3") int maxRetries,
        List<McpServer> mcpServers) {

    public AgentProperties {
        mcpServers = mcpServers == null ? List.of() : mcpServers;
    }

    /**
     * A stdio MCP server to mount. {@code command} is the argv used to launch it
     * (e.g. {@code ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"]}).
     */
    public record McpServer(@DefaultValue("mcp") String name, List<String> command) {
        public McpServer {
            command = command == null ? List.of() : command;
        }
    }
}
