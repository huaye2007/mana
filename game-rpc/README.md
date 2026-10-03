# game-rpc

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 implementation of OGBS RPC, Maven cn.managame:game-rpc, depending on game-core and game-network. Provides internal TCP, multiple Slots, active/passive Peers, call/notify/reply, handshakes, heartbeats and reconnect with an additive random delay. Node-wide IDs, notification isolation, recovery handoff and verification boundaries are in [Java section 9.1](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md#91-审阅确认的缺陷与规模风险). RPC core has no Runtime/business codec dependency; optional Spring adaptation belongs to game-spring.

<a id="规范"></a>

## Specifications

| Specification | Java Development Specification |
| --- | --- |
| [OGBS RPC Specification](../docs/ogbs/OGBS-RPC-1.0.md) | [RPC Java 25 Development Specification](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md) |

Byte layout: [RPC Wire Profile](../docs/rpc-wire.md). Shared Metadata/error codes: [Core](../docs/ogbs/OGBS-Core-1.0.md).

<a id="使用"></a>

## Usage

```java
try (RpcNode node = RpcNode.builder()
        .nodeId(1)
        .bindAddress(new InetSocketAddress("127.0.0.1", 9000))
        .handler(handler)
        .build()) {
    node.start();
    node.addPeer(2, new InetSocketAddress("127.0.0.1", 9001), 2);
    node.call(2, new RpcRequest(1001, encodedBody), (MyResponse response) -> {
        // The application's RpcHandler decodes the response and invokes this callback.
    });
}
```

handler, encodedBody and MyResponse are application-supplied. addPeer maintains connections asynchronously; a call immediately afterward can report onFail(UNAVAILABLE), without waiting or retry. Finite call admission is deferred. Timeout notifications use owned virtual threads awaited by close, leaving the maintenance timer free. Reconnect base defaults to 1000ms, with a random addition up to one quarter of that base; reconnectRandomDelay(Duration.ZERO) selects fixed delay. Random delay spreads attempts; CAS handoff separately repairs recovery ownership races.

Entry points are in cn.managame.rpc.node; messages in message, handlers/callbacks in call, statuses in transport, errors in error, and wire binding in netty. Peer/Slot/PendingCall remain encapsulated internally.

After argument and lifecycle validation, call/notify/reply consume one body reference, even on send failure. Inbound bodies are borrowed only during the Handler callback; retain/copy before asynchronous use or transfer to reply. RpcHandler.onResponse interprets every remote error; local failures go to onFail.

start/close are synchronous management operations and cannot run inside this Node's Handler, EventLoop, or timer. close waits for owned resources, not business tasks dispatched elsewhere.

<a id="示例与验证"></a>

## Examples and validation

Runnable examples and execution tests live in [game-demo](../game-demo/README.md); the RPC artifact publishes only framework code. [RpcEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) demonstrates real local TCP/dynamic ports, body retain and callback decoding, executed successfully in root clean verify. Object dispatch/replies and Route callbacks are described in [Spring integration](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).

```shell
mvn -pl game-rpc -am test
mvn clean verify
```

Wire, routing, reference counts, completion races, real TCP, Peer recreation, recovery ownership and timeout isolation regressions pass: 32 RPC tests and root clean verify. Finite admission is deferred; production capacity and cross-language interoperability are unverified. Core supplies no discovery or RPC TLS/WS configuration; optional Runtime integration is in game-spring.