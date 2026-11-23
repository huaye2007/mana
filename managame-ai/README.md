English | [中文](README.zh-CN.md)

# managame-ai

Thin, protocol-agnostic game server AI integration component on Java 25.

All AI-specific and fast-moving concerns—LLM provider protocols, MCP, A2A, JSON Schema, prompts, memory, RAG and agent
orchestration—belong to the external AI service (such as a Spring AI or Python agent service). `managame-ai` stays a
thin, stable boundary on the game server side so AI standard churn never forces changes to game server code:

1. **Game server → AI service (`AiClient`)**: stateless, non-blocking asynchronous unary calls, W3C Server-Sent Events
   (SSE) streaming with cancellation, and one-way event notifications, with overload limits, timeouts, interceptors and
   pluggable `AiTransport`.
2. **AI service → game server (`AiTools` + `AiToolHttpHandler`)**: declarative game server tools (`@AiTool`) compiled
   to `MethodHandle`s at startup, with group/readOnly metadata, interceptors (authentication, visibility filtering,
   feature switches, audit), synchronous or asynchronous (`AiToolContext`) replies, pluggable `AiToolExecutor` (to
   route invocations onto game threads), and a thin plain HTTP/JSON handler (`GET /tools`, `POST /tools/{name}`).

Depends only on `managame-common` and `managame-network`, with no third-party compile dependencies and no dependency on
`managame-runtime`, `managame-rpc` or `managame-data`.

| Package | Contents |
| --- | --- |
| `cn.managame.ai` | `AiClient`, `AiRequest`, `AiResult`, `AiCall`, `AiCallback`, `AiStreamListener`, `AiChunk`, `AiClientInterceptor`, `AiTools`, `AiToolDefinition`, `AiToolContext`, `AiToolResult`, `AiToolCall`, `AiToolInterceptor`, `AiJsonCodec`, `AiException` |
| `cn.managame.ai.annotation` | `@AiTool` |
| `cn.managame.ai.http` | `AiToolHttpHandler` (plain HTTP/JSON: `GET /tools`, `POST /tools/{name}`) |
| `cn.managame.ai.spi` | `AiTransport` (outbound transport SPI), `AiToolExecutor` (inbound tool scheduler) |
| `cn.managame.ai.internal` | Internal SSE parser, `MethodHandle` invoker, basic scalar codec and JDK `HttpClient` transport (not public API) |

## Calling an external AI service (`AiClient`)

```java
AiClient ai = AiClient.builder()
        .baseUrl("http://127.0.0.1:8081/api/ai")
        .codec(jsonCodec)                          // e.g. Jackson; defaults to AiJsonCodec.basic()
        .timeout(Duration.ofSeconds(30))
        .maxInFlight(256)
        .header("Authorization", "Bearer " + token)
        .intercept(List.of(traceInterceptor))
        .metrics(metrics, "npc")
        .build();

// Unary call (non-blocking; callback never runs on the calling thread)
AiCall call = ai.call("/npc/chat", new NpcChatReq(npcId, text), NpcReply.class, result -> {
    if (result.isSuccess()) { ... } else { ... }
});

// Streaming call over SSE; cancel anytime (for example when the player disconnects)
AiCall stream = ai.stream("/npc/chat/stream", new NpcChatReq(npcId, text), new AiStreamListener() {
    @Override public void onChunk(AiChunk chunk) { ... }
    @Override public void onComplete() { ... }
    @Override public void onError(int errorCode, String message) { ... }
});
stream.cancel();

// One-way game event notification to the AI service
ai.notify("/events/boss-killed", new BossKilledEvent(bossId, killerRoleId));

ai.close();
```

- **Per-call overrides**: pass `AiRequest.of(path, body).withHeader(...).withTimeout(...)` to attach per-request
  headers or timeouts.
- **Shared `HttpClient`**: call `.httpClient(shared)` when several `AiClient` instances share one connection pool; a
  shared client is not closed by `AiClient.close()`.
- **Overload & close**: calls beyond `maxInFlight` fail with `AI_OVERLOADED`; calls after `close()` fail with
  `AI_CLOSED`, and in-flight calls are cancelled with `AI_CANCELLED`.
- **Custom transport**: supply an `AiTransport` via `.transport(...)` to route over `managame-rpc`, another internal
  protocol, or an in-memory fake.
- **With `managame-runtime`**: capture `Continuation back = runtime.continuation()` inside the game task before calling
  `AiClient`, and deliver the callback or stream chunks through `back.execute(...)` so the player's route is never
  blocked and tool callbacks from the AI service can never deadlock it.

## Exposing game server methods to AI (`AiTools`)

```java
record QueryBag(long roleId, Integer category) {}
record SendMail(long roleId, String title, String content) {}

class PlayerAiTools {
    @AiTool(name = "query_player_bag", description = "Query items in a player's bag", group = "npc", readOnly = true)
    public BagView queryBag(QueryBag req) {
        return bags.snapshot(req.roleId(), req.category());
    }

    @AiTool(name = "online_count", description = "Count currently online players", group = "gm", readOnly = true)
    public int onlineCount() { return sessions.size(); }

    @AiTool(name = "send_mail", description = "Send a system mail to a player", group = "gm")
    public void sendMail(AiToolContext context, SendMail req) {
        mails.send(req, result -> context.reply(result));
    }
}

AiTools tools = AiTools.builder()
        .codec(jsonCodec)
        .executor(toolExecutor)                    // defaults to virtual threads; wire to GameRuntime when used together
        .intercept(List.of(authInterceptor))
        .register(List.of(new PlayerAiTools()))
        .metrics(metrics)
        .build();

NetworkServer server = NetworkServer.start(8082,
        HttpInitializer.http().contextPath("/ai").handler(new AiToolHttpHandler(tools)).build());
```

- **Signatures**: `()`, `(arg)`, `(AiToolContext)` or `(AiToolContext, arg)`. Return any DTO or `AiToolResult`
  (`AiToolResult.ok(data)` / `AiToolResult.fail(code, message)`), or `void` when taking `AiToolContext` to reply
  asynchronously. Returning `null` from a non-void tool is a bug (`AI_TOOL_EXECUTION_ERROR`).
- **Programmatic tools**: `Builder.register(name, description, group, readOnly, handler)` registers config-driven tools
  alongside annotated ones.
- **Interceptors (`AiToolInterceptor`)**: `allowList(headers, tool)` filters tool visibility per caller; `before` /
  `after` enforce authentication, group permissions, read-only rules, feature switches and audit logging.
- **Routing onto `managame-runtime`**: provide an `AiToolExecutor` that reads your routing metadata from
  `call.method()` and `call.argument()` and submits `invocation.run()` to `runtime.execute(gameContext, ...)`, failing
  `call.context()` when the runtime refuses.
- **Transport flexibility**: mount `AiToolHttpHandler` for plain internal HTTP/JSON (`GET /tools`,
  `POST /tools/{name}`), or call `tools.list(...)` / `tools.invoke(...)` directly from an application's `RpcHandler` or
  custom gateway. The external Spring AI service adapts those internal calls to whatever AI standard (MCP, `@Tool`, etc.)
  it exposes to models.

## Error codes

Allocated in `FrameworkErrorCodes` (`5001–5999`): `AI_CLOSED`, `AI_UNAVAILABLE`, `AI_TIMEOUT`, `AI_OVERLOADED`,
`AI_CANCELLED`, `AI_PROTOCOL_ERROR`, `AI_TOOL_NOT_FOUND`, `AI_TOOL_INVALID_ARGS`, `AI_TOOL_EXECUTION_ERROR`,
`AI_TOOL_REJECTED`.

## Logging & Metrics

- Loggers (`System.Logger`): `cn.managame.ai.client`, `cn.managame.ai.tool`, `cn.managame.ai.http`.
- Client metrics (`AiClient.builder().metrics(...)`, optional `client` tag): `managame.ai.client.calls` (`kind`),
  `managame.ai.client.in_flight`, `managame.ai.client.failures` (`code`), `managame.ai.client.stream_chunks`,
  `managame.ai.client.latency` (`_bucket`, `_nanos`).
- Tool metrics (`AiTools.builder().metrics(...)`): `managame.ai.tool.calls` (`tool`), `managame.ai.tool.failures`
  (`tool`), `managame.ai.tool.rejected` (`tool`), `managame.ai.tool.not_found`, `managame.ai.tool.invalid_args`,
  `managame.ai.tool.latency` (`_bucket`, `_nanos`). No clock is read under `Metrics.noop()`.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Tests run over real loopback sockets covering unary calls, incremental SSE streaming, cancellation, timeouts, overload,
interceptors, `AiToolHttpHandler`, and a full bidirectional example in `GameServerAiExampleTest`.
