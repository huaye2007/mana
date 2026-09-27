# game-network

**[English](README.md)** | [简体中文](README.zh-CN.md)

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Network Specification](../docs/ogbs/OGBS-Network-1.0.md) | [OGBS Network Java Development Specification](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) |

Component behavior and Java implementation are maintained separately in these specifications. This document is the usage entry point.

JDK 25 + Netty connections for TCP, TLS TCP, binary WebSocket, and WSS, published together as `cn.managame:game-network`.

See the [Network Specification](../docs/ogbs/OGBS-Network-1.0.md) for semantics and the [Java Development Specification](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) for full signatures, defaults, and lifecycle.

<a id="最小服务端"></a>

## Minimal server

```java
import cn.managame.network.connection.*;
import cn.managame.network.netty.NetworkServer;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;

ConnectionHandler handler = new ConnectionHandler() {
    public void onConnected(Connection c) {}
    public void onMessage(Connection c, Object message) {
        ByteBuf reply = ((ByteBuf) message).retain();
        if (c.write(reply) != WriteStatus.ACCEPTED) reply.release();
    }
    public void onDisconnected(Connection c) {}
    public void onException(Connection c, Throwable cause) {
        cause.printStackTrace();
        c.close();
    }
};
NetworkServer server = NetworkServer.builder()
        .bindAddress(new InetSocketAddress(9000))
        .handler(handler)
        .build();
server.start();
// Call server.close() during application shutdown.
```

Without a decoder, TCP delivers byte-stream fragments. Register framing, codecs, and IdleStateHandler through pipeline(...); ChannelOption configures Netty directly.

For a WebSocket server, add `.webSocket("/game")`. Build a client with `NetworkClient.builder().webSocket().handler(handler).build()`, then call `client.connect(URI.create("ws://localhost:9000/game"))`. A TLS server supplies sslContext; a WSS client may use the default JVM trust store.

<a id="包与所有权"></a>

## Packages and ownership

- connection: Connection, ConnectionHandler, WriteStatus.
- connector: ConnectCallback, WebSocketConnectOptions.
- error: NetworkException.
- netty: Server/Client, builders, package-private implementation.

Internal assembly separates NetworkChannelInitializer, protocol-specific TlsTransport/WebSocketTransport, and protocol-independent ConnectionLifecycle. These remain package-private; public Builder and pipeline(...) usage is unchanged. See Java specification §6 for ownership and extension boundaries.

ConnectionHandler.onMessage borrows its message; the framework releases it afterward. ACCEPTED transfers write ownership to Netty; INACTIVE/NOT_WRITABLE do not. Attributes use Netty AttributeKey directly; there is no separate attribute package.

Server/Client close only EventLoopGroups they created. Applications close external groups and own established connections. Synchronous start/connect/close must not block their own EventLoop; use Connection.close() for individual connections inside callbacks.

<a id="运行与验证"></a>

## Run and validate

```shell
mvn -pl game-network -am test
mvn clean verify
```

Run [NetworkEchoExample](src/main/java/cn/managame/network/example/NetworkEchoExample.java) in an IDE. It uses a random port, length framing, and plain strings, and releases all connection and network resources.

Tests cover TCP/TLS/WS/WSS, handshake failure, reference counts, backpressure, shutdown, and interruption races. They create temporary certificates and require no external services. Public-network deployment, native transport, and production capacity are unverified.
