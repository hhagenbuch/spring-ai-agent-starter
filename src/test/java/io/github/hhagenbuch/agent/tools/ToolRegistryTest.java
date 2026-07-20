package io.github.hhagenbuch.agent.tools;

import tools.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()));

    @Test
    void dispatchesToRegisteredTool() throws Exception {
        var input = mapper.readTree("{\"expression\": \"1 + 1\"}");
        StepVerifier.create(registry.execute("calculator", input))
                .expectNext("2")
                .verifyComplete();
    }

    @Test
    void unknownToolBecomesErrorString() {
        StepVerifier.create(registry.execute("nope", mapper.createObjectNode()))
                .expectNextMatches(s -> s.startsWith("ERROR: unknown tool"))
                .verifyComplete();
    }

    @Test
    void toolFailureBecomesErrorString() throws Exception {
        var input = mapper.readTree("{\"expression\": \"bad\"}");
        StepVerifier.create(registry.execute("calculator", input))
                .expectNextMatches(s -> s.startsWith("ERROR: tool 'calculator' failed"))
                .verifyComplete();
    }
}
