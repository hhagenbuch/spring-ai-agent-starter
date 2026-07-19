package io.github.hhagenbuch.agent.api;

import io.github.hhagenbuch.agent.core.AgentLoop;
import io.github.hhagenbuch.agent.core.ConversationMemory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentLoop agentLoop;
    private final ConversationMemory memory;

    public ChatController(AgentLoop agentLoop, ConversationMemory memory) {
        this.agentLoop = agentLoop;
        this.memory = memory;
    }

    @PostMapping
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        return agentLoop.run(sessionId, request.message())
                .map(reply -> new ChatResponse(sessionId, reply));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String message,
                                                @RequestParam(required = false) String sessionId) {
        String sid = sessionId != null && !sessionId.isBlank()
                ? sessionId
                : UUID.randomUUID().toString();
        return agentLoop.runStreaming(sid, message)
                .map(delta -> ServerSentEvent.builder(delta).event("delta").build());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> reset(@PathVariable String sessionId) {
        memory.clear(sessionId);
        return ResponseEntity.noContent().build();
    }
}
