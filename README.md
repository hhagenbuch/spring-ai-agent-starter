# spring-ai-agent-starter

[![CI](https://github.com/hhagenbuch/spring-ai-agent-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/hhagenbuch/spring-ai-agent-starter/actions)
![Java 25](https://img.shields.io/badge/Java-25-blue)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)

A production-shaped **agentic service in Java**: a bounded, fully reactive
tool-calling loop on Spring WebFlux against the Anthropic Messages API.
No framework magic — the agent loop is ~100 lines you can read, test, and own.

Most agent examples are Python scripts. This is what an agent looks like when
it has to live in an enterprise JVM stack: non-blocking end-to-end, retry with
backoff, bounded iteration, tool failures fed back to the model instead of
crashing the loop, and a unit-tested core with no API key required.

## Architecture

```mermaid
flowchart LR
    U[POST /api/chat] --> C[ChatController]
    C --> L[AgentLoop<br/>bounded reactive loop]
    L -->|messages| A[LlmClient · AnthropicClient<br/>retry / backoff]
    A -->|tool_use| L
    L -->|dispatch| TR[ToolRegistry]
    TR --> T1[AgentTool beans<br/>calculator · clock · …]
    TR --> MCP[McpToolAdapter<br/>mounted MCP servers]
    T1 -->|tool_result| L
    MCP -->|tool_result| L
    L <--> M[(ConversationMemory<br/>per session)]
    L --> R[reply · toolsUsed · usage]
```

- **`AgentLoop`** — the core: model → tool calls → results → model, until
  `end_turn` or `agent.max-tool-iterations`. Independent tool calls in a turn
  run **concurrently** (`flatMap`).
- **`ToolRegistry`** — auto-discovers every `AgentTool` Spring bean; unknown
  tools and tool exceptions become error strings the model can recover from.
- **`LlmClient`** — provider abstraction; `AnthropicClient` adds exponential
  backoff on 429/5xx. The loop is tested against a fake, so `mvn test` needs
  no key and no network.
- **`ConversationMemory`** — in-memory per-session history behind a tiny
  interface; swap for Redis/Postgres for multi-instance deployments. A failed
  turn is rolled back and re-recorded as a clean `user → fallback` pair, so one
  transient error can't leave a dangling turn that breaks the whole session.

> **Known limitation:** history is not serialized per session, so two
> concurrent requests on the *same* `sessionId` can interleave their turns in
> memory. Fine for one caller per session; a shared-session workload needs a
> per-session lock (or the Redis/Postgres swap above).

## Run it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run

curl -s localhost:8080/api/chat \
  -H 'content-type: application/json' \
  -d '{"message": "What is 973 * 481? Use your calculator."}'
```

The model calls the `calculator` tool, gets a grounded result, and answers.
The response is `{"sessionId", "reply", "toolsUsed", "usage"}` — `toolsUsed` is
the trajectory (tool names in call order), and `usage` is the token count
totalled across every model call in the turn (`inputTokens`, `outputTokens`,
`cacheCreationInputTokens`, `cacheReadInputTokens`), so callers can see both
*what the agent did* and *what it cost*. Add a tool by implementing `AgentTool`
and annotating it `@Component` — no other wiring.

For token-by-token output, stream the final answer over SSE. Tool calls are
resolved first on the non-streaming path, then the last model turn streams:

```bash
curl -N localhost:8080/api/chat/stream \
  --data-urlencode "message=What is 973 * 481? Use your calculator." -G
```

Each `delta` event carries a chunk of the answer as it is generated. The
streamed turn is written back to session memory once complete, so a streamed
exchange is remembered exactly like a POSTed one.

> **Tradeoff (MVP):** resolving tools on the non-streaming path first means the
> final answer is generated once to settle the tool loop, discarded, then
> regenerated as a stream — roughly **2× the tokens** on a streamed turn. The
> real fix is to stream from the first call and buffer `tool_use` events until
> the turn's shape is known; see the roadmap.

## Mounting MCP servers

Point the agent at any [MCP](https://modelcontextprotocol.io) server and its
tools join the registry alongside the local ones — the loop can't tell them
apart:

```yaml
agent:
  mcp-servers:
    - name: filesystem
      command: [npx, -y, "@modelcontextprotocol/server-filesystem", /tmp]
```

On startup, `McpConnectionManager` launches each server over stdio, runs the
JSON-RPC handshake (`initialize` → `tools/list`), and wraps every discovered
tool as an `McpToolAdapter`. `tools/call` round-trips run off the event loop on
the bounded-elastic scheduler. A server that fails to start is logged and
skipped, never crashing the agent.

## Design decisions

- **Why recursion for the loop?** Each `step` is a `Mono` continuation —
  no blocking, no threads parked, and the depth bound makes termination
  provable.
- **Why raw content replay?** The assistant's `content` array is stored
  verbatim and replayed, so tool_use/tool_result pairing survives multi-turn
  conversations exactly as the API requires.
- **Why error-strings instead of exceptions for tools?** A failed tool is
  information the model should reason about ("that zone id was invalid"),
  not a 500 for the caller.

## Roadmap

- [x] Bounded reactive tool loop with concurrent tool execution
- [x] Retry/backoff, fallback answers, unit-tested core
- [x] SSE streaming responses (`text/event-stream`)
- [x] MCP client: mount any MCP server's tools as `AgentTool`s
- [ ] Single-pass streaming: stream from the first model call, buffering
      `tool_use` events, to drop the 2× token cost of the resolve-then-stream path
- [ ] Treat an MCP `tools/call` timeout as fatal for that client (kill the
      process, drop/reconnect its tools) instead of leaving the read thread
      parked on the pipe holding the request lock
- [ ] Structured output mode (JSON schema-constrained answers)
- [ ] Eval gate in CI via [agent-evals](https://github.com/hhagenbuch/agent-evals)

## License

MIT
