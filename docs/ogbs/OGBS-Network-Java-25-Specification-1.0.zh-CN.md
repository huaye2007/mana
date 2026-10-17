# OGBS Network Java 25 Development Specification 1.0

[English](OGBS-Network-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Network-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应标准：[OGBS Network Specification](OGBS-Network-1.0.zh-CN.md)。状态：V1 Draft，当前 game-network 实现基线。

本文规定公开 Java API、默认值、异常、线程、资源所有权与扩展点。实现必须在行为上同时满足本文与标准规范，而不只是签名。代码违反契约时修正代码；设计变更同步修订两层规范。

Maven：`cn.managame:game-network:1.0.0-SNAPSHOT`，JDK 25，无 preview。Netty 由根 BOM 管理（当前 4.1.135.Final）。不依赖 game-core。

<a id="1-包结构"></a>

## 1. 包结构

本绑定是 Netty 的薄封装：扩展直接使用原生 pipeline handler，ConnectionHandler 接收解码后的消息与生命周期事件。入口不维护连接注册表。

| 包 | 公开 API |
| --- | --- |
| cn.managame.network.connection | Connection、ConnectionHandler、WriteStatus |
| cn.managame.network.connector | ConnectCallback、WebSocketConnectOptions |
| cn.managame.network.error | NetworkException |
| cn.managame.network.netty | NetworkServer、NetworkServerBuilder、NetworkClient、NetworkClientBuilder |
| cn.managame.network.http | HttpServer、HttpServerBuilder、HttpResponseCallback |

其余类型（NettyConnection、NetworkChannelInitializer、WebSocketTransport、ConnectionHandlerAdapter、HttpServerTransport、payload 适配）均为包级私有。属性直接使用 Netty AttributeKey。可运行示例位于 [game-demo](../../game-demo/README.zh-CN.md) 的 cn.managame.demo.examples.network；契约测试保留在 game-network。模块入口：[game-network](../../game-network/README.zh-CN.md)。

<a id="native-http-server-api"></a>

### 1.1 独立 HTTP 服务端

实现 [N-HTTP-01–08](OGBS-Network-1.0.zh-CN.md#http-server-profile)。HttpServer 自行管理 ServerBootstrap、监听与管线，不包装 NetworkServer，不使用 Connection/ConnectionHandler。消息直接使用原生 FullHttpRequest/FullHttpResponse。不提供 HTTP 客户端或 HTTP/2 API。

| 入口 | 签名或设置 | 契约 |
| --- | --- | --- |
| HttpServer | static HttpServerBuilder builder(); void start(); SocketAddress localAddress(); void close() | AutoCloseable；同步、一次性监听；绑定尝试前 localAddress 为 null |
| 必需 | bindAddress(SocketAddress) | 监听地址 |
| 同步兜底 | handler(Function<FullHttpRequest, FullHttpResponse>) | 对到达管线末端的请求返回一个完整响应；默认空 404 |
| 异步兜底 | asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>) | 稍后通过回调完成（§1.2）；handler/asyncHandler 以最后一次设置为准 |
| 原生管线 | pipeline(Consumer<ChannelPipeline>) | 每条 Channel 按注册顺序执行；addLast 在聚合之后、兜底之前安装 HTTP handler，addFirst 安装调用方创建的 TLS |
| 请求限制 | maxContentLength(int); maxInitialLineLength(int); maxHeaderSize(int) | 正数；默认 body 1 MiB、首行 4096 字节、请求头 8192 字节 |
| 入站时间 | readTimeoutMillis(long) | 默认 30,000 ms；0 禁用；负数拒绝；持续无入站字节达到该时长即关闭 |
| 业务执行 | executorGroup(EventExecutorGroup) | 可选、借用；每个执行器必须是 OrderedEventExecutor |
| 传输 | bossGroup、workerGroup、channelFactory、option、childOption | 默认按平台选择传输（见 NetworkTransports），自有 boss（1 个 loop）与自有 worker（Netty 默认大小）；注入的 group 为借用；channelFactory 默认与 group 的传输匹配 |
| 构建 | HttpServer build() | 不可变快照；start() 前不启动默认 group |

异常：null 参数 → NullPointerException；非法限制或无序执行器 → IllegalArgumentException；缺少必需项、重复 start、close 后 start → IllegalStateException；绑定失败在清理后抛 NetworkException 并保留原因。管线初始化失败只关闭该接入 Channel。

管线：`[调用方 SslHandler] → http-read-timeout → http-codec → http-validation → http-keep-alive → http-aggregation → 用户 handler → http-application`。`http-` 名称保留给框架，扩展不得移除或重排。

协议处理：
- 接受 HTTP/1.1 与 HTTP/1.0。HTTP/1.1 要求恰好一个 Host。HTTP/1.0 可省略 Host，并按单请求处理：响应带 `Connection: close`，写完即关闭连接，即使请求要求 keep-alive。其他版本 → 505。
- 多个 Host → 400；CONNECT → 405，Upgrade → 400，非法首行/请求头 → 400，body 超限 → 413，不支持的 Expect → 417。这些空协议错误响应后关闭连接。
- 响应始终以 HTTP/1.1 写出。服务端移除兜底响应的 Transfer-Encoding/trailer，按实际 body 设置 Content-Length；HEAD/304 保留显式非负长度且不发 body，204 无长度/body，205 长度为 0。
- 请求或响应带 `Connection: close` 时，写完后关闭连接并抑制后续流水线请求。写失败直接关闭，不重试。
- handler 抛异常、返回 null 或 1xx、或分帧失败时，发送空 500 并在 ERROR 记录原始异常，不向对端发送详情。可识别的 I/O、解码超限与读超时错误记 DEBUG 摘要；未知管线错误仍为 ERROR。

线程：默认全部运行在 Channel EventLoop。配置 executorGroup 后，一条连接的 HTTP 基础管线与兜底运行在同一有序执行器上；此时 http-codec 之后的扩展必须使用同一 group，例如 `pipeline(p -> p.addLast(group, "auth", new AuthHandler()))`。初始化拒绝该位置出现不同执行器，build 拒绝 `childOption(SINGLE_EVENTEXECUTOR_PER_GROUP, false)`。http-codec 之前的 TLS/原始字节 handler 可留在 EventLoop。非 Sharable handler 在配置器内创建。

所有权：
- 同步兜底：请求借用至函数返回。echo 应返回 `new DefaultFullHttpResponse(HTTP_1_1, OK, request.content().retainedDuplicate())`。返回的响应始终转交服务端。
- 原生扩展（N-HTTP-08）：转发型 handler 调用 `ctx.fireChannelRead(request)` 且不释放；消费型 handler 自行释放请求。SimpleChannelInboundHandler 会自动释放，转发时需要 retain。原生响应器用 `ctx.writeAndFlush` 写一个分帧正确的 FullHttpResponse，之后不再产生兜底响应。原生 CorsHandler 与 HttpContentCompressor 可直接用于该管线。

生命周期：读超时度量入站无数据时间（包括空闲 keep-alive 与部分 body），不是 handler 期限；断开不会中断 handler。start/close 不得在服务端自己的 boss/worker/HTTP 执行器上调用。close 将服务端标记为终止、关闭监听，仅对自有 group 调用 `shutdownGracefully(0, 5 s)`；从不关闭注入的执行器，也不等待业务。借用 worker group 时，已接入连接持续到自身关闭。重复 close 无效果。

不在范围内：HTTP/2、内建路由、JSON、multipart、CORS/压缩策略。body 上限不限制执行队列、连接数或响应缓冲。

源码：[HttpServer](../../game-network/src/main/java/cn/managame/network/http/HttpServer.java)、[HttpServerBuilder](../../game-network/src/main/java/cn/managame/network/http/HttpServerBuilder.java)、[HttpServerTransport](../../game-network/src/main/java/cn/managame/network/http/HttpServerTransport.java)。测试：[HttpServerTest](../../game-network/src/test/java/cn/managame/network/http/HttpServerTest.java)、[HttpServerExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/network/HttpServerExampleTest.java)。

<a id="http-async-response"></a>

### 1.2 异步响应回调

`asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>)` 为每个到达兜底的请求创建一个回调。handler 可就地完成，也可交给其他任务（例如 Runtime 路由）；Network 不依赖 Runtime。

```java
boolean onResponse(FullHttpResponse response);
boolean onFail(Throwable cause);
```

- 两个方法均线程安全。第一次调用返回 true 并认领完成，即使 Channel 已关闭；true 不代表送达。之后的调用返回 false。
- 每次非 null 的 onResponse 转交一个引用；重复或无法交付的响应会被释放。传 null 抛 NullPointerException，不认领完成。
- onFail 在仍可交付时发送空 500 并关闭，记录原因。handler 抛异常按 onFail 处理。获胜的非法响应（1xx、非法 HEAD/304 长度）被释放并改为 500/关闭。
- 请求只借用至 handler 返回。先解码所需内容，或 retain/复制 buffer 并自行释放。
- 永不完成回调会让连接一直等待。Network 不增加业务超时、取消或重试；断开不会认领回调。

完成操作在连接的有序 HTTP 执行器上运行（已在其上时就地执行）。执行器拒绝时释放响应并关闭连接。当前响应的最后一个 LastHttpContent 写出前，后续流水线输入暂存在校验/聚合之前，因此自动 100/400/413/417 不会越过待完成的响应；当前 body 结束后暂停 AUTO_READ，仅恢复框架自己暂停的读取。关闭时释放暂存输入。

等待响应期间读超时仍在计时。因此 30 秒无数据关闭可能先于长任务完成，之后的 onResponse 只是释放响应。长操作应禁用或调大该超时。

示例：[HttpAsyncServerExample](../../game-demo/src/main/java/cn/managame/demo/examples/network/HttpAsyncServerExample.java) 及[其测试](../../game-demo/src/test/java/cn/managame/demo/examples/network/HttpAsyncServerExampleTest.java)。

## 2. Connection 与 Handler

```java
public interface Connection {
    boolean isActive();
    boolean isWritable();
    WriteStatus write(Object message);
    void close();
    SocketAddress localAddress();
    SocketAddress remoteAddress();
    <T> T get(AttributeKey<T> key);
    <T> void set(AttributeKey<T> key, T value);
    <T> T remove(AttributeKey<T> key);
}

public enum WriteStatus { ACCEPTED, INACTIVE, NOT_WRITABLE }

public interface ConnectionHandler {
    void onConnected(Connection connection);
    void onMessage(Connection connection, Object message);
    void onDisconnected(Connection connection);
    default void onEvent(Connection connection, Object event) {}
    void onException(Connection connection, Throwable cause);
}
```

Connection 的状态与地址委托给 Channel（地址可能为 null）。`write` 拒绝 null，先检查 active 再检查 writable，然后调用 `writeAndFlush(message, voidPromise())`，即每次写入自带 flush；接口不提供延迟 flush 的入口，需要让多次写入共用一次 syscall 时由应用在 pipeline 中安排（例如 Netty 的 FlushConsolidationHandler）。只有 INACTIVE/NOT_WRITABLE 保证消息仍归调用方。写失败路由到 onException（§6.3），不提供逐次写入的 Future。

```java
ByteBuf buffer = ...;
if (connection.write(buffer) != WriteStatus.ACCEPTED) buffer.release();
```

同一连接的回调在其 EventLoop 上串行执行；一个 handler 实例可能服务多个 EventLoop，连接级状态应放在属性中。不得阻塞 EventLoop。错误进入 onException，不自动关闭；onException 自身失败只记录日志。

<a id="21-引用计数的逐步解释"></a>
<a id="22-属性与回调状态"></a>

### 2.1 引用计数与属性

入站 ReferenceCounted 消息在 onMessage 返回或抛异常后释放。要 echo 或保留 buffer，先 retain；返回 ACCEPTED 后该份引用归 Netty，否则自行释放。异步处理时在离开回调前 retain，由最终消费者释放。onEvent 不自动释放。

属性遵循 Netty 语义：`remove` 即 `getAndSet(null)`，允许 `set(key, null)`，关闭时既不释放也不清空属性值。需要时在 onDisconnected 中清理。

## 3. Server

```java
public final class NetworkServer implements AutoCloseable {
    public static NetworkServerBuilder builder();
    public void start();
    public SocketAddress localAddress();
    public void close();
}
```

start 同步绑定。重复 start 或 close 后 start → IllegalStateException；绑定失败 → NetworkException 且实例被关闭。start 前 localAddress 为 null；端口 0 返回实际绑定端口。

| Builder 方法 | 默认值 / 含义 |
| --- | --- |
| bindAddress(SocketAddress) | 必需 |
| handler(ConnectionHandler) | 必需 |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | 每次调用追加 |
| webSocket(String path) / webSocket(String path, int maxMessageSize) | 启用 WS；默认最大消息 1 MiB |
| bossGroup / workerGroup | 默认自有 `NetworkTransports.newEventLoopGroup(1)` / `newEventLoopGroup(0)`（Epoll 可用时为 Epoll，否则 NIO） |
| channelFactory | 默认与 boss group 的传输匹配（EpollServerSocketChannel 或 NioServerSocketChannel） |
| option / childOption | ServerBootstrap 选项 |
| build() | 校验并快照；不监听 |

WS path 为以 `/` 开头的单一路径，不含 scheme、authority、query、fragment 或通配符。匹配时忽略请求 query；路径不符返回 404。

## 4. Client

```java
public final class NetworkClient implements AutoCloseable {
    public static NetworkClientBuilder builder();
    public Connection connect(SocketAddress remoteAddress);
    public void connectAsync(SocketAddress remoteAddress, ConnectCallback callback);
    public Connection connect(URI uri);
    public Connection connect(URI uri, WebSocketConnectOptions options);
    public void connectAsync(URI uri, ConnectCallback callback);
    public void connectAsync(URI uri, WebSocketConnectOptions options, ConnectCallback callback);
    public void close();
}

public interface ConnectCallback {
    void onSuccess(Connection connection);
    void onFailure(Throwable cause);
}
```

| Builder 方法 | 默认值 / 含义 |
| --- | --- |
| handler(ConnectionHandler) | 必需 |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | 按顺序追加 |
| webSocket() / webSocket(int maxMessageSize) | WS 模式；默认 1 MiB |
| eventLoopGroup | 默认自有 `NetworkTransports.newEventLoopGroup(0)` |
| channelFactory | 默认与 group 的传输匹配（EpollSocketChannel 或 NioSocketChannel） |
| option | Bootstrap 选项 |
| build() | 快照；每次 build 得到独立客户端 |

一个客户端可连接多个目标。TCP 只接受 SocketAddress，WS 只接受 URI（不匹配 → IllegalStateException）。WS URI 需要 host 与 ws/wss scheme（默认端口 80/443）；拒绝 user-info、fragment、端口 0 或越界端口。

TLS 只能通过添加原生 SslHandler 配置，且必须是唯一的首个 handler。wss:// 必须有，ws:// 不允许有；TCP 有则启用 TLS。信任、主机名校验与超时由调用方配置，见 §6.4。

<a id="41-connectattempt-的完成协议"></a>
<a id="41-connectattempt-completion-protocol"></a>
<a id="42-中断和回调线程例外"></a>

### 4.1 建连结果

每次调用使用一个原生 `Promise<Connection>`（基于 ImmediateEventExecutor，借用的 loop 终止后仍能通知）。Bootstrap 在选定 EventLoop 上运行。传输与全部握手就绪后，适配器检查客户端仍开启，用 `setUncancellable()` 认领结果，创建 Connection，调用 onConnected，然后完成成功结果。失败使用 tryFailure；失败或取消时由监听器关闭 Channel，包括取消后才创建的 Channel。

- onConnected 总在 onSuccess／同步返回之前执行。onConnected 中的异常进入 onException；在其中关闭连接仍报告成功。
- ConnectCallback 在选定 EventLoop 上执行。尝试开始前的拒绝（非法输入、客户端已关闭）在调用线程调用 onFailure；若已终止的借用 loop 拒绝通知，则在当前线程执行。callback 为 null 抛 NullPointerException。回调异常只记录日志。
- 在客户端自己的 EventLoop 上同步 connect → IllegalStateException。等待被中断时取消尝试、恢复中断标志并抛 NetworkException；若成功已被认领，onConnected 返回后关闭这条无法交付的 Connection。

<a id="5-配置快照与握手参数"></a>

## 5. 配置快照与握手参数

每次 build 快照 handler、配置器、选项与传输设置；之后修改 Builder 只影响后续 build。handler 与捕获对象共享引用，不复制。Builder 非线程安全。null 参数立即拒绝；缺少必需设置 → IllegalStateException；非法路径与非正数大小 → IllegalArgumentException。

```java
public final class WebSocketConnectOptions {
    public WebSocketConnectOptions(HttpHeaders headers, String subprotocol);
    public static WebSocketConnectOptions headers(HttpHeaders headers);
    public static WebSocketConnectOptions of(HttpHeaders headers, String subprotocol);
    public static WebSocketConnectOptions defaults();
    public HttpHeaders headers();
    public String subprotocol();
}
```

headers 在构造时与调用 `headers()` 时均复制；一个 options 对象可用于多次连接。请求的子协议未被回应时客户端建连失败；服务端不协商子协议。WS 握手超时使用 Netty 原生默认值（当前 10 秒）；TLS 超时由 SslHandler 配置。

<a id="51-快照的深度和可复用范围"></a>
<a id="6-pipeline-与超时"></a>
<a id="61-装配时点与事件方向"></a>

## 6. Pipeline 与超时

```text
[调用方 SslHandler]
→ network-write-errors
→ [network-http → network-http-aggregate
   → 服务端 network-websocket-path → network-websocket
   → network-websocket-aggregate → network-websocket-handshake]
→ [network-binary-in → network-binary-out]
→ 用户 codec/handler
→ network-connection
```

配置器对每条 Channel 按注册顺序执行。`network-` 名称保留：不得移除或重排，不得吞掉握手或 inactive 事件。WS 下用户 encoder 产出 ByteBuf，由 binary-out 包装为 BinaryWebSocketFrame；TCP 不加分帧。Text 帧以 1003 关闭；超大帧/消息与协议违规直接关闭，不进入 onMessage。Ping/Pong/Close 由 Netty 处理。

| 参数 | 默认值与配置 |
| --- | --- |
| TCP 建连 | CONNECT_TIMEOUT_MILLIS 30 秒；option |
| TLS 握手 | SslHandler 默认 10 秒 |
| WS 客户端握手 | Netty 默认，当前 10 秒，从 channelActive 起算（含 TLS） |
| WS 服务端建立 | 同一原生期限，从 channelActive 起覆盖 TLS 与 Upgrade |
| HTTP Upgrade body | 64 KiB |
| WS 二进制消息 | 1 MiB；Builder 配置 |

定时器运行在 EventLoop 上，loop 被阻塞时不是精确的墙钟期限。

<a id="62-为什么区分生命周期-gate-与末端-adapter"></a>
<a id="62-why-lifecycle-gate-and-terminal-adapter-are-separate"></a>
<a id="62-内部职责与扩展边界"></a>

### 6.2 交付与断开

末端适配器在以下条件全部满足时交付连接：装配完成、channelActive 到达、SslHandler 握手成功（若有）、WS 握手成功（若为 WS）、入口仍开启。随后在同一事件中创建 Connection 并调用 onConnected，因此握手后紧跟的首条消息总在 onConnected 之后交付。TLS/WS 完成事件被消费，不进入 onEvent。交付前的关闭或失败只产生一次客户端失败（或服务端诊断），不触发业务回调。

交付后 channelInactive 只调用一次 onDisconnected。EOF 时解码出的消息可能在 `isActive()` 为 false 时进入 onMessage。有序投递的 handler 必须在 inactive 之前转发读取。

只有初始化时添加的原生 SslHandler 被识别为握手前提。不支持动态插入 TLS、STARTTLS、稍后才安装 SslHandler 的 SNI handler 及嵌套 TLS。

<a id="63-写入错误的路由"></a>

### 6.3 写入错误的路由

交付后，`network-write-errors` 把出站错误越过 WS 协议 handler 转发给用户 handler 与 onException，避免 WS handler 直接关闭连接。交付前的错误使握手失败。自定义 handler 自行关闭 Channel 不受此策略约束。

<a id="native-tls-configuration"></a>

### 6.4 原生 TLS 配置

每条 Channel 新建一个 SslHandler 并放在首位，共享同一个 SslContext。

```java
NetworkServer server = NetworkServer.builder()
        .bindAddress(new InetSocketAddress(8443))
        .pipeline(p -> p.addFirst("ssl", serverContext.newHandler(p.channel().alloc())))
        .handler(handler)
        .build();

String host = "localhost";
int port = 8443;
NetworkClient client = NetworkClient.builder()
        .pipeline(p -> {
            SslHandler ssl = clientContext.newHandler(p.channel().alloc(), host, port);
            var parameters = ssl.engine().getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.engine().setSSLParameters(parameters);
            ssl.setHandshakeTimeoutMillis(10_000);
            p.addFirst("ssl", ssl);
        })
        .handler(handler)
        .build();
```

给 newHandler 传 host 并不会启用主机名校验，需要如上显式设置。WSS 时分别加 `webSocket("/game")` / `webSocket()` 并连接 `wss://localhost:8443/game`。wss:// 缺少 handler、ws:// 带 TLS、多个 SslHandler 或位置错误均为配置错误：客户端报告 NetworkException（cause 为 IllegalArgumentException），服务端关闭该 Channel。握手失败或超时不会创建 Connection。

<a id="7-资源与关闭"></a>
<a id="71-默认资源与注入资源"></a>
<a id="72-关闭与成功交付的交叉"></a>

## 7. 资源与关闭

| 装配 | 生命周期 |
| --- | --- |
| 默认 group | 自有；close() 以 `shutdownGracefully(0, 5 s)` 关闭，其上 Channel 随之关闭 |
| 注入 group | 借用；从不关闭；close() 后已有 Channel 保持打开 |
| 原生传输 | `NetworkTransports` 在 Linux 上加载到 netty-transport-native-epoll 原生库时选择 Epoll，否则 NIO；game-network 以 runtime 依赖携带 linux-x86_64 与 linux-aarch_64 原生库。注入 NIO group 时即使 Epoll 可用也使用 NIO channel；`NetworkTransports.socketChannelFactory/serverChannelFactory(group)` 返回与 group 匹配的类型 |

close 只标记入口已关闭，从不遍历连接或建连尝试。就绪时发现入口已关闭则拒绝（客户端报告 IllegalStateException）。借用 group 时，未完成的握手由其自身结果或超时结束——不要禁用原生超时再指望 close 取消。start/close 不得在入口自己的 EventLoop 上执行（不要在 onDisconnected 中调用 Server.close）。重复 close 无效果。Connection.close 非阻塞，可在任何回调中调用。

<a id="8-失败与兼容性"></a>
<a id="81-后续实现不应重新引入的隐式行为"></a>

## 8. 失败与日志

| 场景 | 表现 |
| --- | --- |
| 非法参数、缺少配置、生命周期误用 | Java 参数/状态异常；异步入口调用 onFailure（callback 为 null 除外） |
| bind/connect/TLS/WS/管线初始化失败 | NetworkException，保留原因 |
| 尝试发现客户端已关闭 | IllegalStateException |
| 已建立连接的解码/I/O/handler 错误 | 原始 Throwable → onException |
| onException / ConnectCallback 抛异常 | 记录日志 |
| INACTIVE / NOT_WRITABLE | WriteStatus；调用方保留所有权 |

不提供重连、自动关闭策略或发送队列；心跳、空闲检测与鉴权使用原生 handler 或上层组合。

日志使用 System.Logger `cn.managame.network`，覆盖服务端建立失败及生命周期结束后的传输错误：

| 类型（最多解开 8 层 DecoderException） | 级别 |
| --- | --- |
| ClosedChannelException、SocketException、PrematureChannelClosureException | DEBUG 断开摘要 |
| SSLException、WebSocketHandshakeException、CorruptedFrameException、TooLongFrameException | DEBUG 协议拒绝摘要 |
| 其他 | ERROR，带堆栈 |

DEBUG 摘要只包含上下文、类别、远端地址与异常类型。源码：[NetworkSupport](../../game-network/src/main/java/cn/managame/network/netty/NetworkSupport.java)。

<a id="9-验证与边界"></a>
<a id="91-易错契约的测试定位"></a>

## 9. 验证与边界

| 测试 | 覆盖 |
| --- | --- |
| [NetworkContractTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkContractTest.java) | TCP/TLS/WS/WSS 往返、写入顺序、onConnected 内首发消息、所有权、背压、异常、快照、借用 group |
| [WebSocketContractTest](../../game-network/src/test/java/cn/managame/network/netty/WebSocketContractTest.java) | 分片、控制帧、Text/超大拒绝、路径、headers 快照 |
| [ConnectRaceTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectRaceTest.java) | 结果竞争、中断、建连中关闭、借用 group 关闭 |
| [ConnectionSetupTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectionSetupTest.java) | 握手/首条消息顺序、写错误路由、建立期限 |
| [NativeTlsTest](../../game-network/src/test/java/cn/managame/network/netty/NativeTlsTest.java) | TLS 位置、信任/主机名失败、握手超时、明文拒绝 |
| [DisconnectOrderingTest](../../game-network/src/test/java/cn/managame/network/netty/DisconnectOrderingTest.java) | EOF 消息先于断开、回调失败时释放、有序投递 |
| [EstablishmentLoggingTest](../../game-network/src/test/java/cn/managame/network/netty/EstablishmentLoggingTest.java) | 日志分类 |
| [HttpServerTest](../../game-network/src/test/java/cn/managame/network/http/HttpServerTest.java) | HTTP/1.1 与 1.0、流水线、分帧、所有权、异步完成、超时、生命周期 |
| [NetworkEchoExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/network/NetworkEchoExampleTest.java) | 可运行示例 |

运行 `mvn -pl game-network -am test`，或对整个仓库运行 `mvn clean verify`。测试用 keytool 生成临时证书并限制 Netty 线程数。

<a id="92-突发连接与资源规模"></a>

### 9.2 已知限制

- 未指定 eventLoopGroup 的每个客户端都会创建自己的 group（默认 2 × CPU 个 loop）。应复用客户端或共享 group，而不是构建大量客户端。
- 没有建连并发上限、总建连期限或单次尝试的取消句柄；突发控制属于应用。CONNECT_TIMEOUT_MILLIS 只覆盖 socket 连接阶段。
- WS URI 在选定 EventLoop 上用 JDK 阻塞解析器解析；DNS 缓慢会拖住该 loop。
- onConnected 等回调应保持短小，否则会延迟同一 loop 上的其他 Channel。
- 生产容量、原生传输与跨语言互通尚未验证。
