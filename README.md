# mana3 — OGBS Java 25

**[English](README.md)** | [简体中文](README.zh-CN.md)

**OGBS = Open Game Backend Specification.**

mana3 is a Java reference implementation of OGBS, providing shared types, networking, RPC, business execution, and data access for game servers. Java 25, no preview features; Maven multi-module project, with public packages under `cn.managame.*`.

| Module | Responsibility |
| --- | --- |
| game-core | Shared Metadata, typed MetadataKey, common framework error codes |
| game-network | TCP / binary WebSocket Connection APIs, native TLS / WSS, and independent HTTP/1.1 HttpServer |
| game-rpc | RpcNode, active/passive peers, fixed slots, handshakes, heartbeats, reconnects, call / notify / reply, and Netty wire codecs |
| game-runtime | Route execution, Context, Handler, HTTP annotations/dispatch, Event, GameTime, cancellable Timer / Cron, cross-Route calls |
| game-data | Single/Group caches, asynchronous write-behind, MySQL/JDBC, MongoDB, append-only MySQL logs |
| [game-example](game-example/README.md) | Runnable Network, Runtime HTTP, and RPC examples and their execution tests |

Dependency direction:

```text
game-core ──────→ game-data
    ├──────────→ game-runtime ←──── game-network
    └──────────→ game-rpc     ←──── game-network
game-network / game-runtime / game-rpc ───→ game-example
```

game-rpc contains RPC core and cn.managame.rpc.netty integration in one Maven module and depends on game-network. RPC does not depend on Runtime or a protocol registry. game-core publishes the shared Caffeine dependency for Runtime/Data cache use, with ownership defined in the [Core Java specification](docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md#1-模块与职责). Runtime depends on game-core and game-network for HTTP annotations and Route integration; it does not depend on RPC, Spring, or business serialization.

Network and RPC each publish one Maven artifact, with responsibility-based subpackages. Start at [game-network](game-network/README.md) and [game-rpc](game-rpc/README.md).

<a id="ogbs-规范文档"></a>

## OGBS specifications

Every framework component requires a standard specification and a Java development specification. All five pairs are listed in the [OGBS 1.0 documentation index](docs/ogbs/README.md).

| Component | Specification (language-independent) | Java Development Specification |
| --- | --- | --- |
| game-core | [OGBS Core Specification](docs/ogbs/OGBS-Core-1.0.md) | [Core Java Development Specification](docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md) |
| game-network | [OGBS Network Specification](docs/ogbs/OGBS-Network-1.0.md) | [Network Java Development Specification](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) |
| game-rpc | [OGBS RPC Specification](docs/ogbs/OGBS-RPC-1.0.md) | [RPC Java Development Specification](docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md) |
| game-runtime | [OGBS Runtime Specification](docs/ogbs/OGBS-Runtime-1.0.md) | [Runtime Java Development Specification](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md) |
| game-data | [OGBS Data Specification](docs/ogbs/OGBS-Data-1.0.md) | [Data Java Development Specification](docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md) |

game-data provides Single/Group caches, asynchronous write-behind, MySQL/JDBC and MongoDB adapters, and append-only MySQL logs. It depends on game-core; game-example will add a game-data dependency when runnable Data examples are implemented. See the [module entry](game-data/README.md), [Data semantics](docs/ogbs/OGBS-Data-1.0.md), and [Data Java Development Specification](docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md). Live database validation status is in the module documentation.

See [OGBS Core](docs/ogbs/OGBS-Core-1.0.md) for shared Metadata and error codes, and the [RPC Wire Profile](docs/rpc-wire.md) for byte layout.

<a id="构建与运行"></a>

## Build and run

Install JDK 25 and ensure mvn -version uses it:

```shell
mvn clean verify
mvn -pl game-network -am test
```

The root build includes game-core, game-network, game-rpc, game-runtime, game-data, and game-example. All standalone runnable examples and their execution tests live in game-example, under `cn.managame.example.<component>`; framework artifacts contain no example classes. Run mvn -pl game-example -am test to validate the examples. RPC→Runtime integration and DataMemoryDemo remain unimplemented.

Run [NetworkEchoExample](game-example/src/main/java/cn/managame/example/network/NetworkEchoExample.java) in an IDE to print hello game-network. It uses a random local port, length framing, and string codecs, and releases network resources afterward.

[HttpServerExample](game-example/src/main/java/cn/managame/example/network/HttpServerExample.java) demonstrates the independent HTTP/1.1 server with application health/echo handlers and a JDK example caller. [HttpAsyncServerExample](game-example/src/main/java/cn/managame/example/network/HttpAsyncServerExample.java) demonstrates asyncHandler and HttpResponseCallback completion on an application-owned executor. The framework API is in cn.managame.network.http; see [the HTTP contract](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md#native-http-server-api).

Network tests cover TCP/TLS/WS/WSS, ordering, reference counts, exceptions, backpressure, handshake failure, and shutdown/interruption races. The current JDK's keytool creates temporary certificates. Windows tests force the JDK Selector wakeup pipe to fall back to TCP and limit Netty's default thread count; production code does not change JVM properties. See Data documentation for live database verification.

<a id="runtime-包结构与业务时间"></a>

## Runtime packages and business time

The [game-runtime module](game-runtime/README.md) separates context, route, executor, protocol, handler, http, event, timer, time, error, and internal responsibilities. Only GameRuntime / GameRuntimeBuilder remain in the root package; update imports to the corresponding subpackages.

```java
import cn.managame.runtime.time.GameTime;

GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
runtime.cron().rescheduleAll(); // Explicitly recompute every Cron entry, including cancelled ones.
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
GameTime.resetClock();
```

Clock/Duration are from java.time; SystemCron is an application class. GameTime changes the process-wide business wall clock and **does not automatically reschedule** existing Timer/Cron tasks. Applications cancel and reschedule dynamic business timers against the new time. Cron uses declaring class + method name as its key; see the [time API](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#10-gametime-timer-and-cron).

<a id="runtime-最小用法"></a>

## Minimal Runtime usage

```java
record Request(long id) {}
record Response(long value) {}

@Handler(domain = 1)
class Requests {
    @HandlerMethod
    public void handle(HandlerContext context, Request request) {
        // Already executing serially within (domain=1, key=request.id).
    }
}

ProtocolProvider protocols = registrar -> {
    registrar.register(Protocols.request(1001, Request.class));
    registrar.register(Protocols.response(1001, Response.class));
    registrar.bindResponse(Request.class, Response.class);
};

try (GameRuntime runtime = GameRuntimeBuilder.builder()
        .routeDomains(List.of(RouteDomain.of(1, "application-defined")))
        .routeExecutors(List.of(
            RouteExecutorBinding.of(RouteExecutors.platformThreads(4), 1)))
        .protocols(List.of(protocols))
        .handlers(List.of(new Requests()))
        .build()) {
    runtime.dispatch(new DefaultHandlerContext(1, 10001, new Request(10001)));
}
```

The integration layer decodes messages and creates Context. Default Context implementations are extensible; the framework does not automatically read `getRoleId/getGuildId`. Use explicit field extraction such as `RouteKeyBinding.of(Request.class, Request::id)`.

<a id="network--rpc-接入"></a>

## Network / RPC integration

Network's public entry points are cn.managame.network.netty.NetworkServer and NetworkClient. ConnectionHandler receives lifecycle, message, event, and exception callbacks. Ordinary send failures go to onException, and applications decide whether to close. The three write statuses describe admission only.

game-network is a thin Netty facade: users extend the pipeline with native handlers and receive messages through ConnectionHandler. It keeps no connection or unfinished-attempt collection; indexing, sessions, reconnect and batch closure belong to upper layers.

See the [Network module](game-network/README.md) and [Network Java Development Specification](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) for complete usage and ownership.

RPC provides RpcNode Builder, owned TCP Server/Client, a timer wheel, multiple slots, active/passive peers, and call/notify/reply. Run [RpcEchoExample](game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) to print hello game-rpc. RpcHandler centrally handles messages, remote errors, and application decoding; automatic RPC→Runtime integration is not implemented.

<a id="约定与当前边界"></a>

## Conventions and current boundaries

- No business RouteDomain is predefined. Each registered Domain must bind exactly one RouteExecutor; multiple Domains may share an executor.
- `build()` validates and makes Runtime immediately usable. Registries are frozen; runtime registration is unsupported.
- `runtime.call()` must run inside that Runtime's Context. Success/failure callbacks return to the original Route and restore its Context. If that Route rejects dispatch, report the error without invoking the callback on another Route.
- Same-Route serialization does not lock an entity across asynchronous work. Cross-Route results should be immutable values, snapshots, or independent DTOs.
- Metadata shares `byte[]` by immutable-use convention. Wire format is `uint16 key + uint16 length + bytes`, with no type field. The Key codec runs only on reads.
- RPC bodies use ByteBuf. Inbound callbacks borrow them; asynchronous use requires retain/copy. Outbound validation transfers one reference; encoding into a contiguous frame releases the body.
- Cron has six numeric fields (second, minute, hour, day, month, weekday), supports `* ? , - /`, uses Sunday=1 and UTC by default. Quartz `L/W/#`, names, and year fields are unsupported.
- RPC uses one HashedWheelTimer per Node for call/handshake timeouts and reconnect delays; connection IdleStateHandler instances handle heartbeats. Call timeout starts after network ACCEPTED.
- Network/RPC callbacks should return quickly; dispatch expensive work to Runtime. RPC core does not automatically decode responses or switch Runtime Routes.
- Core, Network, RPC, Runtime, and Data implementations/tests are available. RPC has real TCP/reconnect tests; automatic Runtime integration remains pending, and production capacity benchmarks are not done. Spring auto-configuration, protocol generation, service discovery, Router, and business codecs are peripheral integrations.

See [Architecture and execution contracts](docs/architecture.md) and the [repository RPC Wire Profile](docs/rpc-wire.md).

<a id="runtime-http"></a>

## HTTP business entry

`cn.managame.runtime.http` supplies `@HttpHandler`, `@HttpMethod`, `HttpRequestMethod`, `HttpContext`, `DefaultHttpContext`, `HttpContextFactory`, `HttpResultCallback`, `HttpResultCodec`, and `HttpDispatcher` in the game-runtime artifact. HttpContext extends the base Context with Route, request, and result callback; it has no business identity/Metadata fields. It depends on game-network and uses the same Domain/RouteKey executors as Handler/Event/call.

Register instances with `httpHandlers(...)`. HttpMethod.method uses HttpRequestMethod and defaults to POST; select GET explicitly with `method=HttpRequestMethod.GET`. Set `routeKey="playerId"` on @HttpHandler/@HttpMethod to select a GET query field or a top-level JSON body field for other methods; method configuration overrides the class rule. Alternatively set routeKeyMethod to a Handler extraction method. An optional four-argument `httpContextFactory(domain, key, request, callback)` preserves the selected Key and may add application-specific HTTP context fields; without a rule, the factory is required to select Key. Connect `HttpServer.builder().asyncHandler(runtime.http()::dispatch)`. A public method returns a business DTO/object or void; deferred completion uses `context.responseCallback().onResponse(dto)`. Runtime encodes JSON by default and creates the transport response internally, without an HTTP version in the business result. `httpResultCodec(...)` customizes result encoding. Paths match raw method/path exactly; no automatic request DTO binding or player-ID inference is provided. The request is borrowed until method return; deferred response completion does not extend its lifetime.

Run [RuntimeHttpExample](game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) for annotated echo and a deferred cross-Route response. See [HTTP semantics](docs/ogbs/OGBS-Runtime-1.0.md#runtime-http-profile) and [Java API, failures, and ownership](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api).
