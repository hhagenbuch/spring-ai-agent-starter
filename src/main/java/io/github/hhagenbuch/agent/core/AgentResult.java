package io.github.hhagenbuch.agent.core;

import io.github.hhagenbuch.agent.llm.TokenUsage;

import java.util.List;

/**
 * Outcome of an agent run: the final answer, the trajectory (the names of the tools
 * the loop actually invoked, in call order), and the token usage totalled across every
 * model call in the turn — so callers can see what the turn actually cost.
 *
 * @param answer    the final text answer
 * @param toolsUsed tool names invoked during the run, in order (may repeat)
 * @param usage     tokens consumed across all model calls this turn (never null)
 */
public record AgentResult(String answer, List<String> toolsUsed, TokenUsage usage) {
}
