package io.github.hhagenbuch.agent.llm;

import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.tools.AgentTool;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;

/**
 * Abstraction over the underlying LLM API so the agent loop can be tested
 * with a fake and the provider can be swapped.
 */
public interface LlmClient {

    Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools);

    /**
     * Streams the text deltas of a single model call. Providers that support
     * server-sent streaming override this; the default keeps {@code LlmClient}
     * a functional interface so lambda fakes only need {@link #chat}.
     */
    default Flux<String> chatStream(List<ObjectNode> messages, Collection<AgentTool> tools) {
        return chat(messages, tools).flatMapMany(response -> Flux.just(response.text()));
    }
}
