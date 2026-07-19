package io.github.hhagenbuch.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Normalized model response.
 *
 * @param text        concatenated text blocks (may be empty when the model only calls tools)
 * @param toolCalls   tool invocations the model requested this turn
 * @param rawContent  the raw {@code content} array from the API, replayed verbatim as the
 *                    assistant message when continuing the conversation
 * @param stopReason  the API stop reason ({@code end_turn}, {@code tool_use}, ...)
 * @param usage       token counts for this call (never null; {@link TokenUsage#EMPTY} if absent)
 */
public record LlmResponse(String text, List<ToolCall> toolCalls, JsonNode rawContent,
                          String stopReason, TokenUsage usage) {

    /** Convenience for callers/tests that don't supply usage (defaults to {@link TokenUsage#EMPTY}). */
    public LlmResponse(String text, List<ToolCall> toolCalls, JsonNode rawContent, String stopReason) {
        this(text, toolCalls, rawContent, stopReason, TokenUsage.EMPTY);
    }

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
