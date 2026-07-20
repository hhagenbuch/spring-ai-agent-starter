package io.github.hhagenbuch.agent.tools;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers all {@link AgentTool} beans and dispatches tool calls.
 * Unknown tools and tool failures are converted to error strings rather than
 * propagated — the model should see the failure and recover, not crash the loop.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> discovered) {
        discovered.forEach(t -> tools.put(t.name(), t));
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    /**
     * Registers a tool discovered at runtime (e.g. from an MCP server), so it is
     * dispatched identically to compile-time {@link AgentTool} beans. Later
     * registrations override an existing tool of the same name — we log a warning
     * because an MCP server silently shadowing a trusted local tool (e.g. its own
     * "calculator") is both a debugging trap and a supply-chain concern.
     */
    public void register(AgentTool tool) {
        if (tools.containsKey(tool.name())) {
            log.warn("Tool '{}' is being overridden by a later registration — "
                    + "an MCP server may be shadowing a local tool of the same name", tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Mono<String> execute(String name, JsonNode input) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return Mono.just("ERROR: unknown tool '" + name + "'");
        }
        return tool.execute(input)
                .onErrorResume(e -> Mono.just("ERROR: tool '" + name + "' failed: " + e.getMessage()));
    }
}
