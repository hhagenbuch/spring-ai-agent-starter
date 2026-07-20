package io.github.hhagenbuch.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Wraps a single tool exposed by an MCP server as an {@link AgentTool}, so the
 * {@code ToolRegistry} dispatches it exactly like a local tool. The blocking
 * {@code tools/call} round-trip runs on the bounded-elastic scheduler to keep
 * the reactor event loop free.
 */
final class McpToolAdapter implements AgentTool {

    /** Runtime cap on a single tools/call so a hung server can't block a chat request forever. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final McpClient client;
    private final ObjectMapper mapper;
    private final String name;
    private final String description;
    private final ObjectNode inputSchema;

    McpToolAdapter(McpClient client, ObjectMapper mapper, JsonNode definition) {
        this.client = client;
        this.mapper = mapper;
        this.name = definition.path("name").asText();
        this.description = definition.path("description").asText("");
        JsonNode schema = definition.path("inputSchema");
        this.inputSchema = schema.isObject() ? (ObjectNode) schema : emptyObjectSchema(mapper);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper unused) {
        return inputSchema;
    }

    @Override
    public Mono<String> execute(JsonNode input) {
        return Mono.fromCallable(() -> {
                    ObjectNode params = mapper.createObjectNode();
                    params.put("name", name);
                    params.set("arguments", input == null ? mapper.createObjectNode() : input);
                    return textContent(client.request("tools/call", params));
                })
                .subscribeOn(Schedulers.boundedElastic())
                // A hung server would otherwise block the chat request indefinitely.
                // ToolRegistry.execute turns this into an "ERROR: ..." string, so the
                // model sees the timeout and can recover instead of the request hanging.
                //
                // Known limitation: this bounds the *request*, not the *client*. The
                // cancelled thread is still parked in readLine() on the process pipe
                // holding McpClient's request lock (pipe reads aren't interruptible),
                // so every later call to this server queues behind the dead read and
                // eats its own timeout — degraded but bounded. The real fix is to treat
                // a timeout as fatal for the client: destroy the process and drop or
                // reconnect its tools (see roadmap).
                .timeout(CALL_TIMEOUT);
    }

    /** Concatenates the {@code text} blocks of an MCP {@code tools/call} result. */
    private String textContent(JsonNode result) {
        StringBuilder out = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                out.append(block.path("text").asText());
            }
        }
        return out.toString();
    }

    private static ObjectNode emptyObjectSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }
}
