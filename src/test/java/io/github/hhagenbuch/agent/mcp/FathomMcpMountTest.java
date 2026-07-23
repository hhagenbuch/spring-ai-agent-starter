package io.github.hhagenbuch.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.hhagenbuch.agent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Starter mounts fathom over MCP": drives a real stdio subprocess presenting
 * fathom's tool contract ({@code FathomMcpServer.java}) end to end ... handshake,
 * discovery of all six fathom tools, and {@code tools/call} through the
 * {@link McpToolAdapter} / {@link ToolRegistry}. Uses a JDK-only stub (not the
 * fathom jar) so CI needs no cross-repo build; the mount path is identical for
 * the real {@code java -jar fathom.jar serve} command.
 */
class FathomMcpMountTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> fathomServerCommand() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path server = Path.of(getClass().getClassLoader().getResource("mcp/FathomMcpServer.java").toURI());
        return List.of(javaBin, server.toString());
    }

    private JsonNode toolNamed(JsonNode tools, String name) {
        for (JsonNode t : tools) {
            if (t.path("name").asText().equals(name)) {
                return t;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    @Test
    void mountsAllSixFathomToolsOverStdio() throws Exception {
        try (McpClient client = new McpClient(fathomServerCommand(), mapper)) {
            JsonNode tools = client.handshakeAndListTools();

            List<String> names = new ArrayList<>();
            tools.forEach(t -> names.add(t.path("name").asText()));
            assertThat(names).containsExactlyInAnyOrder(
                    "search", "entity", "blast_radius", "impacted_by", "contract_surface", "verify");
        }
    }

    @Test
    void impactedByReturnsCrossRepoSurfaceCrossingThroughTheAdapter() throws Exception {
        try (McpClient client = new McpClient(fathomServerCommand(), mapper)) {
            JsonNode tools = client.handshakeAndListTools();
            McpToolAdapter impactedBy = new McpToolAdapter(client, mapper, toolNamed(tools, "impacted_by"));

            StepVerifier.create(impactedBy.execute(mapper.readTree("{\"id\":\"Symbol:PaymentApi\"}")))
                    .assertNext(text -> assertThat(text)
                            .contains("checkout-svc")
                            .contains("surface-crossing"))
                    .verifyComplete();
        }
    }

    @Test
    void verifyToolSurfacesStalenessThroughTheRegistry() throws Exception {
        try (McpClient client = new McpClient(fathomServerCommand(), mapper)) {
            JsonNode tools = client.handshakeAndListTools();
            ToolRegistry registry = new ToolRegistry(List.of());
            registry.register(new McpToolAdapter(client, mapper, toolNamed(tools, "verify")));

            // the honesty property: a stale index is admitted, not papered over
            StepVerifier.create(registry.execute("verify", mapper.readTree("{\"id\":\"File:sample/src/Money.java\"}")))
                    .assertNext(text -> assertThat(text)
                            .contains("verified: false")
                            .contains("STALE"))
                    .verifyComplete();
        }
    }
}
