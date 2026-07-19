package io.github.hhagenbuch.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.llm.LlmResponse;
import io.github.hhagenbuch.agent.llm.ToolCall;
import io.github.hhagenbuch.agent.tools.AgentTool;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProperties props = new AgentProperties("", "test-model", 512, 3, 1, List.of());
    private final ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()));
    private final ConversationMemory memory = new ConversationMemory();

    @Test
    void plainAnswerPassesThrough() {
        LlmClient fake = (messages, tools) -> reactor.core.publisher.Mono.just(
                textResponse("Hello there"));
        AgentLoop loop = new AgentLoop(fake, registry, memory, props, mapper);

        StepVerifier.create(loop.run("s1", "hi"))
                .expectNextMatches(r -> r.answer().equals("Hello there") && r.toolsUsed().isEmpty())
                .verifyComplete();
        assertThat(memory.history("s1")).hasSize(2); // user + assistant
    }

    @Test
    void toolCallRoundTripProducesFinalAnswer() {
        AtomicInteger turn = new AtomicInteger();
        LlmClient fake = (messages, tools) -> reactor.core.publisher.Mono.just(
                turn.getAndIncrement() == 0 ? calculatorCallResponse() : textResponse("The answer is 4"));
        AgentLoop loop = new AgentLoop(fake, registry, memory, props, mapper);

        StepVerifier.create(loop.run("s2", "what is 2+2?"))
                .expectNextMatches(r -> r.answer().equals("The answer is 4")
                        && r.toolsUsed().equals(List.of("calculator")))
                .verifyComplete();
        // user, assistant(tool_use), user(tool_result), assistant(final)
        assertThat(memory.history("s2")).hasSize(4);
    }

    @Test
    void loopTerminatesAtMaxIterations() {
        LlmClient alwaysCallsTools = (messages, tools) -> reactor.core.publisher.Mono.just(
                calculatorCallResponse());
        AgentLoop loop = new AgentLoop(alwaysCallsTools, registry, memory, props, mapper);

        StepVerifier.create(loop.run("s3", "loop forever"))
                .expectNextMatches(r -> r.answer().startsWith("Stopped: exceeded"))
                .verifyComplete();
    }

    @Test
    void streamingEmitsFinalAnswerDeltas() {
        LlmClient fake = new LlmClient() {
            @Override
            public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
                return Mono.just(textResponse("ignored — streamed turn regenerates the answer"));
            }

            @Override
            public Flux<String> chatStream(List<ObjectNode> messages, Collection<AgentTool> tools) {
                return Flux.just("Hel", "lo ", "world");
            }
        };
        AgentLoop loop = new AgentLoop(fake, registry, memory, props, mapper);

        StepVerifier.create(loop.runStreaming("s4", "hi"))
                .expectNext("Hel", "lo ", "world")
                .verifyComplete();
        // streamed turn is remembered: user + assistant(full streamed answer)
        assertThat(memory.history("s4")).hasSize(2);
        assertThat(memory.history("s4").get(1).path("content").asText()).isEqualTo("Hello world");
    }

    @Test
    void errorDoesNotBrickTheSessionForFollowUps() {
        AtomicInteger calls = new AtomicInteger();
        // first call fails mid-turn; later calls succeed
        LlmClient flaky = (messages, tools) -> calls.getAndIncrement() == 0
                ? Mono.error(new RuntimeException("boom"))
                : Mono.just(textResponse("recovered"));
        AgentLoop loop = new AgentLoop(flaky, registry, memory, props, mapper);

        StepVerifier.create(loop.run("s5", "first"))
                .expectNextMatches(r -> r.answer().contains("internal error"))
                .verifyComplete();
        // failed turn is recorded as a clean user -> assistant pair, roles alternate
        assertThat(memory.history("s5")).hasSize(2);
        assertRolesAlternate(memory.history("s5"));

        // a follow-up on the SAME session still works — history isn't corrupted
        StepVerifier.create(loop.run("s5", "second"))
                .expectNextMatches(r -> r.answer().equals("recovered"))
                .verifyComplete();
        assertThat(memory.history("s5")).hasSize(4);
        assertRolesAlternate(memory.history("s5"));
    }

    @Test
    void errorAfterAToolCallLeavesNoDanglingTurn() {
        AtomicInteger calls = new AtomicInteger();
        // turn 0 asks for a tool; after the tool_result is appended, turn 1 fails
        LlmClient fake = (messages, tools) -> switch (calls.getAndIncrement()) {
            case 0 -> Mono.just(calculatorCallResponse());
            case 1 -> Mono.error(new RuntimeException("boom"));
            default -> Mono.just(textResponse("recovered"));
        };
        AgentLoop loop = new AgentLoop(fake, registry, memory, props, mapper);

        StepVerifier.create(loop.run("s6", "what is 2+2?"))
                .expectNextMatches(r -> r.answer().contains("internal error"))
                .verifyComplete();
        // the partial assistant(tool_use)/tool_result turns are rolled back
        assertThat(memory.history("s6")).hasSize(2);
        assertRolesAlternate(memory.history("s6"));
    }

    private void assertRolesAlternate(List<ObjectNode> history) {
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).path("role").asText())
                    .as("roles must alternate at index %d", i)
                    .isNotEqualTo(history.get(i - 1).path("role").asText());
        }
    }

    private LlmResponse textResponse(String text) {
        ArrayNode content = mapper.createArrayNode();
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        return new LlmResponse(text, List.of(), content, "end_turn");
    }

    private LlmResponse calculatorCallResponse() {
        ObjectNode input = mapper.createObjectNode();
        input.put("expression", "2 + 2");
        ArrayNode content = mapper.createArrayNode();
        ObjectNode block = content.addObject();
        block.put("type", "tool_use");
        block.put("id", "tu_1");
        block.put("name", "calculator");
        block.set("input", input);
        return new LlmResponse("", List.of(new ToolCall("tu_1", "calculator", input)), content, "tool_use");
    }
}
