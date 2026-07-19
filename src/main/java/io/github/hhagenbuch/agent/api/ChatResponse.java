package io.github.hhagenbuch.agent.api;

import java.util.List;

/**
 * @param sessionId conversation id (echoed so the caller can continue the session)
 * @param reply     the agent's final answer
 * @param toolsUsed tool names the agent invoked this turn, in call order
 */
public record ChatResponse(String sessionId, String reply, List<String> toolsUsed) {
}
