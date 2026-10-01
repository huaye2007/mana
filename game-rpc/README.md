# game-rpc

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 implementation of OGBS RPC, published as cn.managame:game-rpc. Depends on game-core and game-network. Provides internal TCP, multiple slots, active/passive peers, call/notify/reply, handshakes, heartbeats, and fixed-delay reconnects. Current-source defects and validation gaps are documented in [Java specification section 9.1](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.md#91-审阅确认的缺陷与规模风险). RPC does not depend on Runtime or business codecs.

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

handler, encodedBody, and MyResponse are application-supplied objects/types. addPeer maintains connections asynchronously; immediately after registration, call may synchronously report onFail(UNAVAILABLE). It does not wait for connections or retry automatically. Current Builder has no maxPendingCalls or reconnectJitter. Outstanding calls have no configured admission bound; timeout failures run directly on the shared maintenance timer, so slow handlers delay other deadlines and recovery. Reconnect delay defaults to a fixed 1000ms. These source limitations are recorded in the Java specification; this review changes no production behavior.

Entry points are in cn.managame.rpc.node; messages in message, handlers/callbacks in call, statuses in transport, errors in error, and wire binding in netty. Peer/Slot/PendingCall remain encapsulated internally.

After argument and lifecycle validation, call/notify/reply consume one body reference, even on send failure. Inbound bodies are borrowed only during the Handler callback; retain/copy before asynchronous use or transfer to reply. RpcHandler.onResponse interprets every remote error; local failures go to onFail.

start/close are synchronous management operations and cannot run inside this Node's Handler, EventLoop, or timer. close waits for owned resources, not business tasks dispatched elsewhere.

<a id="示例与验证"></a>

## Examples and validation

Runnable examples and their execution tests are maintained in [game-example](../game-example/README.md); game-rpc publishes only framework code. [RpcEchoExample](../game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) is intended to demonstrate real local TCP, two random ports, body retain and generic callback decoding. Its maxPendingCalls(1024) invocation currently does not compile against the restored Builder; runnable validation is pending.

```shell
mvn -pl game-rpc -am test
mvn clean verify
```

Retained tests describe wire, routing, reference counts, completion races, real TCP and reconnects, but the current RPC suite fails compilation because tests still reference removed APIs. The example also references a missing API. These entries are not a passing verification claim. Production capacity and cross-language interoperability are unverified. Automatic Runtime integration, service discovery, and RPC TLS/WS configuration are not provided.
