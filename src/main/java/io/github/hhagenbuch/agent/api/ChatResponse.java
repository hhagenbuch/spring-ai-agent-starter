package io.github.hhagenbuch.agent.api;

import io.github.hhagenbuch.agent.llm.TokenUsage;

import java.util.List;

/**
 * @param sessionId conversation id (echoed so the caller can continue the session)
 * @param reply     the agent's final answer
 * @param toolsUsed tool names the agent invoked this turn, in call order
 * @param usage     tokens consumed across the turn — so callers know what it cost
 */
public record ChatResponse(String sessionId, String reply, List<String> toolsUsed, TokenUsage usage) {
}
