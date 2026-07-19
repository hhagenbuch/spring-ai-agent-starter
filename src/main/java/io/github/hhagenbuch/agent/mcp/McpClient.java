package io.github.hhagenbuch.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal JSON-RPC 2.0 client over an MCP server's stdio transport.
 *
 * <p>Messages are newline-delimited JSON, one object per line, per the MCP
 * stdio spec. All I/O is blocking; callers should invoke this off the reactor
 * event loop (see {@link McpToolAdapter}). A single lock serializes requests so
 * concurrent tool calls don't interleave on the shared pipe.
 */
final class McpClient implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final ObjectMapper mapper;
    private final AtomicInteger ids = new AtomicInteger();
    private final Object lock = new Object();

    McpClient(List<String> command, ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        this.process = new ProcessBuilder(command).start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Performs the MCP handshake and returns the discovered tool definitions. */
    JsonNode handshakeAndListTools() throws IOException {
        ObjectNode init = mapper.createObjectNode();
        init.put("protocolVersion", "2024-11-05");
        init.putObject("capabilities");
        ObjectNode clientInfo = init.putObject("clientInfo");
        clientInfo.put("name", "spring-ai-agent-starter");
        clientInfo.put("version", "0.1.0");
        request("initialize", init);
        notification("notifications/initialized", null);
        return request("tools/list", null).path("tools");
    }

    /** Sends a request and blocks for the response with the matching id. */
    JsonNode request(String method, JsonNode params) throws IOException {
        synchronized (lock) {
            int id = ids.incrementAndGet();
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            if (params != null) {
                req.set("params", params);
            }
            send(req);

            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode node = mapper.readTree(line);
                if (node.has("id") && node.path("id").asInt() == id) {
                    if (node.has("error")) {
                        throw new IOException("MCP error for " + method + ": " + node.get("error"));
                    }
                    return node.path("result");
                }
                // otherwise a notification/log line for another id — skip it
            }
            throw new IOException("MCP server closed the stream before answering " + method);
        }
    }

    private void notification(String method, JsonNode params) throws IOException {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        send(req);
    }

    private void send(JsonNode node) throws IOException {
        stdin.write(mapper.writeValueAsString(node));
        stdin.write("\n");
        stdin.flush();
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // best-effort
        }
        process.destroy();
    }
}
