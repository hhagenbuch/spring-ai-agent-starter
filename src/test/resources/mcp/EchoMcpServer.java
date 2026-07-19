import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trivial stdio MCP server for tests. Newline-delimited JSON-RPC 2.0: answers
 * {@code initialize}, {@code tools/list} (one "echo" tool), and {@code tools/call}
 * (returns {@code "echo: <text>"}). Hand-rolled, JDK-only, so it runs via
 * {@code java EchoMcpServer.java} with no classpath — see McpEchoIntegrationTest.
 */
public class EchoMcpServer {

    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern TEXT_ARG = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String method = group(METHOD, line);
            String id = group(ID, line);
            if (method == null) {
                continue;
            }
            switch (method) {
                case "initialize" -> reply(id, "{\"protocolVersion\":\"2024-11-05\","
                        + "\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"echo\",\"version\":\"0.0.1\"}}");
                case "notifications/initialized" -> { /* notification: no response */ }
                case "tools/list" -> reply(id, "{\"tools\":[{"
                        + "\"name\":\"echo\","
                        + "\"description\":\"Echoes the text argument back.\","
                        + "\"inputSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"text\":{\"type\":\"string\"}},"
                        + "\"required\":[\"text\"]}}]}");
                case "tools/call" -> {
                    String text = group(TEXT_ARG, line);
                    reply(id, "{\"content\":[{\"type\":\"text\",\"text\":\"echo: "
                            + (text == null ? "" : text) + "\"}],\"isError\":false}");
                }
                default -> { /* ignore unknown methods */ }
            }
        }
    }

    private static void reply(String id, String result) {
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
        System.out.flush();
    }

    private static String group(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
