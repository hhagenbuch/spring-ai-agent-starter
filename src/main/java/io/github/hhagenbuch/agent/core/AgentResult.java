package io.github.hhagenbuch.agent.core;

import java.util.List;

/**
 * Outcome of an agent run: the final answer plus the trajectory — the names of
 * the tools the loop actually invoked, in call order. The trajectory lets evals
 * assert on <em>what the agent did</em>, not just what it said.
 *
 * @param answer    the final text answer
 * @param toolsUsed tool names invoked during the run, in order (may repeat)
 */
public record AgentResult(String answer, List<String> toolsUsed) {
}
