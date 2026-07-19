package io.github.hhagenbuch.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Wraps a single tool exposed by an MCP server as an {@link AgentTool}, so the
 * {@code ToolRegistry} dispatches it exactly like a local tool. The blocking
 * {@code tools/call} round-trip runs on the bounded-elastic scheduler to keep
 * the reactor event loop free.
 */
final class McpToolAdapter implements AgentTool {

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
                .subscribeOn(Schedulers.boundedElastic());
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
