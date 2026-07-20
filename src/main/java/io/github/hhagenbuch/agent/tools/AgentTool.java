package io.github.hhagenbuch.agent.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

/**
 * A capability the agent can invoke. Implementations must be side-effect
 * aware: anything destructive should be idempotent or confirm-gated.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema describing the expected input object. */
    ObjectNode inputSchema(ObjectMapper mapper);

    /** Execute with validated input; return a plain-text result for the model. */
    Mono<String> execute(JsonNode input);
}
