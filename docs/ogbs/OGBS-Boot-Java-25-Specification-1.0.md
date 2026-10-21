# OGBS Boot Java 25 Development Specification 1.0

**[English](OGBS-Boot-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md)

Companion: [server composition semantics](OGBS-Boot-1.0.md). Implemented artifact `cn.managame:game-boot:1.0.0-SNAPSHOT`, Java 25, depending on game-runtime and game-data, with optional game-rpc; Netty arrives transitively through game-runtime. No container dependency of any kind, which is what makes [B-COMPOSE-01](OGBS-Boot-1.0.md#1-what-this-tier-is) checkable: a container type on the classpath here would be a defect.

## 1. Module layout

| Package | Holds | Needs |
| --- | --- | --- |
| `cn.managame.boot.rpc` | `GameRpc`, `GameRpcCodec`, `GameRpcConfigurer` | game-rpc (optional dependency; declare it explicitly to use this package) |
| `cn.managame.boot.metrics` | `ObservabilityEndpoints`, `ReadinessCheck`, `FrameworkMetrics` | game-core readings, game-runtime HTTP, game-data and game-rpc for their adapters |
| `cn.managame.boot.lifecycle` | `ShutdownBudget` | nothing beyond the JDK |
| `cn.managame.boot.http` | `HttpContextPath` | game-runtime HTTP, game-network |

An application depends on game-boot directly, or inherits it from a container integration such as game-spring. Every type listed above is public and constructible; none reads configuration, so property names belong to whichever integration supplies the values.

<a id="2-rpc-adapter"></a>

## 2. RPC adapter

`new GameRpc(GameRuntime, GameRpcCodec, RpcNodeBuilder, List<GameRpcConfigurer>)` is the join. It applies every configurer hook itself, in list order, per [B-COMPOSE-03](OGBS-Boot-1.0.md#1-what-this-tier-is): `configure` on the builder, `decorate` around its own `RpcHandler`, `build`, then `attach`. The caller supplies a builder that already carries node id, bind address and any call timeout, and must not set a handler on it — the adapter installs its own last. `GameRpc` is `AutoCloseable`; `close()` detaches in reverse order then closes the Node, idempotently. `node()` exposes the managed `RpcNode` for topology management (`addPeer`), `localAddress()` and `start()`.

A failing `attach` detaches the configurers that already attached, in reverse, adds any detach failure as a suppressed exception, closes the unstarted Node and rethrows. A `decorate` returning null fails with `NullPointerException` naming the hook. Do not send raw `call`/`notify`/`reply` through `node()`: callback correlation is private to this adapter. An application that wants the raw API uses a separate `RpcNode`.

| Member | Contract |
| --- | --- |
| `<T> void call(targetNodeId, routeKey, businessIdType, businessId, metadata, request, RouteCallback<T>)` | Requires this Runtime's current Route and a REQUEST-to-response binding; `T` must match that binding. Registers `runtime.callback` and restores the exact source Context/Route for success and failure, including immediate missing-Peer rejection. Remote error codes and local RPC failures reach `onFail`; a response that cannot be decoded becomes `PROTOCOL_ERROR` |
| `RpcSendStatus notify(targetNodeId, routeKey, businessIdType, businessId, metadata, request)` | Needs no current Route and cannot be replied to |
| `RpcSendStatus reply(Object response)` | Uses `Contexts.current(RpcHandlerContext.class)` |
| `RpcSendStatus reply(RpcHandlerContext, Object response)` | A saved immutable envelope permits a delayed reply without retaining a transport body |
| `RpcSendStatus replyError(RpcHandlerContext, int positiveCode)` | Empty error response; a non-positive code is `IllegalArgumentException` |

Every reply must match the original request's registered response class and requires a nonzero `requestId`; a Notify's zero `requestId` is `IllegalArgumentException`. Replies carry empty response Metadata and preserve source-Slot preference and replacement-connection fallback. `ACCEPTED` is transport acceptance, not remote completion.

Ownership at the seam. Serialization or binding failures before callback registration only throw, and nothing is reserved. When `RpcNode` throws synchronously **after** registration, the adapter releases the reservation with `CapturedRouteCallback.cancel()` and rethrows, so the caller is told once and no continuation is left reserved. Response Metadata is not exposed by this object-only callback; an application that needs the transport envelope uses a raw `RpcNode`.

Inbound. Command lookup uses `ProtocolDescriptor.Type.REQUEST` for both Call and Notify; a Notify is `requestId` zero, not a separate registration. Decoded requests pass source Node/Slot, command, `requestId`, Route Key, business identity and the exact received Metadata to `runtime.dispatchRpc`, which selects the Domain from the Handler declaration. A `RuntimeDispatchException` from `dispatchRpc` (admission closed, overload, invalid Domain/Key, no Handler) is answered for a Call at once through `RpcNode.reply` with its `errorCode()`, an empty body and empty Metadata, and the Handler does not run (B-RPC-04); for a Notify it is rethrown and `RpcNode` logs it. Unknown commands, codec exceptions and argument-signature binding failures keep `RpcHandler`'s `HANDLER_ERROR` policy for Call and logging-only policy for Notify. Verified by `aCallRuntimeRefusedIsAnsweredWithTheRefusalNotAsAHandlerError` in [BootRpcCompositionTest](../../game-boot/src/test/java/cn/managame/boot/rpc/BootRpcCompositionTest.java).

`GameRpcCodec` exposes `byte[] encode(Object)` and `<T> T decode(byte[], Class<T>)`, plus default methods `ByteBuf encode(Object, ByteBufAllocator)` and `<T> T decode(ByteBuf, Class<T>)`. The adapter calls only the ByteBuf forms, whose defaults wrap or copy and delegate to the array forms; override them to encode straight into a pooled buffer and decode from the received frame without the intermediate copies. A body passed to the ByteBuf decode is **borrowed** and the result must not reference it. A codec must support concurrent transport and Route callers, return nonnull values of the exact registered type, and return arrays and objects independent of transport-buffer lifetime. Outbound arrays become adapter-owned on return and must not be modified or reused. Decoding happens synchronously before admission, so slow decoding delays the transport EventLoop. Business serialization is application policy; the framework defines no format.

`GameRpcConfigurer` is a `@FunctionalInterface` whose single abstract method is `configure(RpcNodeBuilder)`; `decorate`, `attach` and `detach` default to no-ops. `decorate` is how a `GameRouter` or `ServiceRouting` receives the managed handler as its direct handler — the last configurer in the list is outermost. Routed requests then reach the routing handler rather than `runtime.dispatchRpc`, and decoding them, dispatching and replying exactly (`ServiceRouting.reply` with the saved `RoutedRequest`) stay application code.

```java
var runtime = GameRuntimeBuilder.builder()./* domains, executors, protocols, handlers */build();
var builder = RpcNode.builder().nodeId(1).bindAddress(new InetSocketAddress("127.0.0.1", 9100));
try (runtime; var rpc = new GameRpc(runtime, codec, builder, List.of(routingConfigurer))) {
    rpc.node().start();
    rpc.node().addPeer(2, peerAddress, 1);
    // Handlers reach rpc through their own field, a provider, or whatever the application already uses.
}
```

## 3. Shutdown budget

Implements [B-CLOSE-01 and B-CLOSE-02](OGBS-Boot-1.0.md#3-shutdown-budget). `ShutdownBudget` is a record of the three configured phases, `(Duration total, Duration lead, Duration flushReserve)`, and `drain()` is an accessor computed from them. It is not a fourth component: a record's canonical constructor is public whether or not the author wants it to be, so a stored drain could be passed inconsistently and the one guarantee this type exists for would be gone. Computing it makes that unconstructible. `ShutdownBudget.of(totalMillis, leadMillis, flushReserveMillis)` is the millisecond form an integration reading three configuration values uses.

| Phase | Constant | Default | Meaning |
| --- | --- | --- | --- |
| whole budget | `DEFAULT_TOTAL_MILLIS` | 25 000 | keep the orchestrator's grace period above it |
| lead | `DEFAULT_LEAD_MILLIS` | 0 | readiness reports failure while admission stays open |
| flush reserve | `DEFAULT_FLUSH_RESERVE_MILLIS` | 10 000 | reserved for Data's final persistence pass |
| drain | *derived* | 15 000 | `total − lead − flushReserve`, for admitted work |

25 000 is chosen below the 30 seconds a Kubernetes grace period defaults to; `docker stop` allows 10, so a deployment using it sets the total accordingly. Both the canonical constructor and `of` throw `IllegalArgumentException` for a null or non-positive total or reserve, a negative lead, and any split leaving the drain at or below zero — so an over-committed budget fails where it is configured rather than truncating a shutdown much later. One millisecond of drain is a valid split. The messages name the record components; whichever integration read the values says which of its own settings they came from.

Whoever runs the sequence owns the one piece of mutable state it needs: a flag saying this instance has begun leaving rotation, set before admission closes and readable from the thread answering a readiness probe. That flag stays with the runner rather than becoming a type here, because nothing else in this module reads it and a shared holder would only be a second name for one boolean. §4 is where it becomes the `shutdown.rotation` condition.

## 4. Observability

Implements [B-OBS-01 to B-OBS-05](OGBS-Boot-1.0.md#4-observability-composition).

| Type | Role |
| --- | --- |
| `FrameworkMetrics` | `public static MetricsSource of(...)` for `GameRuntime`, `GameData`, `RpcNode` and `NetworkServer`; each takes one snapshot and reports its fields |
| `ReadinessCheck` | `String name()`, `boolean ready()`. Must answer from state it already holds; must not block or dispatch |
| `ObservabilityEndpoints` | `public HttpDispatcher wrap(HttpDispatcher business)`, installed in front of business dispatch |

`FrameworkMetrics` holds the adapters rather than the components, so a component never learns the readings contract and game-network keeps depending on no other framework module. Names are the record's own field names in dotted form under the prefix the registry was given, so `runtime` plus `route.queued` reads as `runtime.route.queued`. Per-Domain readings are part of the name because the Core sink carries no dimensions: `domain1.queued`, `domain1.executor.capacity` and so on. A Route Key is published as a gauge *value*, never as part of a name — `running.oldest.domain`, `running.oldest.key` and `domain1.running.oldest.key` — so watching which Route is stuck costs one series however many Routes exist, which is why Keys are safe here and would not be as a dimension. A name segment must start with a letter, which is why the Domain id follows `domain` rather than standing alone, and cardinality is fixed at build. Publish each present component under the fixed prefixes `runtime`, `data`, `rpc`, `network` with `registry.publish(prefix, source)`; a component the application built itself is its own to publish the same way.

`new ObservabilityEndpoints(MetricsRegistry, List<ReadinessCheck>, metricsPath, readinessPath)` validates both paths: absolute, at least two characters, free of `?` and `#`, and different from each other, otherwise `IllegalArgumentException` naming the argument. `wrap` returns a dispatcher that answers `GET` on those two paths and passes everything else, including any other method on them, to the wrapped dispatcher. A query string is ignored when matching. Both answers are produced on the calling HTTP thread and enter no Route executor.

Readiness returns 200 with `ready\n`, or 503 with `not ready: ` followed by the comma-separated names of the failing checks in list order. A check that throws a `RuntimeException` counts as not ready. Contribute `shutdown.rotation` first, from the rotation flag of §3, so an instance leaving rotation reports that rather than a consequence of it, then `runtime.accepting` and `data.accepting` from `stats().accepting()`, then the application's own checks.

`/metrics` renders every reading as `name value` on its own line, in registry order, as `text/plain; charset=UTF-8`. This is deliberately not a named exposition format: an application that needs one implements `MetricSink` and maps the names, which is what the Core contract exists for.

## 5. Business path prefix

`new HttpContextPath(String value, HttpDispatcher delegate)` implements `HttpDispatcher` and [B-HTTP-01](OGBS-Boot-1.0.md#5-business-path-prefix). An empty value or `/` is the root prefix and the request passes through unchanged; `/game/` normalizes to `/game`. A value that is not a raw absolute ASCII path of segments drawn from letters, digits, `.`, `_`, `~` and `-`, or that contains an empty, `.` or `..` segment, a percent escape, a query or a fragment, is `IllegalArgumentException` at construction.

For a nonroot prefix, `/game` and `/game/` map to `/`; `/game/echo?routeKey=7` maps to `/echo?routeKey=7`; `/echo`, `/games/echo` and `/game%2Fecho` return 404 without business execution. An invalid origin-form or fragment-bearing target returns 400. Dispatch uses `request.retainedDuplicate()` with the relative URI and releases its own reference in a `finally`, including when the delegate throws, leaving the transport's original request and its reference count untouched. Runtime independently retains an admitted request through method return; deferred access still needs its own ownership.

## 6. Validation

| Entry | Covers |
| --- | --- |
| [BootRpcCompositionTest](../../game-boot/src/test/java/cn/managame/boot/rpc/BootRpcCompositionTest.java) | §2 assembled with no container over real TCP: inbound Notify and Call with envelope and Domain selection, reply through the adapter, outbound Call completing on the source Route for success and missing-Peer failure, hook order with attach seeing an unstarted Node, detach on close, and failed-attach cleanup |
| [ObservabilityEndpointsTest](../../game-boot/src/test/java/cn/managame/boot/metrics/ObservabilityEndpointsTest.java) | §4 path validation, failing and throwing checks named in list order, per-Domain readings with their executor bound, a draining Runtime reporting 503 while readings still answer, prefix independence and non-GET fall-through |
| [HttpContextPathTest](../../game-boot/src/test/java/cn/managame/boot/http/HttpContextPathTest.java) | §5 relative target, untouched original request and reference count when the delegate throws, rejected prefixes |
| [ShutdownBudgetTest](../../game-boot/src/test/java/cn/managame/boot/lifecycle/ShutdownBudgetTest.java) | §3 derivation, defaults under a 30 s grace period, rejected splits, phase set once |

Run root `mvn clean verify` after changing module composition or Maven dependencies, so stale class files cannot hide a broken boundary. Not verified: production resource exhaustion, hung storage drivers, TLS and native security extensions, deployment security, RPC capacity under load. The B-CLOSE-02 lead needs a trigger, so it is verified in the container integration that owns one.
