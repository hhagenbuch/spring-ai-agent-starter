package io.github.hhagenbuch.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.agent.tools.impl.CalculatorTool;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesBinaryExpressions() {
        assertThat(tool.evaluate("2 + 2")).isEqualTo("4");
        assertThat(tool.evaluate("10 / 4")).isEqualTo("2.5");
        assertThat(tool.evaluate("-3 * 7")).isEqualTo("-21");
    }

    @Test
    void rejectsUnsupportedExpressions() {
        assertThatThrownBy(() -> tool.evaluate("2 + 2 + 2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.evaluate("1 / 0"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void executeReturnsResultReactively() throws Exception {
        var input = mapper.readTree("{\"expression\": \"6 * 7\"}");
        StepVerifier.create(tool.execute(input))
                .expectNext("42")
                .verifyComplete();
    }
}
