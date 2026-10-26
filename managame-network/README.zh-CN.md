[English](README.md) | 中文

# managame-network

通用游戏服务器网络组件，基于 Netty 4.2。目前提供 **TCP、WebSocket（ws）、安全 WebSocket（wss）** 服务端（另可做 TLS over TCP），供服务器内部使用的 **HTTP/1.1（含 HTTPS）** 服务，以及自动重连的 **TCP 客户端**。
连接统一抽象为 `Connection`，业务通过 `ConnectionHandler` 接收生命周期与消息；中间的 Netty 管线可以自由添加、复用、定制任意 `ChannelHandler`。

## 快速开始

分两步：`ConnectionInitializer` 描述每个连接的管线长什么样，`NetworkServer` 负责在某个端口上监听。

```java
// TCP：4 字节长度头分帧 + 自己的编解码
ConnectionInitializer tcp = ConnectionInitializer.tcp()
        .addLast(NetworkHandlers.lengthFieldFraming(1 << 20))
        .addLast("codec", MyCodec::new)
        .handler(gameHandler)
        .build();

// WebSocket：二进制帧，每条消息以 ByteBuf 交给后续 handler
ConnectionInitializer ws = ConnectionInitializer.ws("/game")
        .addLast("codec", MyCodec::new)
        .handler(gameHandler)
        .build();

// WSS：证书可来自 PEM（PKCS#8 私钥）或 PKCS#12/JKS
SslContext ssl = NetworkSsl.server(Path.of("server.p12"), "changeit".toCharArray());
ConnectionInitializer wss = ConnectionInitializer.wss("/game", ssl)
        .addLast("codec", MyCodec::new)
        .handler(gameHandler)
        .build();

// 监听：自带线程组（1 个 boss + Netty 默认数量的 worker，开启 TCP_NODELAY）
NetworkServer tcpServer = NetworkServer.start(9000, tcp);
NetworkServer wssServer = NetworkServer.start(new InetSocketAddress("0.0.0.0", 9443), wss);

// 关闭：幂等；关闭监听，不再接入新连接。已接入的连接归业务所有，见"线程与资源"
wssServer.close();
```

`ConnectionInitializer` 就是一个普通的 Netty `ChannelInitializer`，只保存配置，可以同时挂到多个端口，也可以直接交给业务自己的 `ServerBootstrap.childHandler(...)`，或者用 `EmbeddedChannel` 在不开 socket 的情况下测试。

端口传 0 时绑定随机端口，`server.port()` 返回实际端口；传 `InetSocketAddress` 可以指定监听网卡。

## 管线布局与 Handler 定制

每个连接的管线固定为以下顺序（名称见 `PipelineNames`）：

```
addFront… | ssl | http-codec, http-aggregator, ws-path, [ws-compression], ws-protocol, ws-aggregator,
ws-messages | addLast… | customize | connection → ConnectionHandler
```

| 方式 | 位置 | 适用 |
| --- | --- | --- |
| `addFront(name, …)` | TLS / WebSocket 之前，看到原始字节 | IP 过滤、HAProxy 协议、流量整形、原始字节日志 |
| `addLast(name, …)` | TLS / WebSocket 之后，`connection` 之前 | 分帧、编解码、心跳/空闲检测、鉴权 |
| `customize(pipeline -> …)` | 所有 handler 装好之后、`connection` 之前 | 按 `PipelineNames` 在内置 handler 前后插入、替换、移除 |

同一段内按声明顺序排列。每个 handler 有两种传法：

- **共享实例**：`addLast("log", new LoggingHandler())`，必须标注 `@Sharable`，所有连接共用；传入非 `@Sharable` 实例会直接抛 `IllegalArgumentException`。
- **工厂**：`addLast("frame", () -> new LengthFieldBasedFrameDecoder(...))`，每个连接新建一个，用于有状态的 handler（解码器等）。

### 复用：HandlerChain

`HandlerChain` 是一组有序、具名、不可变的 handler 定义，可装到任意多个 `ConnectionInitializer`，也可以装进自己 bootstrap 的管线（例如测试客户端）：

```java
HandlerChain protocol = HandlerChain.builder()
        .addLast("decoder", MyDecoder::new)          // 每连接一个
        .addLast("encoder", MyEncoder.INSTANCE)      // @Sharable，共用
        .build();

ConnectionInitializer.tcp().addLast(NetworkHandlers.lengthFieldFraming(1 << 20)).addLast(protocol)...
ConnectionInitializer.ws("/game").addLast(protocol)...  // WebSocket 本身按消息分帧，无需长度头
protocol.addTo(clientChannel.pipeline());               // 客户端复用同一份定义
```

内置的可复用链（`NetworkHandlers`）：

- `lengthFieldFraming(maxFrameLength)`：4 字节大端长度头（不含自身）的拆包/封包，名称 `frame-decoder` / `frame-encoder`。
- `readTimeout(Duration)`：一段时间收不到任何数据就关闭连接（心跳超时），名称 `read-timeout`。

handler 名称在同一个 `ConnectionInitializer` 内必须唯一，`PipelineNames` 中的内置名称不可占用。

## WebSocket

`WebSocketConfig` 配置路径与消息形式：

```java
WebSocketConfig config = WebSocketConfig.of("/game")
        .withMessages(WebSocketMessages.TEXT)   // BINARY（默认）/ TEXT / FRAMES
        .withMaxMessageSize(256 * 1024)          // 默认 1 MiB，超出以 1009 关闭
        .withSubprotocols("game.v1")
        .withCompression(true);                  // permessage-deflate
ConnectionInitializer.ws(config)...
```

- **BINARY / TEXT**：只接收对应类型的数据帧，payload 以 `ByteBuf` 交给 `addLast` 段，写出的 `ByteBuf` 自动包成对应帧，因此 TCP 与 WebSocket 可以共用同一套编解码 handler；收到另一种帧以 1003 关闭。
- **FRAMES**：不做转换，handler 直接收发 `WebSocketFrame`。
- 任何模式下写出的 `WebSocketFrame` 原样发送。
- 路径精确匹配（允许 query string），其它路径返回 404。握手请求（URI、headers、子协议）在 `onActive` 之前存入
  `connection.get(NetworkAttributes.WEBSOCKET_HANDSHAKE)`，可读取 token 或代理转发的真实 IP。
- 在握手阶段拒绝连接：`addLast` 一个处理 `HandshakeComplete` 用户事件的 handler，校验失败直接 `ctx.close()`，该连接不会触发 `onActive`。
- 建连完成靠 Netty 的用户事件（`SslHandshakeCompletionEvent`、`HandshakeComplete`）传到管线末端的 `connection` 来判断，所以自定义 handler 不处理的用户事件要 `ctx.fireUserEventTriggered(event)` 转发下去（`ChannelInboundHandlerAdapter` 默认就会转发），否则 `onActive` 不会触发。

## Channel 与 Connection 的映射

Connection 怎么创建、怎么获取，都由 `ConnectionMapper` 决定，框架自己不持有 Connection：

- `create(channel)`：每个 channel 建连完成时调用一次，返回值交给 `onActive`。可以新建，也可以返回已有对象（如断线重连时恢复会话）。抛异常或返回 null 时关闭连接，不触发 `onActive`。
- `get(channel)`：之后的每次回调（`onRead`/`onEvent`/`onException`/`onInActive`）都通过它取 Connection，所以连接存续期间映射可以随时改变；返回 null 时跳过该次回调并释放消息，连接保持打开。
- `remove(channel)`：`onInActive` 之后立即调用，必须实现。之后 `get` 应返回 null，这样 `onInActive` 之后不会再有任何回调。

三个方法都在该 channel 的 EventLoop 上被调用。框架本身不保存任何连接状态，"是否已建连、是否已断开"都以 `get` 的结果为准：`create` 之前和 `remove` 之后取不到，就没有回调；此时发生的异常会直接关闭 channel。

默认实现 `ConnectionMapper.attribute()` 为每个 channel 新建一个 `NettyConnection`，存进 `NetworkAttributes.CONNECTION`（`onInActive` 之后清除），自定义的 Netty handler 可以通过 `ctx.channel().attr(NetworkAttributes.CONNECTION).get()` 取到。

自定义示例：用自己的会话类型、存在自己的表里：

```java
class GameSession extends NettyConnection {          // NettyConnection 可继承，channel() 可取底层 Channel
    long playerId;
    GameSession(Channel channel) { super(channel); }
}

class SessionTable implements ConnectionMapper {
    final Map<ChannelId, GameSession> sessions = new ConcurrentHashMap<>();
    public Connection create(Channel ch) { GameSession s = new GameSession(ch); sessions.put(ch.id(), s); return s; }
    public Connection get(Channel ch) { return sessions.get(ch.id()); }
    public void remove(Channel ch) { sessions.remove(ch.id()); }
}

ConnectionInitializer.tcp().connectionMapper(new SessionTable()).handler(gameHandler)...
```

也可以完全不用 `NettyConnection`，自己实现 `Connection` 接口。

## ConnectionHandler 语义

- 同一个 `ConnectionInitializer` 的所有连接共用同一个 `ConnectionHandler` 实例；连接级状态放在 `Connection` 的 attribute 或自己的 Connection 类型中。回调拿到的 Connection 就是 `ConnectionMapper` 当时返回的对象。
- 同一连接的回调都在该连接的 EventLoop 上按顺序执行，不能阻塞。
- `onActive`：TCP 接入、TLS 握手、WebSocket 握手（按配置）全部完成后调用，握手失败的连接不会出现在 `ConnectionHandler` 中。`handshakeTimeout`（默认 10 秒）设置的是 Netty 自带的 TLS 握手超时和 WebSocket 握手超时；后者从收到升级请求才开始计时，所以连上后什么都不发的客户端要靠 `NetworkHandlers.readTimeout(...)` 关闭，生产环境应当配置。
- `onRead`：消息在回调返回后被 release；需要异步保留的 `ByteBuf` 请先 `retain()`。
- `onInActive`：每个 `onActive` 过的连接最多一次；只有映射已不再返回 Connection 时才会跳过。
- `onEvent`：管线中的用户事件（如 `IdleStateEvent`），不含 TLS / WebSocket 握手事件。
- `onException`：`onActive` 之后的异常（包括其它回调抛出的异常）。连接不会自动关闭，由业务决定。
- `Connection.write` 返回 `ACCEPTED` / `INACTIVE` / `NOT_WRITABLE`（超过写缓冲高水位），不阻塞。

## HTTP 服务

面向服务器内部的 HTTP/1.1 服务，例如 GM 接口、支付回调、健康检查。和长连接一样分两步：`HttpInitializer` 描述管线，`NetworkServer` 负责监听。

```java
HttpInitializer admin = HttpInitializer.http()                // HTTPS：HttpInitializer.https(sslContext)
        .contextPath("/admin")                                 // 只处理 /admin 下的请求，其它路径直接 404
        .maxContentLength(256 * 1024)                          // 请求体上限，默认 1 MiB，超出返回 413 并关闭连接
        .addFront("ip", new RuleBasedIpFilter(...))            // 原始字节层：IP 白名单等
        .handler(exchange -> {
            if (exchange.path().equals("/gm/kick")) {          // 请求 /admin/gm/kick?id=1 时 path() 为 /gm/kick
                String body = exchange.request().content().toString(UTF_8);  // 请求在 handle 返回后释放，需要的数据先取出来
                gameExecutor.execute(() -> exchange.respond(json(OK, kick(body))));   // 可以在任意线程稍后应答
            } else {
                exchange.respond(HttpResponseStatus.NOT_FOUND);
            }
        })
        .build();
NetworkServer http = NetworkServer.start(8080, admin);
```

- `HttpHandler.handle(exchange)` 在连接的 EventLoop 上调用，不能阻塞。`HttpExchange` 代表一次请求和它的响应：
  - `request()`：聚合后的完整请求（`FullHttpRequest`），`handle` 返回后被释放，异步使用前要 `retain()` 或先取出数据；
  - `path()`：去掉 contextPath 和查询串后的路径，保持原始编码，例如 contextPath 为 `/admin` 时，`/admin/users?id=1` 得到 `/users`，`/admin` 本身得到 `/`；
  - `respond(...)`：应答，可在任意线程调用，重复应答返回 false；未设置 `Content-Length` 时自动按内容长度补上（204/304 除外）；
  - `remoteAddress()`：对端地址。
- `contextPath` 默认为根路径；末尾的 `/` 会被忽略。不在其下的请求直接返回 404，不会进入 `HttpHandler`。`addLast` 段的 handler 不受它限制，能看到所有请求。
- 每个请求必须恰好应答一次；`handle` 抛异常时返回 500。
- 响应严格按请求顺序发出：一个请求读完后，连接暂停读取，直到它的最终响应写出（100 Continue 这类临时响应不算）。因此异步应答、客户端流水线发送多个请求都不会乱序。
- keep-alive 由 Netty 的 `HttpServerKeepAliveHandler` 按请求和响应的 `Connection` 头处理；HTTP/1.0 同样支持。
- `addLast` 段的 handler 看到的是 `FullHttpRequest`，可以自己直接应答（例如健康检查），但对自己消费掉的每个请求都必须写出一个最终响应，否则该连接不会再读取后续请求。
- HTTP/2 暂不支持：内部服务很少能从多路复用中获益，而 h2c 两种建连方式加上 TLS 的 ALPN 协商会让协议入口从一条变成三条。请求处理接口与协议无关，以后需要时可以加上，业务代码不用改。

管线布局见 `PipelineNames`：

```
addFront… | [ssl] | http-codec, http-flow, http-exchange, http-keep-alive, http-aggregator | addLast… | http-handler
```

## 客户端

`NetworkClient` 维持到一个远端地址的连接：立即发起连接，之后连接失败或连接断开都会自动重连，每次失败后等待时间翻倍，直到上限，成功一次后从下限重新开始。每条连接做什么由传入的 handler 决定，通常就是一个 `ConnectionInitializer`，于是客户端连接和服务端连接用的是同一套 `ConnectionHandler` 语义：每次连上触发 `onActive`，断开触发 `onInActive`。

```java
ConnectionInitializer upstream = ConnectionInitializer.tcp()
        .addLast(NetworkHandlers.lengthFieldFraming(1 << 20))
        .addLast("codec", MyCodec::new)
        .handler(upstreamHandler)
        .build();
NetworkClient client = NetworkClient.connect(new InetSocketAddress("10.0.0.2", 7001), upstream);

client.isConnected();   // 当前连接是否可用
client.close();         // 停止重连并关闭当前连接
```

- 重连间隔默认 100 ms 到 10 秒，可用 `connect(bootstrap, address, minRetry, maxRetry)` 修改。
- 传入自己的 `Bootstrap` 可以设置选项和线程组。和服务端一样，框架只在副本上补齐没设置的部分：没有线程组时自建 1 个 EventLoop（`close()` 时关闭），channel 类型按线程组选择，默认开启 `TCP_NODELAY`。同一个 bootstrap 可以用来建立多个客户端，例如到同一地址的多条连接。
- 客户端只为了关闭而保存当前的 channel，连接本身仍由 `ConnectionHandler` 交给业务。
- `ConnectionInitializer` 的 TLS 和 WebSocket 只实现了服务端一侧。TLS 或 WebSocket 客户端请传入自己的 `ChannelInitializer`（Netty 的客户端 `SslContext`、`WebSocketClientProtocolHandler` 等）。
- `close()` 不能在客户端自建的 EventLoop 上调用。

## 线程与资源

- 框架不记录已接入的连接，连接由业务自己保存和关闭（例如在 `onActive` 里登记、`onInActive` 里移除，关服时遍历自己的表调用 `connection.close()`）。
- 监听端口的所有设置（socket 选项、线程组、channel 类型）都用 Netty 自己的 `ServerBootstrap` API，框架不另行包装。`NetworkServer.start(bootstrap, address)` 只在副本上补齐业务没设置的部分，传入的 bootstrap 不会被修改，可以重复使用：

  | 业务没设置 | 框架补上 |
  | --- | --- |
  | 线程组 | 自建 1 个 boss 线程和 Netty 默认数量（CPU×2）的 worker 线程，`close()` 时一并关闭 |
  | channel 类型 | 按线程组选择：epoll 可用时用 epoll，否则 NIO |
  | `TCP_NODELAY` | `true` |

  ```java
  // 只改选项，线程组仍由框架创建和关闭
  NetworkServer server = NetworkServer.start(new ServerBootstrap()
          .option(ChannelOption.SO_BACKLOG, 1024)
          .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
          .childHandler(game), new InetSocketAddress(9001));

  // 多个端口（如 TCP + WS 同时对外）共享业务自己的线程组
  EventLoopGroup boss = NetworkTransports.newEventLoopGroup(1, "net-boss");
  EventLoopGroup worker = NetworkTransports.newEventLoopGroup(8, "net-worker");
  ServerBootstrap shared = new ServerBootstrap().group(boss, worker).option(ChannelOption.SO_BACKLOG, 1024);
  NetworkServer tcpServer = NetworkServer.start(shared.clone().childHandler(tcp), new InetSocketAddress(9000));
  NetworkServer wsServer = NetworkServer.start(shared.clone().childHandler(ws), new InetSocketAddress(9001));
  ```

  `NetworkServer.start(port, initializer)` 等同于用一个只设置了 child handler 的 bootstrap，所有设置都取默认值。
- 框架自建的线程组在 `close()` 时一并关闭，这会顺带关掉其上仍存在的连接，`close()` 返回时它们的 `onInActive` 已执行完。业务提供的线程组，`server.close()` 只关闭监听端口，不会关闭线程组，也不会关闭已接入的连接，这些连接由业务关闭。
- `NetworkServer` 返回时已绑定完成，没有"未启动"状态。绑定失败抛 `NetworkException`（框架自建的线程组随之关闭）。
- `close()` 不能在该服务器的 EventLoop 线程中调用：在监听端口自己的线程上等待时 Netty 会直接报错，在自带的线程组里等待自身停止则会死锁，所以框架会拒绝。

## 当前范围

已实现：TCP / TLS over TCP / ws / wss 服务端，HTTP/1.1 / HTTPS 服务端，自动重连的 TCP 客户端。未包含：HTTP/2、TLS 与 WebSocket 客户端的现成封装、连接数与频率限制等，可通过 `addFront` / `addLast` 或自己的 `ChannelInitializer` 接入现成的 Netty handler 实现。

测试位于 `src/test/java/cn/managame/network`，使用真实回环连接覆盖回显、生命周期顺序、握手超时、路径 404、错误帧类型、超长消息、TLS 失败、handler 复用与校验、自定义 Connection 映射，并用 `EmbeddedChannel` 验证 `ConnectionInitializer` 可脱离 socket 使用；HTTP 测试用 JDK 自带的 `HttpClient` 和原始 socket 覆盖同步/异步应答、500、413、流水线顺序、前置 handler 自行应答和 HTTPS；客户端测试覆盖服务端晚启动、连接断开后的重连，以及关闭从未连上的客户端。

## 构建

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

测试会打开真实的回环连接，需要在沙箱外运行；`argLine` 给 JDK 的 Unix-domain selector 指定一个可写目录。
