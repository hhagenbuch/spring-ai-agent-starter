package io.github.hhagenbuch.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real stdio MCP subprocess ({@code EchoMcpServer.java}) end to end:
 * handshake, tool discovery, and a {@code tools/call} through the
 * {@link McpToolAdapter} and {@link ToolRegistry}.
 */
class McpEchoIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> echoServerCommand() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path server = Path.of(getClass().getClassLoader().getResource("mcp/EchoMcpServer.java").toURI());
        return List.of(javaBin, server.toString());
    }

    @Test
    void discoversAndInvokesToolsOverStdio() throws Exception {
        try (McpClient client = new McpClient(echoServerCommand(), mapper)) {
            JsonNode tools = client.handshakeAndListTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).path("name").asText()).isEqualTo("echo");

            McpToolAdapter adapter = new McpToolAdapter(client, mapper, tools.get(0));
            assertThat(adapter.name()).isEqualTo("echo");
            assertThat(adapter.description()).isNotBlank();
            assertThat(adapter.inputSchema(mapper).path("type").asText()).isEqualTo("object");

            StepVerifier.create(adapter.execute(mapper.readTree("{\"text\": \"ping\"}")))
                    .expectNext("echo: ping")
                    .verifyComplete();
        }
    }

    @Test
    void registeredMcpToolDispatchesLikeALocalTool() throws Exception {
        try (McpClient client = new McpClient(echoServerCommand(), mapper)) {
            JsonNode tools = client.handshakeAndListTools();
            ToolRegistry registry = new ToolRegistry(List.of());
            registry.register(new McpToolAdapter(client, mapper, tools.get(0)));

            assertThat(registry.all()).hasSize(1);
            assertThat(registry.all().iterator().next().name()).isEqualTo("echo");

            StepVerifier.create(registry.execute("echo", mapper.readTree("{\"text\": \"hi\"}")))
                    .expectNext("echo: hi")
                    .verifyComplete();
        }
    }
}
