package io.github.hhagenbuch.agent.core;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.TokenUsage;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        int mark = messages.size();
        messages.add(textMessage("user", userMessage));
        List<String> toolsUsed = new ArrayList<>();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.EMPTY);
        return step(messages, 0, toolsUsed, usage)
                .map(answer -> new AgentResult(answer, List.copyOf(toolsUsed), usage.get()))
                .onErrorResume(e -> Mono.just(new AgentResult(
                        recordFailure(messages, mark, userMessage, e), List.copyOf(toolsUsed), usage.get())));
    }

    /**
     * Streaming variant: resolve any tool calls with the non-streaming path,
     * then stream only the final answer turn as a {@code Flux} of text deltas.
     * Simplest scope that still exercises real SSE decoding.
     *
     * <p>The deltas are accumulated and the full answer is appended to
     * {@link ConversationMemory} once the stream completes, so a streamed turn
     * is remembered exactly like a non-streamed one — the next request sees a
     * well-formed transcript, not a dangling user/tool_result turn. On error the
     * turn is recorded as a clean {@code user → assistant(fallback)} pair (see
     * {@link #recordFailure}), so a failed stream can't poison the session either.
     */
    public Flux<String> runStreaming(String sessionId, String userMessage) {
        List<ObjectNode> messages = memory.history(sessionId);
        int mark = messages.size();
        messages.add(textMessage("user", userMessage));
        StringBuilder answer = new StringBuilder();
        return resolveTools(messages, 0)
                .flatMapMany(ready -> llm.chatStream(ready, registry.all()))
                .doOnNext(answer::append)
                .doOnComplete(() -> messages.add(textMessage("assistant", answer.toString())))
                .onErrorResume(e -> Flux.just(recordFailure(messages, mark, userMessage, e)));
    }

    /**
     * Records a failed turn as a clean {@code user → assistant(fallback)} exchange.
     * We roll the history back to where this call started (dropping the user turn
     * and any partial tool_use/tool_result turns) and re-append the question with
     * the fallback the caller actually saw. This keeps memory strictly alternating
     * — without it, a single transient failure leaves a dangling user/tool_result
     * turn and every later request on the session is rejected by the API.
     */
    private String recordFailure(List<ObjectNode> messages, int mark, String userMessage, Throwable e) {
        String text = "I hit an internal error and could not complete that request: " + e.getMessage();
        rollbackTo(messages, mark);
        messages.add(textMessage("user", userMessage));
        messages.add(textMessage("assistant", text));
        return text;
    }

    private void rollbackTo(List<ObjectNode> messages, int mark) {
        while (messages.size() > mark) {
            messages.remove(messages.size() - 1);
        }
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

    private Mono<String> step(List<ObjectNode> messages, int depth, List<String> toolsUsed,
                              AtomicReference<TokenUsage> usage) {
        if (depth >= props.maxToolIterations()) {
            return Mono.just("Stopped: exceeded the maximum of "
                    + props.maxToolIterations() + " tool iterations.");
        }
        return llm.chat(messages, registry.all())
                .flatMap(response -> {
                    usage.updateAndGet(total -> total.plus(response.usage())); // count every model call
                    messages.add(assistantMessage(response));
                    if (!response.wantsTools()) {
                        return Mono.just(response.text());
                    }
                    return executeTools(response.toolCalls(), toolsUsed)
                            .map(results -> {
                                messages.add(results);
                                return results;
                            })
                            .then(Mono.defer(() -> step(messages, depth + 1, toolsUsed, usage)));
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
