English | [中文](README.zh-CN.md)

# managame-ai

A thin Java 25 integration layer between a game server and external AI services built with third-party frameworks such
as Spring AI or LangChain.

Prompts, LLM providers, conversation memory, RAG and agent orchestration stay in the external AI service.
`managame-ai` covers the two thin boundaries every game server needs, with no third-party compile dependencies (only
`managame-common` and `managame-network`) and no dependency on `managame-runtime`, `managame-rpc` or `managame-data`:

1. **Game server → AI service (`AiClient`)**: non-blocking HTTP and W3C SSE client with concurrency admission
   (`maxInFlight`), per-call and full-stream timeouts, cancellation, one-way event notifications, `Metadata` header
   propagation, pluggable `AiCodec` and `Metrics`.
2. **AI service → Game server (`@McpTool` + `McpTools` + `McpHttpHandler`)**: expose existing game server methods as
   standard **MCP (Model Context Protocol)** tools over stateless Streamable HTTP (JSON-RPC 2.0) by adding annotations.
   Methods are compiled to `MethodHandle`s and JSON Schemas once at startup; interceptors filter tool visibility and
   enforce authorization; `McpToolExecutor` dispatches invocations onto the game's execution model (such as
   `GameRuntime`).

| Package | Contents |
| --- | --- |
| `cn.managame.ai` | `AiClient`, `AiRequest`, `AiResult`, `AiCall`, `AiCallback`, `AiStreamEvent`, `AiStreamListener`, `AiCodec` |
| `cn.managame.ai.annotation` | `@McpTool`, `@McpParam` |
| `cn.managame.ai.mcp` | `McpTools`, `McpHttpHandler`, `McpToolDefinition`, `McpToolContext`, `McpToolCall`, `McpToolResult`, `McpToolInterceptor`, `McpToolHandler`, `McpToolProxy` |
| `cn.managame.ai.spi` | `McpToolExecutor` |
| `cn.managame.ai.internal` | Zero-dependency JSON parser/writer, JSON Schema generator, basic codec, SSE parser, compiled `ToolMethod` and meters (not API) |

## Calling an external AI service (`AiClient`)

```java
AiClient ai = AiClient.builder("http://127.0.0.1:8081/api/ai")
        .codec(jsonCodec)                             // e.g. Jackson; defaults to AiCodec.basic()
        .timeout(Duration.ofSeconds(30))
        .streamIdleTimeout(Duration.ofSeconds(10))    // optional max gap between SSE chunks
        .maxInFlight(128)
        .header("Authorization", () -> "Bearer " + currentToken())
        .propagate(TRACE_ID, "X-Trace-Id")
        .metrics(metrics, "npc")
        .build();

// Non-blocking unary call (POST by default; AiRequest.get / delete / withMethod also supported)
AiCall call = ai.call(AiRequest.of("/npc/chat", new NpcTalk(roleId, text), ctx.metadata()),
        NpcReply.class, result -> {
            if (result.isSuccess()) { ... } else { ... }
        });

// Poll an async job or clear a remote session
ai.call(AiRequest.get("/jobs/" + jobId), JobStatus.class, result -> ...);
ai.call(AiRequest.delete("/sessions/" + sessionId), Void.class, result -> ...);

// Incremental W3C SSE streaming, cancellable at any time (e.g. player disconnect)
AiCall stream = ai.stream("/npc/chat/stream", new NpcTalk(roleId, text),
        event -> { ... },
        result -> { ... });
stream.cancel();

// One-way fire-and-forget game event notification (returns network's WriteStatus, isolated budget)
WriteStatus status = ai.notify("/events/boss-killed", new BossKilled(bossId, killerRoleId));

ai.close();
```

- **With `managame-runtime`**: take `Continuation then = runtime.continuation()` inside the game task before calling
  `ai.call` / `ai.stream`, and resume game state inside `then.execute(c -> ...)`. Re-fetch entities by id inside the
  continuation rather than holding them across the call.
- **Session isolation**: carry a business conversation key in your request DTO (for example
  `"npc:" + roleId + ":" + npcId + ":" + dialogueEpoch`), mapped to Spring AI's `ChatMemory.CONVERSATION_ID` or
  LangGraph's `thread_id`. Incrementing `dialogueEpoch` in game data resets the conversation without protocol state.
- **Shared `HttpClient` and bulkhead isolation**: create separate `AiClient` instances per workload (for example
  `npc` with a 30s timeout and `moderation` with a 500ms timeout) and share one `HttpClient` via `.httpClient(shared)`
  so slow streams never starve latency-sensitive calls; a shared client is not shut down by `AiClient.close()`.
- **String and `byte[]` payloads**: passing a `String` or `byte[]` body sends it verbatim without invoking `AiCodec`;
  requesting `String.class` or `byte[].class` returns the raw response body without invoking `AiCodec`. Custom codecs
  (such as Jackson) can override `AiCodec.encodeBytes` / `decodeBytes` so unary HTTP calls avoid intermediate `String`
  allocations. `AiCodec.basic()` also coerces quoted numeric strings into exact 64-bit `long`s so Snowflake IDs above
  `2^53 - 1` never lose precision when quoted by LLMs or callers.
- **Deadline propagation, admission, bounds and shutdown**: `AiClient` sends the request's timeout budget in
  milliseconds as `X-Ai-Timeout-Ms` (configurable or disabled via `.timeoutHeader(...)`) so the downstream AI service
  can bound SSE emitters and cancel stale MCP tool calls after the caller has timed out; calls beyond `maxInFlight` fail
  with `AI_OVERLOADED`; one-way `notify` uses its own `maxNotifyInFlight` budget and returns `WriteStatus` (`ACCEPTED`,
  `INACTIVE`, `NOT_WRITABLE`); unary responses over `maxResponseBytes` (1 MiB default) or SSE events over
  `maxStreamEventChars` (256 KiB default) abort with `AI_PROTOCOL_ERROR`; callbacks run on `.executor(...)` (virtual
  threads by default), never on the caller or `HttpClient`'s I/O threads; `close()` aborts in-flight calls and rejects
  subsequent ones with `AI_CLOSED`.

## Exposing game server methods as MCP tools (`@McpTool`)

Annotate existing service or handler methods to expose them directly as MCP tools:

```java
record SendMail(
        @McpParam("Player role id") long roleId,
        @McpParam("Mail title") String title,
        @McpParam("Mail content") String content) {}

class PlayerTools {
    @McpTool(name = "query_bag", description = "Query a player's bag items",
            group = "npc", readOnly = true, routeKey = "roleId")
    public BagView queryBag(
            @McpParam("Player role id") long roleId,
            @McpParam(value = "Optional category filter", required = false) String category) {
        return bags.snapshot(roleId, category);
    }

    @McpTool(name = "send_mail", description = "Send a system mail to a player",
            group = "gm", idempotent = true, routeKey = "roleId")
    public McpToolResult sendMail(SendMail mail) { ... }

    @McpTool(name = "online_count", description = "Count online players", group = "gm", readOnly = true)
    public int onlineCount() { return sessions.size(); }

    @McpTool(name = "query_rank", description = "Query cross-server player rank",
            group = "npc", readOnly = true, routeKey = "roleId")
    public void queryRank(McpToolContext ctx, @McpParam("Player role id") long roleId) {
        worldRpc.queryRank(roleId, rank -> ctx.reply(rank));
    }
}

McpTools tools = McpTools.builder()
        .codec(jsonCodec)
        .executor(gameRuntimeExecutor)                // defaults to virtual threads
        .intercept(List.of(authInterceptor))
        .timeout(Duration.ofSeconds(10))              // optional safety net for async tools
        .resultAdapter(res -> ...)                    // optional adapter for HttpResult / HandleResult
        .register(List.of(new PlayerTools()))
        .metrics(metrics)
        .build();

NetworkServer mcpServer = NetworkServer.start(8082,
        HttpInitializer.http().contextPath("/mcp")
                .handler(McpHttpHandler.builder(tools).serverInfo("my-game", "1.0.0").build())
                .build());
```

- **Supported signatures**: zero arguments, a single DTO record or POJO (whose components/fields become the top-level
  properties of `inputSchema`), or one or more explicit parameters (compile with `-parameters` or set
  `@McpParam(name = ...)`), each optionally preceded by `McpToolContext`. Avoid placing `@McpTool` directly on
  Protobuf wire handlers — annotate the underlying service method instead so `inputSchema` stays clean.
- **Return semantics**: a `void` method with `McpToolContext` answers asynchronously via `context.reply` /
  `context.fail`; a `void` method without it answers synchronously with `"ok"`; returning `McpToolResult.fail(...)`
  surfaces a tool error (`isError: true`); returning `null` from a non-`void` method is treated as a bug
  (`AI_TOOL_EXECUTION_ERROR`). Use `.resultAdapter(...)` when reusing existing methods returning `HttpResult` or
  `HandleResult`, `.maxInFlight(...)` (256 default) to protect game routes against runaway agents, and `.timeout(...)`
  (closing `McpTools` at shutdown) to guard queued or asynchronous tools — a queued tool that times out before running
  is skipped so side effects are not executed after timeout, while a non-idempotent mutating tool that times out after
  starting tells the model its effect is unknown so it checks state instead of blindly retrying.
- **Safety bounds and idempotency**: `McpHttpHandler.builder(tools).maxResultChars(...)` (256 KiB chars default)
  turns oversized tool outputs into `isError: true` so the model narrows its query instead of blowing up its context.
  Mark retry-safe mutating tools with `@McpTool(idempotent = true)` (`idempotentHint`) and deduplicate side-effectful
  tools in game state by a `requestId` parameter.
- **Interceptors (`McpToolInterceptor`)**: `allowList(headers, tool)` filters which tools appear in `tools/list` per
  caller; `before(call)` authorizes calls (returning `false` rejects with `AI_TOOL_REJECTED`); `after(call, result)`
  runs in reverse order on completion for auditing.
- **With `managame-runtime`**: name the route key in `@McpTool(routeKey = "roleId")` (a parameter or a member of a
  single DTO, compiled into a `MethodHandle` at startup) and supply a `McpToolExecutor` that inspects `call.method()`
  (for example `@Domain`) and `call.routeKey()`, submits a `GameTask` to `runtime.execute`, and fails `call.context()`
  both when `execute` throws and in `GameTask.refused`. See `AiConfig` and `AiRuntimeIntegrationExampleTest` in
  `managame-example` for a complete end-to-end sample.
- **Stateless gateway forwarding (`scanDefinitions` / `registerProxy`)**: in multi-server deployments, a stateless
  gateway node can inspect `@McpTool` schemas via `McpTools.scanDefinitions(types)` or expose one unified `/mcp`
  endpoint via `McpTools.builder().registerProxy(types, call -> ...)` without instantiating backend game services —
  argument decoding, 64-bit `call.routeKey()` extraction and `McpToolInterceptor` checks run at the edge before
  forwarding over `managame-rpc`.
- **MCP protocol**: `McpHttpHandler` implements stateless Streamable HTTP (JSON-RPC 2.0 `initialize`,
  `notifications/initialized`, `ping`, `tools/list`, `tools/call`; configurable `.path("/mcp")` when co-hosted on an
  existing HTTP server, and `MCP-Protocol-Version` header validation) compatible with Spring AI, LangChain4j, LangChain
  and the official MCP SDK (`2024-11-05` through `2025-11-25`). Unknown tools return JSON-RPC `-32602`; invalid
  arguments, rejections and domain failures return `isError: true` so the model can see the error and recover.

## Error codes, logs and metrics

- Error codes live in `FrameworkErrorCodes` (`5001–5999`): `AI_CLOSED`, `AI_UNAVAILABLE`, `AI_TIMEOUT`,
  `AI_OVERLOADED`, `AI_CANCELLED`, `AI_PROTOCOL_ERROR`, `AI_TOOL_NOT_FOUND`, `AI_TOOL_INVALID_ARGS`,
  `AI_TOOL_EXECUTION_ERROR`, `AI_TOOL_REJECTED`.
- Loggers (`System.Logger`): `cn.managame.ai.client` and `cn.managame.ai.mcp`.
- Client metrics (`AiClient.builder().metrics(...)`, optional `client` tag): `managame.ai.client.calls` (`kind`),
  `managame.ai.client.failures` (`code`), `managame.ai.client.in_flight`, `managame.ai.client.notify_in_flight`,
  `managame.ai.client.stream_events`, `managame.ai.client.latency` (`_bucket`, `_nanos`).
- MCP metrics (`McpTools.builder().metrics(...)`, optional `server` tag): `managame.ai.mcp.calls` (`tool`),
  `managame.ai.mcp.failures` (`tool`), `managame.ai.mcp.rejected` (`tool`), `managame.ai.mcp.latency` (`tool`,
  `_bucket`, `_nanos`), `managame.ai.mcp.not_found`, `managame.ai.mcp.invalid_args`, `managame.ai.mcp.overloaded`,
  `managame.ai.mcp.closed`, `managame.ai.mcp.in_flight`. No clock is read under `Metrics.noop()`.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Tests run over real loopback connections, covering `AiClient` (unary, incremental SSE, cancel, timeout, overload,
close), `McpTools`, `McpHttpHandler`, and end-to-end interoperability with the official MCP Java SDK client.
