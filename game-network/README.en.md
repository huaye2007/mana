# game-network

[中文](README.md) | English

`game-network` is the Netty network transport component. It provides server lifecycle, long-lived connection event delivery, TCP/WebSocket pipelines, and unified HTTP request/response semantics.

Sessions, player/account identity, authentication state, and the `connection -> session` mapping all belong to the business layer and are neither defined nor managed here. HTTP business handlers see only a unified `HttpRequest` and `IHttpResponder`, without knowing whether HTTP/1 or HTTP/2 is used underneath.

## Module Boundary

- `game-network-core`
  - `IConnection`: send, close, state, and transport type of a connection.
  - `IConnectionHandler`: connect, message, disconnect, exception, and idle events.
  - `INetworkServer`: the minimal `start/stop` lifecycle contract.
- `game-network-http`
  - `HttpRequest` / `HttpResponse`: complete HTTP messages independent of the network implementation.
  - `IHttpHandler` / `IHttpResponder`: request and response callbacks without `CompletionStage`.
- `game-network-netty`
  - `NettyConnection` / `WebsocketConnection`: adapters for Netty channels.
  - `NettyServer` / `NettyServerBuilder`: unified TCP, WebSocket, and fully custom servers.
  - `NettyHttpServer`: adapts HTTP/1, HTTP/2, and h2c to the unified HTTP handler.
  - Default conversion between WebSocket frames and messages.

The component no longer provides sessions, a SessionManager, a ConnectionManager, network configuration DTOs, or a generic pipeline configuration interface. Configure `ServerBootstrap`, `ChannelOption`, and `ChannelPipeline` directly when Netty options are needed, avoiding wrappers that mirror version-sensitive Netty APIs.

## Business Integration

The business layer implements `IConnectionHandler`. If it needs sessions, it creates them when connections open and maintains the mapping in its own manager:

```java
IConnectionHandler handler = new IConnectionHandler() {
    @Override
    public void onConnect(IConnection connection) {
        sessions.add(new PlayerSession(connection));
    }

    @Override
    public void onMessage(IConnection connection, Object message) {
        PlayerSession session = sessions.get(connection);
        router.dispatch(session, message);
    }

    @Override
    public void onDisconnect(IConnection connection) {
        sessions.remove(connection);
    }

    @Override
    public void onException(IConnection connection, Throwable cause) {
        connection.close();
    }

    @Override
    public void onIdle(IConnection connection) {
        connection.close();
    }
};
```

## Standard TCP Server

```java
INetworkServer tcpServer = NettyServer.tcp(handler)
        .bind("0.0.0.0", 9000)
        .bootstrap(bootstrap -> bootstrap
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true))
        .pipeline(pipeline -> {
            pipeline.addLast("idle", new IdleStateHandler(60, 0, 0));
            pipeline.addLast("decoder", packetDecoder);
            pipeline.addLast("encoder", packetEncoder);
        })
        .build();

tcpServer.start();
```

Handlers added by `pipeline(...)` are native Netty handlers. Without a decoder, TCP messages are normally delivered as `ByteBuf`; the consumer is then responsible for releasing them.

## Standard WebSocket Server

```java
WebSocketServerProtocolConfig protocol = WebSocketServerProtocolConfig.newBuilder()
        .websocketPath("/ws")
        .checkStartsWith(true)
        .build();

INetworkServer wsServer = NettyServer.webSocket(protocol, handler)
        .bind("0.0.0.0", 9001)
        .httpMaxContentLength(64 * 1024)
        .beforeProtocol(pipeline -> pipeline.addLast("ssl", sslHandler))
        .pipeline(pipeline -> pipeline.addLast("packetCodec", packetCodec))
        .build();
```

The WebSocket connect event fires after a successful handshake. `beforeProtocol(...)` is intended for TLS and other components that must precede the HTTP/WebSocket protocol handlers.

## HTTP/1 and HTTP/2

The business handler uses one interface, sees no Netty object, and does not return a `CompletionStage`:

```java
IHttpHandler httpHandler = (request, responder) -> {
    if (request.uri().equals("/health")) {
        responder.text("ok");
    } else {
        responder.send(HttpResponse.empty(404));
    }
};

INetworkServer httpServer = NettyHttpServer.builder(httpHandler)
        .bind(8080)
        .protocol(NettyHttpProtocol.AUTO)
        .maxContentLength(1024 * 1024)
        .maxInitialLineLength(4 * 1024)
        .maxHeaderSize(8 * 1024)
        .maxPendingRequests(1024)
        .maxConcurrentStreams(128)
        .requestTimeout(Duration.ofSeconds(30))
        .build();
```

On a cleartext port, `AUTO` accepts HTTP/1.1, h2c upgrade, and HTTP/2 prior knowledge. With an `SslContext`, ALPN selects HTTP/1.1 or HTTP/2; the context must use Netty's native ALPN configuration to advertise `h2` and `http/1.1`.

`IHttpResponder` may be called immediately or retained until an RPC, actor, or other asynchronous callback completes. The adapter safely returns execution to the corresponding Netty event loop and allows each request to complete only once. Every HTTP/2 stream is adapted into an independent request without exposing that protocol detail to the business handler.

`maxPendingRequests` bounds the ordered-response queue on each HTTP/1 connection; overflow receives 429 and closes the connection. `maxConcurrentStreams` advertises the HTTP/2 stream limit through SETTINGS. A request receives 408 when the business handler has not completed it before `requestTimeout`. Initial-line (including URI), header, and aggregated-body sizes are bounded so one connection cannot consume unbounded memory.

`IConnectionHandler` and `IHttpHandler` are invoked serially on the corresponding channel's Netty event loop. Business code may call `IConnection.writeMsg` or the one-shot `IHttpResponder` from another thread; the adapter returns response work to the event loop. Reference-counted messages without a decoder, such as `ByteBuf`, are owned and released by the business handler after `IConnectionHandler.onMessage`; `HttpRequest` and `HttpResponse` instead copy headers and bodies and contain no Netty reference-counted objects.

Every server `stop()` directly closes the listening socket, active connections, and owned EventLoopGroups. It neither tracks business requests nor performs protocol-level draining. A stopped server cannot be restarted.

The first version aggregates complete request and response bodies; streaming uploads, SSE, and HTTP/2 Push are outside its scope.

## Fully Custom Server

For a custom protocol or complete pipeline control, reuse only the server lifecycle:

```java
INetworkServer customServer = NettyServer.custom(new ChannelInitializer<SocketChannel>() {
    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline().addLast(customProtocolHandler);
    }
})
.bind(9100)
.bootstrap(bootstrap -> bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true))
.build();
```

Calling `initializer(...)` on a TCP/WebSocket preset also replaces its default pipeline completely.

## Multiple Servers

Every `build()` creates an independent `NettyServer`, so one process can listen on multiple TCP, WebSocket, or custom ports:

```java
INetworkServer clientTcp = NettyServer.tcp(clientHandler).bind(9000).build();
INetworkServer clientWs  = NettyServer.webSocket(wsProtocol, clientHandler).bind(9001).build();
INetworkServer adminHttp = NettyHttpServer.builder(httpHandler).bind(8080).build();
INetworkServer internal  = NettyServer.tcp(rpcHandler).bind("127.0.0.1", 9100).build();

clientTcp.start();
clientWs.start();
adminHttp.start();
internal.start();
```

By default, each server creates and closes its own `EventLoopGroup`, so their lifecycles are isolated. To share event loops, the upper layer must create and close them and configure every server with:

```java
.eventLoopGroups(sharedBoss, sharedWorker, false)
```

Set the final argument to `true` only when one server exclusively owns those groups and is responsible for closing them.
