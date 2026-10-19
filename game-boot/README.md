# game-boot

**[English](README.md)** | [简体中文](README.zh-CN.md)

The composition tier: the joins that turn capability components into a running server, with no container of any kind. Java 25, depending on game-runtime and game-data, with optional game-rpc.

A capability component must not learn about its neighbours — Runtime must not know the RPC wire, RPC must not know Routes, Network must not know the readings contract — so something has to join them, and this is the only module allowed to depend on several at once. Everything here is an ordinary object an application constructs, holds and closes itself. [game-spring](../game-spring/README.md) is a thin Spring adapter over exactly these types, so an application that uses no container gets the same behaviour from the same code.

## RPC to Runtime

Add game-rpc explicitly, implement a thread-safe `GameRpcCodec`, and construct the adapter:

```java
var builder = RpcNode.builder().nodeId(1).bindAddress(new InetSocketAddress("127.0.0.1", 9100));
try (var runtime = GameRuntimeBuilder.builder()./* … */build();
     var rpc = new GameRpc(runtime, codec, builder, List.of(routingConfigurer))) {
    rpc.node().start();
    rpc.node().addPeer(2, peerAddress, 1);
}
```

The constructor is the whole join: it calls every `GameRpcConfigurer` hook itself, in list order — `configure`, `decorate`, build, `attach` — and `close()` detaches in reverse before closing the Node. A failing `attach` detaches what attached and closes the unstarted Node. Inbound frames decode through the Runtime protocol registry and enter `dispatchRpc`, where the Handler declaration selects the Domain. `rpc.reply(response)` answers the current RPC context; `rpc.call(..., RouteCallback)` from a Route returns completion to that same Route and participates in drain. Full contract: [Boot §2](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.md#2-rpc-adapter).

## Readiness and readings

```java
var registry = new MetricsRegistry();
registry.publish("runtime", FrameworkMetrics.of(runtime));
var endpoints = new ObservabilityEndpoints(registry, checks, "/metrics", "/readiness");
httpBuilder.asyncHandler(endpoints.wrap(new HttpContextPath("/game", runtime.http()))::dispatch);
```

`FrameworkMetrics.of(...)` adapts each component's snapshot record to a Core `MetricsSource`, so a component never learns the readings contract and game-network keeps depending on no other framework module. Publish each present component under the fixed prefixes `runtime`, `data`, `rpc`, `network`, and a dashboard reads any deployment of this framework. `/metrics` renders every reading as `name value` per line; `/readiness` answers 200 `ready` or 503 naming the `ReadinessCheck` conditions that failed. Both are matched before any path prefix and answered on the HTTP thread outside every Route executor, so a saturated or draining instance still answers. Full contract: [Boot §4](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.md#4-observability).

## Shutdown budget

`ShutdownBudget.of(totalMillis, leadMillis, flushReserveMillis)` is one declared budget split into the phases shutdown runs in sequence — leave rotation, drain admitted work, flush the last changes. The total is configured and the drain is derived, so the sum cannot drift, and a split that leaves the drain nothing fails where it is configured. The default total of 25 000 ms sits under the 30 seconds a Kubernetes grace period defaults to. `drain()` is computed rather than stored, so no caller can build a budget whose parts disagree with its total. Whoever runs the sequence — a container's close event, a signal handler, your own `main` — observes the same phases. Full contract: [Boot §3](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.md#3-shutdown-budget).

## Business path prefix

`HttpContextPath` maps a common prefix onto endpoint declarations that do not know about it, matching complete raw path segments, preserving the query, never decoding or redirecting, and leaving the transport's request untouched. Full contract: [Boot §5](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.md#5-business-path-prefix).

## Contracts and build

[Server composition specification](../docs/ogbs/OGBS-Boot-1.0.md), [Java development specification](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.md), [OGBS index](../docs/ogbs/README.md). Public packages are `cn.managame.boot.rpc`, `.metrics`, `.lifecycle` and `.http`; every type in them is constructible.

Build `mvn -pl game-boot -am clean verify`. Tests assemble the RPC adapter with no container over real TCP, and cover hook order, failed-attach cleanup, readiness and readings including a draining Runtime, prefix mapping and reference ownership, and the budget derivation. Production resource exhaustion, hung storage drivers, TLS and RPC capacity under load remain unverified.
