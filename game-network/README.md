# game-network

**[English](README.md)** | [简体中文](README.zh-CN.md)

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Network Specification](../docs/ogbs/OGBS-Network-1.0.md) | [OGBS Network Java Development Specification](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) |

Component behavior and Java implementation are maintained separately in these specifications. This document is the usage entry point.

JDK 25 + Netty connections for TCP, TLS TCP, binary WebSocket, and WSS, published together as `cn.managame:game-network`.

A thin Netty facade: configure native handlers through pipeline(...), then receive decoded messages, events and lifecycle callbacks in your ConnectionHandler. Applications own connection indexing, sessions, reconnect and batch shutdown.

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

For a WebSocket server, add `.webSocket("/game")`. Build a client with `NetworkClient.builder().webSocket().handler(handler).build()`, then call `client.connect(URI.create("ws://localhost:9000/game"))`. For TLS, explicitly add a native SslHandler with `pipeline(p -> p.addFirst("ssl", ...))` on both endpoints. WSS requires this configuration; no default TLS context is created. The caller configures trust and hostname verification. See [native TLS examples](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md#native-tls-configuration).

WS/WSS handshake timeouts use native Netty defaults, currently 10 seconds; successful handshakes notify onConnected immediately. Servers do not negotiate subprotocols, and builders expose no additional WS timeout/subprotocol settings. Recognizable establishment disconnections and protocol rejections produce DEBUG summaries; unknown/configuration errors retain ERROR stacks. See the [Java specification](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.md) for complete boundaries.

<a id="包与所有权"></a>

## Packages and ownership

- connection: Connection, ConnectionHandler, WriteStatus.
- connector: ConnectCallback, WebSocketConnectOptions.
- error: NetworkException.
- netty: Server/Client, builders, package-private implementation.

NetworkChannelInitializer assembles the pipeline; TLS is caller-configured and WebSocketTransport supplies the binary WS profile. ConnectionHandlerAdapter directly handles native TLS/WS completion events, checks endpoint admission, invokes onConnected, then completes the client result. There is no intermediate readiness Promise or combined handshake result. The terminal adapter also preserves final decoded messages before disconnection. Client uses one native Promise per connection result; neither endpoint stores connections or unfinished attempts. These remain package-private. The old sslContext(...) builder method is removed; use pipeline(...) instead. See Java specification §6.

ConnectionHandler.onMessage borrows its message; the framework releases it afterward. ACCEPTED transfers write ownership to Netty; INACTIVE/NOT_WRITABLE do not. Attributes use Netty AttributeKey directly; there is no separate attribute package.

Server/Client close only EventLoopGroups they created. Applications close external groups and own established connections. Synchronous start/connect/close must not block their own EventLoop; use Connection.close() for individual connections inside callbacks.

Server/Client close stop entry points and release only owned EventLoopGroups; they never enumerate channels. With external groups, an unfinished handshake completes or times out independently and rejects delivery if it then observes the closed endpoint. Existing Connections remain application-owned. See the specification for a delivery already racing with close.

<a id="运行与验证"></a>

## Run and validate

```shell
mvn -pl game-network -am test
mvn clean verify
```

Runnable examples and their execution tests are maintained in [game-example](../game-example/README.md); game-network publishes only framework code. Run [NetworkEchoExample](../game-example/src/main/java/cn/managame/example/network/NetworkEchoExample.java) in an IDE. It uses a random port, length framing, and plain strings, and releases all connection and network resources.

Tests cover TCP/TLS/WS/WSS, handshake failure, reference counts, backpressure, shutdown, and interruption races. They create temporary certificates and require no external services. Public-network deployment, native transport, and production capacity are unverified.
