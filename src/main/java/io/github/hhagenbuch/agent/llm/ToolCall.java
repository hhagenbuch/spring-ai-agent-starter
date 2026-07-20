package io.github.hhagenbuch.agent.llm;

import tools.jackson.databind.JsonNode;

/** A single tool invocation requested by the model. */
public record ToolCall(String id, String name, JsonNode input) {
}
