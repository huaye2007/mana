# game-network

[English](README.en.md) | 中文

`game-network` 是 Netty 网络接入组件。它负责 Server 生命周期、长连接事件投递、TCP/WebSocket pipeline，以及统一的 HTTP 请求/响应语义。

Session、玩家/账号身份、登录态，以及 `connection -> session` 映射全部属于业务层，不由本模块定义或管理。HTTP 业务层只处理统一的 `HttpRequest` 和 `IHttpResponder`，不需要知道底层是 HTTP/1 还是 HTTP/2。

## 模块边界

- `game-network-core`
  - `IConnection`：连接的发送、关闭、状态和传输类型。
  - `IConnectionHandler`：连接建立、消息、断开、异常和空闲事件。
  - `INetworkServer`：最小化的 `start/stop` 生命周期契约。
- `game-network-http`
  - `HttpRequest` / `HttpResponse`：与具体网络实现无关的完整 HTTP 消息。
  - `IHttpHandler` / `IHttpResponder`：不依赖 `CompletionStage` 的请求和响应回调。
- `game-network-netty`
  - `NettyConnection` / `WebsocketConnection`：Netty Channel 适配。
  - `NettyServer` / `NettyServerBuilder`：统一 TCP、WebSocket 和完全自定义 Server。
  - `NettyHttpServer`：将 HTTP/1、HTTP/2 和 h2c 适配到统一 HTTP Handler。
  - WebSocket 帧与消息之间的默认转换。

组件不再提供 Session、SessionManager、ConnectionManager、网络配置 DTO 或通用 pipeline 配置接口。需要 Netty 参数时直接配置 `ServerBootstrap`、`ChannelOption` 和 `ChannelPipeline`，从而避免重复包装随 Netty 版本变化的接口。

## 业务接入

业务实现 `IConnectionHandler`。如果业务需要 Session，应在连接建立时创建，并在业务自己的管理器中维护映射：

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

## 标准 TCP Server

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

`pipeline(...)` 中加入的是原生 Netty handler。没有 decoder 时，TCP 消息通常以 `ByteBuf` 投递，释放责任由消费方承担。

## 标准 WebSocket Server

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

WebSocket 的连接建立事件在握手成功后触发。`beforeProtocol(...)` 适合 TLS 等必须位于 HTTP/WebSocket 协议 handler 之前的组件。

## HTTP/1 与 HTTP/2

业务 Handler 使用统一接口，既不接触 Netty 对象，也不需要返回 `CompletionStage`：

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

`AUTO` 在明文端口同时接受 HTTP/1.1、h2c upgrade 和 HTTP/2 prior knowledge。配置 `SslContext` 后通过 ALPN 选择 HTTP/1.1 或 HTTP/2；`SslContext` 必须使用 Netty 原生 ALPN 配置声明 `h2` 和 `http/1.1`。

`IHttpResponder` 可以立即调用，也可以保留到 RPC、Actor 或其他异步业务回调中再调用。适配层保证从业务线程安全地切回对应 Netty EventLoop，并限制一个请求只完成一次。HTTP/2 的每个 Stream 独立适配成一次请求，但这些协议差异不会暴露给业务 Handler。

`maxPendingRequests` 限制单条 HTTP/1 连接中等待有序响应的请求数，超过限制返回 429 并关闭连接；`maxConcurrentStreams` 通过 HTTP/2 SETTINGS 限制并发 Stream。请求超过 `requestTimeout` 且业务仍未完成响应时返回 408。初始行（包含 URI）、Header 和完整 Body 都有上限，避免单连接无限占用内存。

`IConnectionHandler` 和 `IHttpHandler` 由对应 Channel 的 Netty EventLoop 串行调用。业务可以在其他线程调用 `IConnection.writeMsg` 或一次性的 `IHttpResponder`；适配层负责切回 EventLoop。未解码的引用计数消息（例如 `ByteBuf`）交给 `IConnectionHandler.onMessage` 后由业务释放；`HttpRequest`/`HttpResponse` 则复制 Header 和 Body，不携带 Netty 引用计数对象。

所有 Server 的 `stop()` 都直接关闭监听端口、已有连接和自身持有的 EventLoopGroup，不追踪业务请求，也不执行协议级排空。Server 停止后不能再次启动。

第一版聚合完整请求体和响应体，不覆盖流式上传、SSE、HTTP/2 Push 等流式能力。

## 完全自定义 Server

用户需要自定义协议或完整控制 pipeline 时，可以只复用 Server 生命周期：

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

也可以在 TCP/WebSocket preset 上调用 `initializer(...)`，完整替换其默认 pipeline。

## 多个 Server

每次 `build()` 都会创建独立的 `NettyServer`，因此一个进程可以同时监听多个 TCP、WebSocket 或自定义端口：

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

默认情况下，每个 Server 创建并关闭自己的 `EventLoopGroup`，生命周期互不影响。如果多个 Server 需要共享线程组，应由上层统一创建和关闭，并对每个 Server 使用：

```java
.eventLoopGroups(sharedBoss, sharedWorker, false)
```

只有明确由某一个 Server 独占并负责关闭线程组时，才将最后一个参数设为 `true`。
