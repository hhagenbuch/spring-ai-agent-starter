package io.github.hhagenbuch.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * The agentic core: model → (tool calls → results → model)* → answer.
 *
 * <p>Fully non-blocking. Independent tool calls within a turn run
 * concurrently via {@code flatMap}; the loop is bounded by
 * {@code agent.max-tool-iterations} to guarantee termination.
 */
@Component
public class AgentLoop {

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ConversationMemory memory;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public AgentLoop(LlmClient llm, ToolRegistry registry, ConversationMemory memory,
                     AgentProperties props, ObjectMapper mapper) {
        this.llm = llm;
        this.registry = registry;
        this.memory = memory;
        this.props = props;
        this.mapper = mapper;
    }

    public Mono<AgentResult> run(String sessionId, String userMessage) {
        List<ObjectNode> messages = memory.history(sessionId);
        messages.add(textMessage("user", userMessage));
        List<String> toolsUsed = new ArrayList<>();
        return step(messages, 0, toolsUsed)
                .map(answer -> new AgentResult(answer, List.copyOf(toolsUsed)))
                .onErrorResume(e -> Mono.just(new AgentResult(
                        "I hit an internal error and could not complete that request: " + e.getMessage(),
                        List.copyOf(toolsUsed))));
    }

    /**
     * Streaming variant: resolve any tool calls with the non-streaming path,
     * then stream only the final answer turn as a {@code Flux} of text deltas.
     * Simplest scope that still exercises real SSE decoding.
     *
     * <p>The deltas are accumulated and the full answer is appended to
     * {@link ConversationMemory} once the stream completes, so a streamed turn
     * is remembered exactly like a non-streamed one — the next request sees a
     * well-formed transcript, not a dangling user/tool_result turn. Nothing is
     * persisted if the stream errors, so a failed turn never poisons memory.
     */
    public Flux<String> runStreaming(String sessionId, String userMessage) {
        List<ObjectNode> messages = memory.history(sessionId);
        messages.add(textMessage("user", userMessage));
        StringBuilder answer = new StringBuilder();
        return resolveTools(messages, 0)
                .flatMapMany(ready -> llm.chatStream(ready, registry.all()))
                .doOnNext(answer::append)
                .doOnComplete(() -> messages.add(textMessage("assistant", answer.toString())))
                .onErrorResume(e -> Flux.just(
                        "I hit an internal error and could not complete that request: " + e.getMessage()));
    }

    /**
     * Drives the model → tools loop until the model stops requesting tools,
     * returning the message list ready for the final answer turn. The final
     * (non-tool) response is intentionally not appended, so the streaming call
     * regenerates that answer from the same context.
     */
    private Mono<List<ObjectNode>> resolveTools(List<ObjectNode> messages, int depth) {
        if (depth >= props.maxToolIterations()) {
            return Mono.just(messages);
        }
        return llm.chat(messages, registry.all())
                .flatMap(response -> {
                    if (!response.wantsTools()) {
                        return Mono.just(messages);
                    }
                    messages.add(assistantMessage(response));
                    return executeTools(response.toolCalls(), new ArrayList<>())
                            .doOnNext(messages::add)
                            .then(Mono.defer(() -> resolveTools(messages, depth + 1)));
                });
    }

    private Mono<String> step(List<ObjectNode> messages, int depth, List<String> toolsUsed) {
        if (depth >= props.maxToolIterations()) {
            return Mono.just("Stopped: exceeded the maximum of "
                    + props.maxToolIterations() + " tool iterations.");
        }
        return llm.chat(messages, registry.all())
                .flatMap(response -> {
                    messages.add(assistantMessage(response));
                    if (!response.wantsTools()) {
                        return Mono.just(response.text());
                    }
                    return executeTools(response.toolCalls(), toolsUsed)
                            .map(results -> {
                                messages.add(results);
                                return results;
                            })
                            .then(Mono.defer(() -> step(messages, depth + 1, toolsUsed)));
                });
    }

    /** Runs all requested tool calls concurrently, preserving call order in the result message. */
    private Mono<ObjectNode> executeTools(List<ToolCall> calls, List<String> toolsUsed) {
        calls.forEach(call -> toolsUsed.add(call.name()));
        return Flux.fromIterable(calls)
                .flatMap(call -> registry.execute(call.name(), call.input())
                        .map(result -> toolResultBlock(call.id(), result)))
                .collectList()
                .map(blocks -> {
                    ObjectNode message = mapper.createObjectNode();
                    message.put("role", "user");
                    ArrayNode content = message.putArray("content");
                    blocks.forEach(content::add);
                    return message;
                });
    }

    private ObjectNode textMessage(String role, String text) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", role);
        message.put("content", text);
        return message;
    }

    private ObjectNode assistantMessage(LlmResponse response) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.set("content", response.rawContent());
        return message;
    }

    private ObjectNode toolResultBlock(String toolUseId, String result) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", result);
        return block;
    }
}
