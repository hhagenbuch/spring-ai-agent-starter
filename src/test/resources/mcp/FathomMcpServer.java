import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stdio MCP server stub that presents fathom's real tool contract, for the
 * "starter mounts fathom" integration test. Newline-delimited JSON-RPC 2.0:
 * answers {@code initialize}, {@code tools/list} (fathom's six core tools:
 * search, entity, blast_radius, impacted_by, contract_surface, verify), and
 * {@code tools/call} with representative fathom text results.
 *
 * <p>Hand-rolled and JDK-only (no package, no classpath) so it runs via
 * {@code java FathomMcpServer.java} — the same shape as {@code EchoMcpServer}.
 * This proves the mount works against fathom's tool <em>surface</em> without a
 * cross-repo dependency on the fathom jar (which would need building/publishing
 * before this repo's CI could run). Swap this command for the real
 * {@code java -jar fathom.jar serve --config fathom.yaml} to mount live fathom.
 */
public class FathomMcpServer {

    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

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
                        + "\"serverInfo\":{\"name\":\"fathom\",\"version\":\"0.2.0\"}}");
                case "notifications/initialized" -> { /* notification: no response */ }
                case "tools/list" -> reply(id, toolsList());
                case "tools/call" -> reply(id, callResult(NAME_IN_PARAMS(line)));
                default -> { /* ignore unknown methods */ }
            }
        }
    }

    /** The tool name inside params.name (the second "name" after the method call name). */
    private static String NAME_IN_PARAMS(String line) {
        // params look like: "params":{"name":"impacted_by","arguments":{...}}
        Matcher m = NAME.matcher(line);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static String toolsList() {
        String s = obj("search", "Search the indexed corpus by keyword. Ranking is hybrid ... no semantic embeddings.")
                + "," + obj("entity", "Fetch one entity by id, with its type and attributes.")
                + "," + obj("blast_radius", "What breaks if this changes: the reverse transitive closure of dependents.")
                + "," + obj("impacted_by", "Cross-repo consumers of an entity, grouped by repo, tagged with surface-crossing.")
                + "," + obj("contract_surface", "List entities marked as contract surface, with kind and reason.")
                + "," + obj("verify", "Re-read an entity's source and confirm the index still matches it.");
        return "{\"tools\":[" + s + "]}";
    }

    private static String obj(String name, String desc) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + desc + "\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}";
    }

    private static String callResult(String tool) {
        String text = switch (tool == null ? "" : tool) {
            case "impacted_by" -> "1 cross-repo consumer of Symbol:PaymentApi [contract surface: api-type]:\\n"
                    + "repo checkout-svc:\\n  \\u2022 Symbol:CheckoutService  via references  (surface-crossing)";
            case "contract_surface" -> "1 contract-surface entit(ies):\\n"
                    + "\\u2022 Symbol:PaymentApi  [api-type]  Published API/client type.";
            case "verify" -> "File:sample/src/Money.java\\nverified: false  [STALE]\\n"
                    + "live source differs from the index \\u2192 run reindex.";
            case "blast_radius" -> "2 entit(ies) affected if Symbol:Money changes:\\n"
                    + "\\u2022 Symbol:Invoice  [Symbol]  depth 1\\n\\u2022 Symbol:Ledger  [Symbol]  depth 2";
            case "entity" -> "Symbol:Money [Symbol] attrs={repo: sample-code}";
            default -> "1 match for the query.";
        };
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"isError\":false}";
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
