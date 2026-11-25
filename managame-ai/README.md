English | [中文](README.zh-CN.md)

# managame-ai

A thin Java 25 integration layer between a game server and external AI services built with third-party frameworks such
as Spring AI or LangChain.

Prompts, LLM providers, conversation memory, RAG and agent orchestration stay in the external AI service.
`managame-ai` covers the two thin boundaries every game server needs, with no third-party compile dependencies (only
`managame-common` and `managame-network`) and complete independence from `managame-runtime`, `managame-rpc` and
`managame-data` — so it plugs cleanly into `GameRuntime` or into any other game server framework (actor systems,
custom route executors, or standalone services):

1. **Game server → AI service (single shared `AiClient`)**: one non-blocking HTTP and W3C SSE client shared across all
   business modules in the server process, backed by a single connection pool and a single process-wide daemon timing
   wheel. Once bridged at startup via `AiDispatcher`, business modules call `ai.call(...)` / `ai.stream(...)` directly
   with automatic context validation, ambient `Metadata` header propagation, and ordered callback delivery back onto the
   caller's route — with concurrency admission (`maxInFlight`), full end-to-end timeouts (headers and body alike),
   idle stream timeouts, cancellation, one-way notifications, pluggable `AiCodec` and `Metrics`.
2. **AI service → Game server (`@McpTool` + `McpTools` + `McpHttpHandler`)**: expose existing game server methods as
   standard **MCP (Model Context Protocol)** tools over stateless Streamable HTTP (JSON-RPC 2.0) by adding annotations.
   Methods are compiled into `MethodHandle`s, route-key readers and JSON Schemas once at startup; `McpToolExecutor`
   validates framework metadata in `prepare` and dispatches execution and off-thread async completions onto the game's
   execution model.

| Package | Contents |
| --- | --- |
| `cn.managame.ai` | `AiClient`, `AiRequest`, `AiResult`, `AiCall`, `AiCallback`, `AiStreamEvent`, `AiStreamListener`, `AiCodec` |
| `cn.managame.ai.annotation` | `@McpTool`, `@McpParam` |
| `cn.managame.ai.mcp` | `McpTools`, `McpHttpHandler`, `McpToolDefinition`, `McpToolContext`, `McpToolCall`, `McpToolResult`, `McpToolInterceptor`, `McpToolHandler`, `McpToolProxy` |
| `cn.managame.ai.spi` | `AiDispatcher`, `McpToolExecutor` |
| `cn.managame.ai.internal` | Zero-dependency JSON parser/writer, JSON Schema generator, basic codec, SSE parser, shared daemon timer, compiled `ToolMethod` and meters (not API) |

## Calling an external AI service (single `AiClient`)

Build **one `AiClient`** for the entire game server and wire it once to your server runtime via `AiDispatcher`
(defaults to `AiDispatcher.virtualThreads()` when used standalone):

```java
// Wired once at startup (example for managame-runtime; any actor or route framework plugs in the same way):
AiDispatcher runtimeDispatcher = new AiDispatcher() {
    @Override public Executor capture(Object explicitCtx) {
        if (explicitCtx != null) {
            Context target = (Context) explicitCtx;
            runtime.checkDomain(target.domain());
            return task -> runtime.handOn(target, _ -> task.run());
        }
        Continuation back = runtime.continuation();   // enforces Context.current() & captures caller route
        return task -> back.execute(_ -> task.run());
    }
    @Override public Metadata metadata(Object explicitCtx) {
        if (explicitCtx instanceof Context target) return target.metadata();
        return Context.hasCurrent() ? Context.current().metadata() : Metadata.empty();
    }
};

AiClient ai = AiClient.builder("http://127.0.0.1:8081/api/ai")
        .codec(jsonCodec)                             // e.g. Jackson; defaults to AiCodec.basic()
        .dispatcher(runtimeDispatcher)
        .timeout(Duration.ofSeconds(30))
        .streamIdleTimeout(Duration.ofSeconds(10))    // optional max gap between SSE chunks
        .maxInFlight(1024)
        .header("Authorization", () -> "Bearer " + currentToken())
        .propagate(TRACE_ID, "X-Trace-Id")
        .metrics(metrics)
        .build();

// Every business module injects the single AiClient: X-Trace-Id propagates and callbacks resume on the caller's route
AiCall call = ai.call("/npc/chat", new NpcTalk(roleId, text), NpcReply.class, result -> {
    if (result.isSuccess()) { ... } else { ... }
});

// Per-request timeout override for latency-sensitive endpoints (no need to split multiple AiClient instances)
ai.call(AiRequest.of("/moderation/check", text).withTimeout(Duration.ofMillis(500)),
        ModerationReply.class, result -> ...);

// Safe URL query / path segment encoding; external entries outside a route task can attach .withContext(...)
ai.call(AiRequest.get("/jobs/status").withQuery("jobId", jobId), JobStatus.class, result -> ...);
ai.call(AiRequest.delete("/sessions/" + AiRequest.encodeSegment(sessionId)), Void.class, result -> ...);

// Incremental W3C SSE streaming: events & completion arrive in order on the caller's route; event: error fails cleanly
AiCall stream = ai.stream("/npc/chat/stream", new NpcTalk(roleId, text),
        event -> { ... },
        result -> { ... });
stream.cancel();

// One-way fire-and-forget game event notification (returns network's WriteStatus, isolated budget)
WriteStatus status = ai.notify("/events/boss-killed", new BossKilled(bossId, killerRoleId));

ai.close();
```

- **One client across all gameplay modules**: all modules share one `AiClient`, one connection pool (`maxInFlight = 1024`
  default) and one 10 ms daemon timing wheel. Fast and slow endpoints override deadlines per call via
  `AiRequest.withTimeout(...)`. Response decoding runs off the business route before handing the decoded DTO to
  `AiDispatcher`. Re-fetch entities by id inside callbacks rather than holding references across async calls.
- **Session isolation**: carry a business conversation key in your request DTO (for example
  `"npc:" + serverId + ":" + roleId + ":" + npcId + ":" + dialogueEpoch`), mapped to Spring AI's
  `ChatMemory.CONVERSATION_ID` or LangGraph's `thread_id`. Incrementing `dialogueEpoch` resets the conversation.
- **String, `byte[]`, 64-bit Snowflake IDs and value types**: `String` and `byte[]` bodies/responses bypass `AiCodec`;
  custom codecs can override `encodeBytes` / `decodeBytes` to avoid intermediate `String` copies. `AiCodec.basic()`
  coerces quoted numeric strings into exact 64-bit `long`s and natively supports `UUID`, `URI`, `Duration` and
  `java.time.*` types.
- **Full-body deadlines, admission, bounds and shutdown**: `AiClient` sends `X-Ai-Timeout-Ms` (configurable via
  `.timeoutHeader(...)`) and enforces end-to-end timeouts across both response headers and chunked response bodies;
  calls beyond `maxInFlight` fail with `AI_OVERLOADED`; responses over `maxResponseBytes` (1 MiB default) or SSE events
  over `maxStreamEventChars` (256 KiB default) abort with `AI_PROTOCOL_ERROR`; mid-stream `event: error` frames fail
  with `AI_UNAVAILABLE`; `close()` aborts in-flight calls and rejects subsequent ones with `AI_CLOSED`.

## Exposing game server methods as MCP tools (`@McpTool`)

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

    @McpTool(name = "kick_player", description = "Kick a player offline",
            group = "gm", destructive = true, routeKey = "roleId")
    public void kickPlayer(@McpParam("Player role id") long roleId) { ... }

    @McpTool(name = "query_rank", description = "Query cross-server player rank",
            group = "npc", readOnly = true, routeKey = "roleId")
    public void queryRank(McpToolContext ctx, @McpParam("Player role id") long roleId) {
        worldRpc.queryRank(roleId, rank -> ctx.reply(rank));
    }
}

McpTools tools = McpTools.builder()
        .codec(jsonCodec)
        .executor(gameRuntimeExecutor)                // defaults to McpToolExecutor.virtualThreads()
        .intercept(List.of(authInterceptor, auditInterceptor))
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

- **Supported signatures**: zero arguments, a single DTO record or POJO (unwrapped as top-level `inputSchema`
  properties), or explicit parameters (`-parameters` or `@McpParam(name = ...)`), each optionally preceded by
  `McpToolContext`.
- **Return semantics, hints and lifecycle**: a `void` method with `McpToolContext` answers asynchronously via
  `context.reply` / `context.fail`; a `void` method without it answers `"ok"`; returning `McpToolResult.fail(...)`
  surfaces `isError: true`; returning `null` from a non-`void` method fails with `AI_TOOL_EXECUTION_ERROR`. `@McpTool`
  publishes `readOnlyHint`, `destructiveHint` (`destructive = true` for high-risk actions), `idempotentHint` and
  `openWorldHint: false`. `tools.close()` aborts in-flight async calls with `AI_CLOSED` so HTTP exchanges never hang.
- **Context, interceptors and I/O isolation**: `McpToolContext` carries mutable `Metadata` (`put` / `metadata()`) across
  `before`, the tool, and `after`; `McpToolCall.rawArgumentsJson()` exposes the verbatim JSON arguments slice for audit
  logging or RPC forwarding. When an async tool calls `ctx.reply` from an external I/O thread (such as an RPC
  EventLoop), `McpTools` hands `after` interceptors and result encoding to `McpToolExecutor.complete` so I/O threads
  never run business or codec work.
- **Integrating with `managame-runtime` or other frameworks**: implement `McpToolExecutor` using `prepare(def, method)`
  to validate framework annotations (e.g. `@Domain`) and pre-create `TaskKind`s once at startup, and `execute` /
  `complete` to schedule onto `call.routeKey()`. See `AiConfig` in `managame-example`.
- **Stateless gateway forwarding (`scanDefinitions` / `registerProxy`)**: export schemas via
  `McpTools.scanDefinitions(types)` or validate arguments, extract 64-bit `routeKey`s and enforce authorization at a
  stateless edge node via `McpTools.builder().registerProxy(types, call -> ...)` before forwarding
  `call.rawArgumentsJson()` over RPC.

## Error codes, logs and metrics

- Error codes in `FrameworkErrorCodes` (`5001–5999`): `AI_CLOSED`, `AI_UNAVAILABLE`, `AI_TIMEOUT`, `AI_OVERLOADED`,
  `AI_CANCELLED`, `AI_PROTOCOL_ERROR`, `AI_TOOL_NOT_FOUND`, `AI_TOOL_INVALID_ARGS`, `AI_TOOL_EXECUTION_ERROR`,
  `AI_TOOL_REJECTED`.
- Loggers (`System.Logger`): `cn.managame.ai.client` and `cn.managame.ai.mcp`.
- Client metrics: `managame.ai.client.calls` (`kind`), `managame.ai.client.failures` (`code`),
  `managame.ai.client.in_flight`, `managame.ai.client.notify_in_flight`, `managame.ai.client.stream_events`,
  `managame.ai.client.latency` (`_bucket`, `_nanos`).
- MCP metrics: `managame.ai.mcp.calls` (`tool`), `managame.ai.mcp.failures` (`tool`, framework/execution/timeout
  errors), `managame.ai.mcp.business_failures` (`tool`, application error codes), `managame.ai.mcp.rejected` (`tool`),
  `managame.ai.mcp.latency` (`tool`, `_bucket`, `_nanos`), `managame.ai.mcp.timeouts`, `managame.ai.mcp.not_found`,
  `managame.ai.mcp.invalid_args`, `managame.ai.mcp.overloaded`, `managame.ai.mcp.closed`, `managame.ai.mcp.in_flight`.
  No clock is read under `Metrics.noop()`.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```
