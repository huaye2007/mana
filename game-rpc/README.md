# game-rpc

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 implementation of OGBS RPC, published as cn.managame:game-rpc. Depends on game-core and game-network. Provides internal TCP, multiple slots, active/passive peers, call/notify/reply, handshakes, heartbeats, and fixed-delay reconnects. RPC does not depend on Runtime or business codecs.

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

handler, encodedBody, and MyResponse are application-supplied objects/types. addPeer maintains connections asynchronously; immediately after registration, call may synchronously report onFail(UNAVAILABLE). It does not wait for connections or retry automatically.

Entry points are in cn.managame.rpc.node; messages in message, handlers/callbacks in call, statuses in transport, errors in error, and wire binding in netty. Peer/Slot/PendingCall remain encapsulated internally.

After argument and lifecycle validation, call/notify/reply consume one body reference, even on send failure. Inbound bodies are borrowed only during the Handler callback; retain/copy before asynchronous use or transfer to reply. RpcHandler.onResponse interprets every remote error; local failures go to onFail.

start/close are synchronous management operations and cannot run inside this Node's Handler, EventLoop, or timer. close waits for owned resources, not business tasks dispatched elsewhere.

<a id="示例与验证"></a>

## Examples and validation

Run [RpcEchoExample](src/main/java/cn/managame/rpc/example/RpcEchoExample.java) in an IDE to print hello game-rpc. It uses real local TCP and two random ports, demonstrating body retain and generic callback decoding.

```shell
mvn -pl game-rpc -am test
mvn clean verify
```

Tests cover wire, routing, reference counts, completion races, real TCP, reconnects, and the example. Production capacity and cross-language interoperability are unverified. Automatic Runtime integration, service discovery, and RPC TLS/WS configuration are not provided.
