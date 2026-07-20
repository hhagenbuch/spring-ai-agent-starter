package io.github.hhagenbuch.agent.core;

import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, per-session message history. Deliberately the simplest thing
 * that works; swap for Redis/Postgres behind the same interface for
 * multi-instance deployments.
 */
@Component
public class ConversationMemory {

    private final Map<String, List<ObjectNode>> sessions = new ConcurrentHashMap<>();

    public List<ObjectNode> history(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new CopyOnWriteArrayList<>());
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }
}
