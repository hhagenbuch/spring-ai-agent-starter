package io.github.hhagenbuch.agent.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.tools.AgentTool;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link LlmClient} backed by the Anthropic Messages API, with exponential
 * backoff on retryable failures (429/5xx).
 */
@Component
public class AnthropicClient implements LlmClient {

    private final WebClient webClient;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public AnthropicClient(WebClient anthropicWebClient, AgentProperties props, ObjectMapper mapper) {
        this.webClient = anthropicWebClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public Mono<LlmResponse> chat(List<ObjectNode> messages, Collection<AgentTool> tools) {
        return webClient.post()
                .uri("/v1/messages")
                .bodyValue(buildBody(messages, tools))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parse)
                .retryWhen(Retry.backoff(props.maxRetries(), Duration.ofMillis(500))
                        .filter(AnthropicClient::isRetryable));
    }

    @Override
    public Flux<String> chatStream(List<ObjectNode> messages, Collection<AgentTool> tools) {
        ObjectNode body = buildBody(messages, tools);
        body.put("stream", true);
        return webClient.post()
                .uri("/v1/messages")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(this::textDelta)
                .filter(delta -> !delta.isEmpty());
    }

    private ObjectNode buildBody(List<ObjectNode> messages, Collection<AgentTool> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        ArrayNode msgs = body.putArray("messages");
        messages.forEach(msgs::add);
        if (!tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (AgentTool tool : tools) {
                ObjectNode t = toolArray.addObject();
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("input_schema", tool.inputSchema(mapper));
            }
        }
        return body;
    }

    /** Extracts the text from a {@code content_block_delta} SSE event; null for other events. */
    private String textDelta(ServerSentEvent<String> event) {
        String data = event.data();
        if (data == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(data);
            if ("content_block_delta".equals(node.path("type").asText())
                    && "text_delta".equals(node.path("delta").path("type").asText())) {
                return node.path("delta").path("text").asText();
            }
        } catch (Exception e) {
            // ignore malformed/heartbeat lines
        }
        return null;
    }

    private LlmResponse parse(JsonNode response) {
        JsonNode content = response.path("content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : content) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "tool_use" -> toolCalls.add(new ToolCall(
                        block.path("id").asText(),
                        block.path("name").asText(),
                        block.path("input")));
                default -> { /* ignore unknown block types */ }
            }
        }
        return new LlmResponse(text.toString(), toolCalls, content, response.path("stop_reason").asText(),
                TokenUsage.from(response.path("usage")));
    }

    private static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
        }
        return false;
    }
}
