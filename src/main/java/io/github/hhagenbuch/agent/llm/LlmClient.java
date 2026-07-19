package io.github.hhagenbuch.agent.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;

/**
 * Abstraction over the underlying LLM API so the agent loop can be tested
 * with a fake and the provider can be swapped.
 */
public interface LlmClient {

    Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools);
}
