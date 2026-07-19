package io.github.hhagenbuch.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Token counts from an Anthropic response's {@code usage} object. Exposed so callers
 * (and cost/observability tooling) can see what a turn actually consumed — the numbers
 * are in every API response, and there is no reason to discard them.
 *
 * @param inputTokens              billable input tokens
 * @param outputTokens             generated output tokens
 * @param cacheCreationInputTokens tokens written to the prompt cache (billed at a premium)
 * @param cacheReadInputTokens     tokens read from the prompt cache (billed at a discount)
 */
public record TokenUsage(long inputTokens, long outputTokens,
                         long cacheCreationInputTokens, long cacheReadInputTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0, 0);

    /** Parse the {@code usage} node of a Messages API response (absent fields default to 0). */
    public static TokenUsage from(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return EMPTY;
        }
        return new TokenUsage(
                usage.path("input_tokens").asLong(),
                usage.path("output_tokens").asLong(),
                usage.path("cache_creation_input_tokens").asLong(),
                usage.path("cache_read_input_tokens").asLong());
    }

    /** Sum with another usage — used to total the tokens across a multi-call tool loop. */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheCreationInputTokens + other.cacheCreationInputTokens,
                cacheReadInputTokens + other.cacheReadInputTokens);
    }
}
