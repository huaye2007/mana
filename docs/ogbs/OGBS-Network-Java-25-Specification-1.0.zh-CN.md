# OGBS Network Java 25 Development Specification 1.0

[English](OGBS-Network-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Network-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应标准：[OGBS Network Specification](OGBS-Network-1.0.zh-CN.md)。

本文规定 Java 实现的公开 API、默认配置、异常形式、线程与资源机制、扩展接入以及验证要求。Java 实现必须同时满足本文和对应标准规范；不能只满足方法签名而忽略行为契约。代码与规范冲突时应修正实现，设计变更则同步修订两层规范。下文明确标注的待实现、未验证能力不代表已完成。

状态：V1 Draft，当前 game-network 实现基线。通用语义单一来源：[Network Specification](OGBS-Network-1.0.zh-CN.md)。

Maven：`cn.managame:game-network:1.0.0-SNAPSHOT`。JDK 25，无 preview；Netty 版本由根 POM BOM 管理，当前 4.1.135.Final。接口与 Netty 实现同一 artifact，不依赖 game-core。

## 1. 包结构

本绑定是 Netty 的薄封装。扩展直接使用原生 pipeline handler，ConnectionHandler 接收解码后的消息和生命周期事件；不为了关闭管理增加入口持有的连接清单或批量连接管理。下文关闭契约明确承接这一所有权边界。

| 包 | 公开 API |
| --- | --- |
| cn.managame.network.connection | Connection、ConnectionHandler、WriteStatus |
| cn.managame.network.connector | ConnectCallback、WebSocketConnectOptions |
| cn.managame.network.error | NetworkException |
| cn.managame.network.netty | NetworkServer、NetworkServerBuilder、NetworkClient、NetworkClientBuilder |
| cn.managame.network.http | HttpServer、HttpServerBuilder、HttpResponseCallback |

NettyConnection、NetworkChannelInitializer、WebSocketTransport、ConnectionHandlerAdapter 与 WS payload 适配均保持 netty 包级封装。Server/Client 入口与其实现同包，避免为拆包公开内部协作类型。

内部 HTTP 保留在 game-network artifact，但使用独立的 cn.managame.network.http 包。HttpServer 自行管理 ServerBootstrap、监听、资源生命周期与请求管线，不包装 NetworkServer，不使用 Connection/ConnectionHandler。HttpServerTransport 保持包级封装；TCP/WS 类与其 Upgrade codec 不变。业务 handler 由应用负责，可运行 HTTP 示例位于 game-demo 的 cn.managame.demo.examples.network。不需要新增 Maven 模块或依赖。

不再建立 attribute 包或自定义 ConnectionKey，直接使用 Netty AttributeKey。原 Acceptor/Connector 抽象由具体 NetworkServer/NetworkClient 替代。模块入口见 [game-network](../../game-network/README.zh-CN.md)。可运行示例及其执行测试位于 [game-demo](../../game-demo/README.zh-CN.md) 的 cn.managame.demo.examples.network 包中，不随 game-network artifact 发布。示例调用方需要更新 import 与模块依赖，不保留旧包别名。组件契约测试仍位于 game-network。

<a id="native-http-server-api"></a>

### 1.1 独立 HTTP/1.1 服务端

实现 [N-HTTP-01–08](OGBS-Network-1.0.zh-CN.md#http-server-profile)。以下 API 已提供，不新增框架 HTTP 客户端或 HTTP/2 API。直接使用原生 FullHttpRequest/FullHttpResponse，避免重复消息模型；§§2–8 的 TCP/WS API 保持独立。

| 入口 | 签名或设置 | 契约 |
| --- | --- | --- |
| HttpServer | static HttpServerBuilder builder(); void start(); SocketAddress localAddress(); void close() | AutoCloseable；同步、一次性监听；绑定尝试前 localAddress 为 null |
| 必需配置 | bindAddress(SocketAddress) | 必需的监听地址 |
| 可选兜底 | handler(Function<FullHttpRequest, FullHttpResponse>) | 对扩展传下来的请求同步返回一个完整最终响应；默认空 404 |
| 异步兜底 | asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>) | 接收请求专属回调，可从任意线程完成；最后一次 handler/asyncHandler 设置替换前者 |
| 完成回调 | HttpResponseCallback: boolean onResponse(FullHttpResponse); boolean onFail(Throwable) | 线程安全、仅一个获胜者；每次非 null 响应提交消费一个独立拥有的引用，包括重复/晚到完成 |
| 原生管线 | pipeline(Consumer<ChannelPipeline>) | 每条 Channel 按注册顺序执行配置器，位于聚合之后、兜底之前；addLast 安装 HTTP handler，addFirst 安装调用方创建的 TLS |
| 请求限制 | maxContentLength(int); maxInitialLineLength(int); maxHeaderSize(int) | 正数字节数；默认 body 1 MiB、首行 4096 字节、请求头 8192 字节 |
| 入站时间 | readTimeoutMillis(long) | 默认 30,000 ms；零禁用，负数拒绝；持续无入站字节则关闭 socket |
| 业务执行 | executorGroup(EventExecutorGroup) | 可选、借用；每个执行器必须是 OrderedEventExecutor |
| 传输资源 | bossGroup(EventLoopGroup); workerGroup(EventLoopGroup); channelFactory(ChannelFactory<? extends ServerChannel>) | 默认 NIO，自有 boss 为一个 loop，自有 worker 使用 Netty 默认大小；注入 group 均借用 |
| 原生选项 | <T> option(ChannelOption<T>, T); <T> childOption(ChannelOption<T>, T) | 使用 Netty 校验；原生传输需要匹配的 group/factory |
| 构建 | HttpServer build() | 上述 fluent 方法均返回 HttpServerBuilder；不可变配置快照，start 前不启动默认 group |

null 参数抛 NullPointerException；非法限制或无序执行器抛 IllegalArgumentException。缺少必需字段、重复 start 或 close 后 start 抛 IllegalStateException。绑定失败包装为 NetworkException 并保留原因；尝试完成清理并保留中断。管线初始化失败关闭当前接入 Channel，由 Netty 诊断，不使已绑定监听失效。Builder 是可变装配对象，不支持并发使用；函数、配置器、context 与 group 对象在快照间共享引用。

管线：可选调用方 SslHandler 在首位 → http-read-timeout（启用时）→ http-codec → http-validation → http-keep-alive → http-aggregation → 用户 HTTP handler → http-application 兜底。配置器能看到已装配的 HTTP 基础管线，使用 addLast 追加 handler；配置完成前尚无 http-application context。TLS/原始字节 handler 仍可通过 addFirst 添加。http- 名称保留给框架，扩展不得移除或重排核心 handler。HttpDecoderConfig 提供首行/请求头限制；HttpObjectAggregator 子类处理 body 上限/Expect；原生 HttpServerKeepAliveHandler 对所有响应实施考虑分帧的持久连接策略，包括扩展响应。HTTP/1.0 与 HTTP/2 返回 505，CONNECT 返回 405，Upgrade 返回 400。非法解码/Host/首行/请求头返回 400；body 超限返回 413，不支持的 Expect 返回 417。空协议错误响应后关闭，不增加 HTTP/2 协商或共享 WS 管线。

默认 HTTP 处理及 addLast 扩展运行在 Channel EventLoop。executorGroup 将 HTTP 基础管线与兜底分配到每条连接的同一有序执行器。启用投递时，http-codec 之后的扩展必须使用相同 group，例如 pipeline(p -> p.addLast(group, "auth", new AuthHandler()))；不指定 group 会切回 EventLoop，破坏顺序协调。初始化时拒绝 http-codec 之后不同的 context.executor()；build 拒绝 childOption(SINGLE_EVENTEXECUTOR_PER_GROUP, false)。http-codec 之前的 TLS/原始字节 handler 可运行在 EventLoop。共享函数需要支持跨连接并发，非 Sharable 原生 handler 在每次配置器调用中创建。应用选择有界执行资源并负责关闭，不隐式创建业务执行器/定时器。异步兜底通过下述显式回调完成，不引入 CompletionStage、Future 或流式响应 API。

原生扩展所有权遵循 N-HTTP-08：转发型 ChannelInboundHandlerAdapter 调用 ctx.fireChannelRead(request)，不释放已转移引用；消费型 adapter 用完后自行释放。SimpleChannelInboundHandler 自动释放，使用它继续转发时需要 retain。原生响应器通过 ctx.writeAndFlush(FullHttpResponse) 写回，自行设置合法 Content-Length 或原生传输分帧，不再产生第二个兜底响应。KeepAlive 策略遵循请求/响应关闭要求，决定关闭后抑制后续流水线请求。框架不再释放已被扩展消费的请求。原生 CorsHandler 可以消费 OPTIONS 并生成预检响应；HttpContentCompressor 可变换兜底/原生输出并调整 wire 分帧。鉴权、路由、CORS 与压缩配置仍属于应用策略。

兜底函数路径中的请求借用至函数返回。例如 echo 应返回 new DefaultFullHttpResponse(HTTP_1_1, OK, request.content().retainedDuplicate())；不 retain 就返回共享内容，会在请求自动释放时使发送引用失效。返回响应的所有权始终转交服务端，包括响应校验失败或处理期间断开。服务端选择 HTTP/1.1，移除响应 Transfer-Encoding/trailer，普通 Content-Length 根据实际 body 计算；HEAD/304 保留显式非负长度并抑制发送 body，204 去掉长度/body，205 长度为零。请求或响应包含 Connection: close 时，写回后结束连接并禁止执行后续流水线业务。发送失败关闭，不重试。handler 抛异常、返回 null/1xx 或响应分帧失败时，尝试空 500 并在 ERROR 记录原始应用异常，不发送异常详情。可识别的 I/O、解码边界与读超时异常仅产生 DEBUG 连接摘要；未知管线异常仍为 ERROR。

ReadTimeoutHandler 度量入站无数据时间，包括空闲 Keep-Alive 和部分 body，不限制 handler 或请求总时长。断开不回滚或中断 handler。start/close 禁止从关联 boss/worker/HTTP 执行器调用。close 将入口标记为终止，关闭监听，并仅对自有 boss/worker 调用 shutdownGracefully(0, 5 seconds)。不使用 ChannelGroup 或连接注册表，不关闭注入的 HTTP 执行器，不等待外部业务。借用 worker 时，已有 socket 可在监听关闭后继续服务至自身超时/关闭，应用通过自有原生资源管理。重复 close 无效果，不构成并发清理屏障。

已确认取舍：普通内部接口先支持 HTTP/1.1，采用独立实现，避免给 NetworkServer/ConnectionHandler 增加 HTTP 分支。明确接入要求或实测连接/响应顺序瓶颈出现时再评估 HTTP/2，单凭高 QPS 不足以判断。body 上限不限制执行队列、连接数量或响应缓冲，不宣称生产容量。内建路由、JSON、压缩、multipart 与 CORS 不在初版实现中。

源码：[HttpServer](../../game-network/src/main/java/cn/managame/network/http/HttpServer.java)、[Builder](../../game-network/src/main/java/cn/managame/network/http/HttpServerBuilder.java)、[Transport](../../game-network/src/main/java/cn/managame/network/http/HttpServerTransport.java)。验证：[HttpServerTest](../../game-network/src/test/java/cn/managame/network/http/HttpServerTest.java)、[HttpServerExample](../../game-demo/src/main/java/cn/managame/demo/examples/network/HttpServerExample.java)、[示例测试](../../game-demo/src/test/java/cn/managame/demo/examples/network/HttpServerExampleTest.java)。测试覆盖真实 socket、chunked 入站、持久/流水线响应边界、HEAD/204/205/304、原生 TLS/明文拒绝、拒绝、执行器投递时的 100/413 顺序、所有权、handler 失败、无数据超时、资源生命周期、原生路由/鉴权拒绝/默认 404、CORS 预检、gzip 变换和扩展执行器一致性。生产容量仍未验证；根 clean verify 已通过并执行 HTTP/RPC 示例。

<a id="http-async-response"></a>

### 1.2 异步响应回调

`asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>)` 替换同步兜底设置；随后调用 `handler(...)` 又替换它。已构建监听保持自身配置快照。每个进入兜底的请求创建一个回调对象，可在 handler 内完成，也可保留给 Route/业务任务。Network 不依赖 Runtime，也不选择 Domain/RouteKey。[HttpResponseCallback](../../game-network/src/main/java/cn/managame/network/http/HttpResponseCallback.java) 仅公开：

```java
boolean onResponse(FullHttpResponse response);
boolean onFail(Throwable cause);
```

两个方法均线程安全。第一个有效调用返回 true 并认领唯一完成，即使 Channel 已失活或随后投递失败。true 不表示写入接纳或送达。后续调用返回 false；每次非 null 的 onResponse 消费一个独立拥有的引用，重复或无法交付的响应释放。已经转交的引用不得再次使用，除非事前另行 retain。null response/cause 抛 NullPointerException，不认领完成。onFail 在仍可交付时尝试空 500 并关闭，记录原因但不向对端暴露。handler 抛异常走 onFail；完成后再抛异常仅记录，不产生第二响应。获胜的非法响应（包括 1xx 或非法 HEAD/304 长度）释放后尝试空 500/关闭，保持已有同步规范化。同步 handler 返回 null 仍走原有 500/关闭路径。

请求仍只借用至 handler 的初次调用返回。回调保存 HEAD/持久连接标记和连接状态，不保存请求。投递 Route 前应解码不可变输入，或 retain/复制需要的 buffer，并在拒绝及使用结束后释放应用拥有的引用。完成响应不是继续访问请求数据的授权。返回而未完成回调会使该连接等待；应用应最终完成或管理自己的业务期限。Network 不新增自动业务取消或重试。断开/关闭不认领回调，晚到的首次完成仍可返回 true，同时释放无法交付的响应。

完成操作投递至连接的有序 HTTP 执行器；已经位于该执行器时可 inline。执行器拒绝时释放提交响应并请求关闭连接。调用方拥有的 HTTP 执行器应保持可用，直到关联 Channel 完成管线清理。HttpServerTransport 在分发后续请求前观察最终出站 LastHttpContent 的写入完成，包括原生压缩产生的内容和原生应用响应。它在协议校验/聚合之前暂存后续解码输入，使后续请求的自动 100/400/413/417 不会越过待完成响应。在当前 body 结束后临时禁用 AUTO_READ，仅恢复框架自己暂停的读取，关闭时释放已经解码/投递的排队输入。手动读取及执行器队列仍由外部管理，暂存输入没有可配置硬内存上限。原生异步扩展仍自行释放请求，并在有序上下文写入一个合法分帧的响应，不使用兜底回调。

等待期间 readTimeoutMillis 继续生效，包括暂停自动读取时；它衡量入站无数据，不表示业务执行期限或回滚。例如，把回调交给 Route 任务后，30 秒无数据关闭可先于业务完成；稍后的 onResponse 释放响应，不撤销业务。长业务应显式禁用或调整此 I/O 超时。借用 worker group 时，监听关闭继续按 N-HTTP-06 允许已有连接/响应完成。

示例：[HttpAsyncServerExample](../../game-demo/src/main/java/cn/managame/demo/examples/network/HttpAsyncServerExample.java) 在借用期内解码 UTF-8，并从应用拥有的执行器完成回调；[HttpAsyncServerExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/network/HttpAsyncServerExampleTest.java) 验证完整往返。HttpServerTest 还覆盖异步跨连接推进、流水线/自动响应顺序、请求/响应所有权、完成竞争、失败、自有资源关闭后的晚到完成、无数据超时及 Builder 替换。已有 TCP/WS 与同步 HTTP API 保持源码兼容，不变更依赖或 Wire Profile。本次验证：85 项 Network 测试通过，其中 HTTP 25 项；独立 JUnit launcher 执行的两个 HTTP 示例测试通过。根 clean verify 已通过 Core/Network/RPC 及全部后续模块。

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

Connection 的 active/writable、地址直接委托 Channel；地址可能为空。属性用 `channel.attr(key)`，remove 使用 `getAndSet(null)`。同名 AttributeKey 遵守 Netty 语义；允许 set(key, null)，不额外冻结或清空属性。调用方负责属性对象自身的并发安全。

write 要求非 null，依次检查 active、writable，随后执行 `channel.writeAndFlush(message, channel.voidPromise())`。可复用 void promise 将编码/写入失败转为 pipeline exceptionCaught；内部头部错误转发器让已建立连接的发送异常跳过会自动关闭的 WS protocol handler，继续交给用户 pipeline / onException，不创建公共 Future、回调或发送队列。接管发生在提交 Netty write 时；仅 INACTIVE/NOT_WRITABLE 保证调用方仍持有消息，不应假定任意自定义出站 handler 抛异常都能退回所有权。

```java
ByteBuf buffer = ...;
if (connection.write(buffer) != WriteStatus.ACCEPTED) {
    buffer.release();
}
```

所有 ConnectionHandler 回调在 Channel EventLoop 上执行。多个连接可并发使用同一个 handler；不要阻塞 EventLoop。入站 ReferenceCounted 在 onMessage 正常返回或异常后 release。回写同一 ByteBuf 必须先 retain；异步持有同样 retain，并在最终结束时 release。普通对象 release 为 no-op。onEvent 不自动 release。

普通 pipeline/业务异常保留原 Throwable 并进入 onException，不自动关闭。onException 自身异常用 System.Logger 兜底。断开后的晚到异常只诊断；onDisconnected 本身抛异常仍在生命周期结束前进入 onException。

### 2.1 引用计数的逐步解释

假设 onMessage 收到一个 ByteBuf，框架持有的借用份额为 1。回写原缓冲区时，应用先 retain，得到发送份额；write 返回 ACCEPTED 后，该发送份额归 Netty。onMessage 返回时框架释放入站份额，之后 Netty 完成发送或失败时处理发送份额。

若 write 返回 INACTIVE/NOT_WRITABLE，应用应立即释放额外取得的发送份额；框架仍在 onMessage 结束时释放原借用份额。不能在回调内主动释放原借用份额再让 adapter 释放一次。

异步处理也遵循相同原则：retain 后才能交出回调作用域；异步任务提交失败时释放新增份额，任务成功接纳后由最终处理方释放。这里说明的是使用约定；Network 不创建异步任务队列，也不知道上层任务是否接纳。

### 2.2 属性与回调状态

AttributeKey<T> 直接使用 Netty 的键语义，同名键不应被不同业务模块解释为不同类型。set/remove 改变属性槽，不自动释放槽中对象，也不使对象线程安全。连接关闭后框架不清空属性，业务清理可放在 onDisconnected。

同一 Connection 的 Handler 回调在其 EventLoop 串行执行，但同一个 ConnectionHandler 实例可以接收来自多个 EventLoop 的调用。实例中“当前玩家”“最后消息”等无连接区分的可变字段会产生串扰；连接相关状态应明确存放在属性或上层映射中。


## 3. Server

```java
public final class NetworkServer implements AutoCloseable {
    public static NetworkServerBuilder builder();
    public void start();
    public SocketAddress localAddress();
    public void close();
}
```

start 同步等待 Netty bind。重复 start、关闭后 start 为 IllegalStateException。bind 失败为 NetworkException，实例关闭且回收自有资源。localAddress 在 start 前为 null；端口 0 在启动成功后可读取实际端口。

Builder 的全部配置入口：

| 方法 | 默认 / 含义 |
| --- | --- |
| bindAddress(SocketAddress) | 必填 |
| handler(ConnectionHandler) | 必填 |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | 可多次追加 |
| webSocket(String path) | 切换 WS，默认最大消息 1 MiB |
| webSocket(String path, int maxMessageSize) | 指定正数最大消息长度 |
| bossGroup(EventLoopGroup) | 缺省创建 NioEventLoopGroup(1) |
| workerGroup(EventLoopGroup) | 缺省创建标准 NioEventLoopGroup() |
| channelFactory(ChannelFactory&lt;? extends ServerChannel&gt;) | NioServerSocketChannel::new |
| option(ChannelOption&lt;T&gt;, T) | ServerBootstrap parent option |
| childOption(ChannelOption&lt;T&gt;, T) | ServerBootstrap child option |
| build() | 校验并快照；不监听、不启动默认 group |

Server WS path 必须是以 / 开头的单一路径，禁止 authority、scheme、query、fragment、wildcard；不自动 normalization。请求 query 保留给 HTTP 握手 handler，但不参与 path 匹配。不同路径以 HTTP 404 关闭。

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

一个 Client 可连接多个目标或同一目标多次。同步/异步共用原生 Promise 建连流程；成功认领发生在交付 onConnected 前，最终成功回调在 onConnected 后。callback 自身抛异常只记录日志，不转成第二次结果或 ConnectionHandler.onException。

同步 connect 禁止从该 Client 的任意 EventLoop 调用，立即 IllegalStateException。等待线程被中断时恢复 interrupt flag，取消尝试并关闭底层 Channel，抛 NetworkException；即使成功刚好赢得竞争，也关闭同步调用方无法取得的连接。

有效尝试的异步 callback 在选定的 Netty EventLoop 上执行。入参非法、已关闭等发起前拒绝在调用线程直接 onFailure；如果外部 EventLoop 已终止且拒绝回调任务，也在当前线程兜底通知一次，避免永久无结果。null callback 直接 NullPointerException。

| Client Builder 方法 | 默认 / 含义 |
| --- | --- |
| handler(ConnectionHandler) | 必填 |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | 按注册顺序追加 |
| webSocket() / webSocket(int maxMessageSize) | WS 模式；默认 1 MiB |
| eventLoopGroup(EventLoopGroup) | 缺省创建标准 NioEventLoopGroup() |
| channelFactory(ChannelFactory&lt;? extends Channel&gt;) | NioSocketChannel::new |
| option(ChannelOption&lt;T&gt;, T) | 原生 Bootstrap option |
| build() | 快照并创建独立 Client |

TCP 模式只接受 SocketAddress；WS 模式只接受 URI，错用为 IllegalStateException。URI 必须有有效 host，scheme 为 ws/wss，端口缺省分别 80/443；拒绝 user-info、fragment、0 或超范围端口。

TLS 只通过原生 pipeline handler 配置；不再提供 sslContext(...) Builder 方法，也不自动创建客户端 context。全部 pipeline configurer 执行完毕后，允许存在一个 SslHandler，且必须位于首位。wss:// 必须配置它；ws:// 若配置它则拒绝，不再静默忽略 TLS。TCP 存在该 handler 时使用 TLS。信任库、证书、客户端/服务端模式、目标 host/port、主机名校验及握手超时均由调用方配置，见[原生 TLS 配置](#native-tls-configuration)。

<a id="41-connectattempt-的完成协议"></a>

### 4.1 原生建连结果

NetworkClient 每次调用使用一个原生 Netty Promise<Connection> 保存结果、接受取消并提供同步 await。Bootstrap 和 Channel 在选定的 EventLoop 上创建，初始化、就绪与失败处理按该线程串行执行。自定义 channelFactory 因此在该 EventLoop 上运行，不得阻塞它。不再定义结果包装类、额外完成 CAS、取消标记或附着 Channel 字段。

Promise 使用 ImmediateEventExecutor，保证 Channel 执行器终止后仍执行资源回收和通知 listener，不创建工作线程。结果 listener 显式将 ConnectCallback 派发到选定 EventLoop，派发被拒绝时在当前线程兜底。若仅使用绑定 Channel 执行器的 Promise，其 listener 执行被拒绝时可能丢失这些通知。

```text
创建独立 Promise（不登记到端点集合）
→ 在选定 EventLoop 执行 Bootstrap
→ Transport 完成，且应用 pipeline 已装配
→ 检查结果未完成且 Client/Channel 仍开放
→ 通过 Promise.setUncancellable() 认领交付
→ 创建 Connection，执行 onConnected
→ 存入成功值，唤醒同步等待者/调用 onSuccess
```

失败使用 Promise.tryFailure，中断等待使用 Promise.cancel(false)。结果 listener 在失败/取消时关闭 Bootstrap 的 Channel；若取消后才创建 Channel，向已完成 Promise 添加 listener 仍会关闭它，初始化也会在业务交付前拒绝已完成结果。不需要端点登记/移除回调。

先检查 isDone，再通过 setUncancellable 与等待线程的取消竞争。它不是通用成功/失败锁：Netty 仍允许对不可取消 Promise 调用 tryFailure。初始化、Channel 就绪和传输失败处理在 EventLoop 上串行执行，其他线程不得独立将已认领成功的结果改成失败。先执行 onConnected，再 setSuccess；前者抛异常只走 ConnectionHandler.onException，在其中关闭也不会触发 ConnectCallback.onFailure。

### 4.2 中断和回调线程例外

同步 await 被中断时先取消 Promise。取消获胜后，资源回收 listener 关闭底层 Channel，包括取消后才创建的 Channel。若成功已认领，取消不能获胜；另一个 listener 在 onConnected 返回后关闭成功 Connection。例如 onConnected 阻塞期间中断等待线程，该线程立即恢复中断标记并抛 NetworkException，回调放行后再关闭无法交付给调用者的 Connection，不制造第二次结果。

正常异步结果由尝试选择的 EventLoop 交付；发起前校验失败则直接在调用线程 onFailure。借用的 EventLoop 若已终止且拒绝结果通知，只能在当前线程兜底一次。因此上层不能要求 onFailure 永远具有 Channel EventLoop 上下文，更不能在回调内无条件调用同步 connect/close。

ConnectCallback 自身异常只做诊断，不转交 ConnectionHandler.onException：前者属于建连调用者的结果处理，后者属于已经建立的 Connection 生命周期。


## 5. 配置快照与握手参数

Builder 可重复 build，每个实例快照自己的 handler、pipeline 列表、options、Transport 参数。未提供外部 group 时分别创建资源；传入 group 时显式共享。快照不深复制用户的 handler 或 pipeline lambda 内部状态。Builder 本身不是并发配置器。

handler/pipeline/group/factory/option/value 等 null 参数立即拒绝。build 缺必填项为 IllegalStateException；非法 path、非正消息长度为 IllegalArgumentException。

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

构造时复制 HttpHeaders，headers() 返回新的副本。默认空 headers、null subprotocol；可重复安全用于不同 connect。服务端不协商子协议，也不提供子协议 Builder 配置；客户端保留每次 connect 的 WebSocketConnectOptions，以便连接外部 WS 服务。客户端要求子协议但服务端没有匹配响应时握手失败；服务端可能已经完成自己的 Upgrade，随后收到客户端关闭，两端结果不是一个事务。原生 handler 可通过 pipeline(...) 检查 HTTP Upgrade；WS Builder 不增加 Auth/Router/HTTP 服务 DSL，独立 HTTP 入口见 §1.1。

### 5.1 快照的深度和可复用范围

Builder build 后再修改 options 或添加 pipeline，只影响下次 build。它不会改动已经建好的 Server/Client 配置。handler 和 lambda 捕获对象仍为共享引用，因此“配置快照”不意味着复制它们内部的可变状态。

每条 Channel 调用各 pipeline configurer 来创建自己的编解码器。不要在 Builder 外只创建一个非 Sharable decoder，然后重复 addLast 到所有连接；应在 configurer 内 new。当业务需要共享 handler 时，由应用确认其 @Sharable 和并发安全性。

WebSocketConnectOptions 另行复制 Headers：构造后改变原 Headers，以及改变 headers() 返回值，都不会修改选项本体。这确保同一个 options 可以用于多个独立尝试；但不意味着 Server 自动理解这些 Header 或进行鉴权。


WebSocket 握手超时直接采用 Netty 原生默认值，不提供独立 Builder 配置或框架默认常量；TLS 超时仍通过原生 SslHandler 配置。早期增加的服务端子协议及两端自定义 WS 超时入口已移除，调用方需删除相应 Builder 调用并重新编译。

## 6. Pipeline 与超时

```text
TCP: [调用方 SslHandler] → 用户 pipeline → ConnectionHandler adapter
WS:  [调用方 SslHandler] → HTTP codec / HTTP aggregator / WS protocol / frame aggregator
     → WS handshake observer → binary decoder/encoder → 用户 pipeline → ConnectionHandler adapter
```

每条 Channel 重新执行 configurer；多次 pipeline(a).pipeline(b) 按 a、b 执行。用户 decoder/encoder 应各自创建非 Sharable 实例。出站逆序经过用户 encoder，再由 ByteBuf 转 BinaryWebSocketFrame。入站聚合后 content.retain，原 frame 由 Netty decoder 释放。

WS observer 管理服务端建立期限，向后传播原生握手成功事件。末端 ConnectionHandlerAdapter 直接处理 TLS/WS 完成事件并交付业务回调，不再暴露中间就绪 Future。临时 closeFuture 监听器负责交付前关闭，也覆盖 inactive 尚未到达末端的情况。TCP 字节流不内置业务 framing。用户 IdleStateHandler 的事件经 onEvent 交付，不重新定义 Idle 类型。自定义异步 handler 必须自行维护事件传播及消息顺序，不得删除/重排内部 `network-*` handler 或伪造生命周期/握手事件。

内部 handler 名称使用组件职责前缀 network-，不绑定项目名或 Java 包名。这些名称留给框架 handler；用户 handler 必须使用不同名称，否则 Netty 拒绝重名。该前缀替代旧 managame-，不保留别名；按名称使用 addBefore/addAfter/get 的调用方需要迁移。handler 顺序、事件与 wire 行为不变。内部名称支持原生插入，例如在 `network-http-aggregate` 后添加 HTTP Upgrade 校验。TLS handler 名称由调用方指定：初始化时使用 `pipeline.addFirst("ssl", ...)`；装配方法识别该 SslHandler 并等待其 handshakeFuture。TLS 握手事件不交给 ConnectionHandler.onEvent。

| 参数 | 当前默认与入口 |
| --- | --- |
| TCP connect | Netty CONNECT_TIMEOUT_MILLIS，默认 30 秒；option 设置 |
| TLS handshake | SslHandler 默认 10 秒；通过 pipeline 中原生 handler 设置 |
| WS client handshake | Netty WebSocketClientProtocolConfig 默认值，当前 10 秒；channelActive 发起握手时启动计时，包含等待 TLS 完成的耗时 |
| WS server establishment | 读取 Netty WebSocketServerProtocolConfig 默认期限，当前 10 秒；从 channelActive 起覆盖静默对端、TLS 与 Upgrade，成功/关闭取消定时任务 |
| HTTP Upgrade body | HttpObjectAggregator 64 KiB，独立于业务消息 |
| Binary WebSocket message | 1 MiB，同时约束 frame payload 和聚合长度；Builder 可调整 |
| NIO / socket options | Netty 默认值；原生 option / childOption |

Text 用关闭码 1003 拒绝。单帧/聚合超限和 WS 协议违规关闭，不进入 onMessage。Ping/Pong/Close 使用 Netty protocol handler。Server 初始化某一连接 pipeline 失败仅诊断并关闭该 Channel；Client 则本次建连失败。

### 6.1 装配时点与事件方向

NetworkChannelInitializer.configure 添加发送异常入口、可选 WebSocket 协议及 payload handler，然后执行用户 configurer。configurer 将 SslHandler 添加到首位，排在所有已有 handler 之前。装配方法校验 TLS 位置和 WS URI scheme，添加末端 adapter，向 adapter 传入已配置的 SslHandler 和 WS 模式，最后标记初始化完成。交付要求装配成功、激活事件到达末端、全部已配置握手完成以及端点接纳成功。构造 handler 的先后不决定字节处理顺序，最终 pipeline 位置才决定。

入站事件大体从头到尾传播，出站写入从尾到头传播。以下列出主要内部顺序，方括号表示可选：

```text
[调用方 SslHandler]
→ network-write-errors
→ [network-http → network-http-aggregate
   → 服务端 network-websocket-path → network-websocket
   → network-websocket-aggregate → network-websocket-handshake]
→ [network-binary-in → network-binary-out]
→ 用户编解码/事件 handler
→ network-connection
```

WS 出站用户 encoder 先把业务对象变成 ByteBuf，binary-out 再包装为 BinaryWebSocketFrame，最后经过 WS/TLS 编码。TCP 不插入 binary 适配，也不提供业务消息长度头。

用户 configurer 若通过 addBefore/addAfter 插入原生 handler，应保持内部握手、生命周期和释放逻辑有效。吞掉 handshake/inactive 事件或删除内部 handler 会破坏前置条件；这不属于框架支持的任意流水线改写。

<a id="62-为什么区分生命周期-gate-与末端-adapter"></a>

### 6.2 内部职责与扩展边界

Netty 负责 TLS/WS 协议，Adapter 直接衔接完成事件与业务交付。不定义通用 Transport、独立建立协议或生命周期协调对象。

| 内部组件 | 职责 |
| --- | --- |
| NetworkServer / NetworkClient | 配置监听/建连、自有 EventLoopGroup，并提供入口开放检查；Client 另持有每次调用的原生结果 Promise，不登记连接或尝试集合 |
| [NetworkChannelInitializer](../../game-network/src/main/java/cn/managame/network/netty/NetworkChannelInitializer.java) | 装配 handler/configurer、校验 TLS 位置和 URI scheme，将 TLS 引用与 WS 模式交给末端 adapter |
| [WebSocketTransport](../../game-network/src/main/java/cn/managame/network/netty/WebSocketTransport.java) | 装配二进制 WS 协议及 payload handler；处理建立期限和协议拒绝，向后传播原生成功事件 |
| [ConnectionHandlerAdapter](../../game-network/src/main/java/cn/managame/network/netty/ConnectionHandlerAdapter.java) | 在 EventLoop 上直接检查交付条件、创建 Connection、调用业务 handler 并完成客户端结果；负责入站引用释放与异常隔离 |

TCP 在装配完成且 channelActive 到达末端后交付。TLS 使用原生 SslHandshakeCompletionEvent 触发检查，并检查 SslHandler.handshakeFuture 的成功状态。WS 使用原生 WebSocket 握手成功事件；WSS 同时要求 WS 成功和原生 TLS 成功。四种情况均不创建中间 ready Promise、WS 完成 Promise 或 PromiseCombiner。客户端只保留返回建连结果所需的 Promise<Connection>；服务端无需创建结果 Promise。

Adapter 通过入口提供的 BooleanSupplier 检查开放状态，客户端再用结果 Promise.setUncancellable 与等待线程的取消竞争。随后在同一事件处理调用中创建 Connection、执行 onConnected，最后完成客户端成功结果；不存在“就绪 Future 成功后再排队创建 Connection”的步骤。TLS/WS 握手事件在末端消费，不交给 ConnectionHandler.onEvent。用户原生 handler 可以观察这些事件，但必须按顺序继续传播，不得吞掉。

临时 closeFuture listener 在业务交付或建立失败时移除；它只负责建立前资源关闭，包括 adapter 尚未添加的阶段。建立失败关闭当前 Channel，客户端收到一次失败，服务端记录诊断，不触发业务生命周期。业务交付后仅由末端 channelInactive 通知断开。即使原生协议已经完成，初始化失败仍不能交付业务 Connection。

例如 Netty 为限制递归而延后 Promise listener 时，握手成功事件紧接第一条二进制消息仍必须按 onConnected → onMessage 交付。直接事件衔接消除了这个中间通知窗口，无需缓存消息或新增连接集合。验证入口为 ConnectionSetupTest.nestedHandshakeNotificationDeliversFirstMessageBeforeReturning；真实 TCP/TLS/WS/WSS 两端在 onConnected 内立即发送消息由 NetworkContractTest.firstMessagesCanBeSentInsideOnConnected 验证。

TLS/WS 协议完全由 Netty 实现，这些完成通知不包含 RPC、登录或业务协商。两端不覆盖 Netty 的 WS 握手超时，当前原生默认值为 10 秒。服务端从原生配置读取相同期限，自 channelActive 起限制静默对端，包括 TLS 建立耗时；收到 Upgrade 不重置总期限。客户端使用 Netty 原生 WS 握手定时器。超时是未成功握手的等待上限，正常握手完成立即交付 onConnected。TCP connect 与两端 TLS 超时独立，先失败或到期者结束尝试。定时任务依赖 EventLoop 执行，不是阻塞线程下的精确墙钟上限；超时/失败关闭 Channel。

不使用 HashSet、ChannelGroup、共享 admission 锁或跨连接取消清单。单连接处理留在其 EventLoop；原生 Promise 保存各自的建连结果。Server 仅在管理操作 start/close 同步，Client 用 CAS 保证资源只关闭一次；这些管理机制不参与消息交付或连接登记。该变化删除全局登记开销，不代表已经测得吞吐提升。

末端 adapter 先接收用户 codec 输出，再接收 channelInactive，随后只调用一次 onDisconnected。因此 EOF 尾帧可以在 Connection.isActive 已为 false 时进入 onMessage，回调抛异常仍释放借用引用。有序异步 handler 必须先传播读取/错误，再传播 inactive，Netty 在 Channel EventLoop 上调度 adapter。不使用定时器绕过顺序；吞掉 inactive 可能导致通知无法到达。Netty 在 inactive 之后才发出的 decodeLast 异常，继续按现有生命周期结束规则仅做诊断。

不提供通用 Transport 接口或 TLS 包装。WebSocket 保留具体装配，因为二进制消息 Profile 需要 HTTP Upgrade、帧聚合和 payload 适配。SslHandler 是唯一自动识别为建立前置条件的用户添加 handler；任意业务握手不会自动加入就绪判断。动态插入 TLS、之后才安装 SslHandler 的 SNI handler、STARTTLS 和多层嵌套 TLS 不属于当前初始化契约，需单独设计后才能声明支持。

### 6.3 写入错误的路由

当前实现使用 voidPromise 避免每次发送暴露或维护完成 Future。void-promise 的失败可能从 pipeline 头部发出；若直接穿过 WS protocol handler，普通发送错误可能被其默认逻辑关闭连接。

因此 network-write-errors 在业务连接交付后，使用装配时保存的最后一个协议 ChannelHandlerContext，将错误从协议 handler 之后转发，经 payload/用户 codec 到达末端 adapter。保留用户 pipeline 和 onException 的处理机会，不按 handler 名称查找，也不依赖另一个生命周期控制器。它不是吞掉错误，也不取消 WS 对非法入站帧的关闭规则。建立前错误仍按握手失败处理。

原生自定义 handler 自己关闭 Channel 的行为不受“Network 普通异常不自动关闭”限制。接入者必须审视自己的 exceptionCaught；框架无法撤销用户 handler 已主动发起的关闭。



<a id="native-tls-configuration"></a>

### 6.4 原生 TLS 配置

每个 Channel 在初始化结束前配置一个新的 SslHandler，放在首位，保证入站先解密再经过 HTTP/WS/业务解码，出站先编码再加密。TCP 激活可能早于 TLS 完成，不代表连接成功。Netty 可以在 TLS 完成前排队等待发送上层握手相关数据，但不能将其以明文发送到网络。

以下 Builder 片段假定调用方已创建 serverContext、clientContext 和 ConnectionHandler handler。客户端捕获目标地址，因为 Channel 初始化期间 remoteAddress 可能仍为 null。一个 Builder 用于多个目标时，配置策略需要为每条连接提供正确的对端身份。

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

SslContext/SslHandler、InetSocketAddress、NetworkServer/NetworkClient 分别为 Netty、JDK、game-network 原生类型。调用方负责使用适当密钥和信任配置创建 context；创建客户端 handler 时传入 host 不能代替显式配置主机名校验。框架不修改信任设置或 engine 参数。服务端追加 webSocket("/game")、客户端追加 webSocket() 并连接 wss://localhost:8443/game 即为 WSS；两端仍需显式添加 TLS。

wss:// 缺少 handler、ws:// 存在 TLS、多个 SslHandler 或 SslHandler 位于其他 handler 之后，都属于 pipeline 配置错误。客户端报告 NetworkException，cause 保留 IllegalArgumentException；服务端诊断并关闭受影响的接入 Channel。握手失败/超时不创建 Connection，不产生业务 onException/onDisconnected。自建 group 关闭会结束未完成握手；借用 group 的握手继续到后续结果或超时，届时若入口已关闭则拒绝交付。验证入口为 NativeTlsTest 与 NetworkContractTest.roundTripAndOrderedWrites。

这替代原 sslContext(...) 和隐式 WSS context 创建行为。调用该方法的代码需要迁移到显式 pipeline 配置，属于源码不兼容变更；握手成功后才触发 onConnected 的契约不变。

## 7. 资源与关闭

Server 标记关闭、关闭监听 Channel，再 shutdown 自建 boss/worker；Client 标记关闭，再 shutdown 自建 group。两者均不遍历连接或尝试。自建 group 关闭自然会关闭关联 Channel；借用 group 时，已有 Channel 由调用方管理，未完成操作在后续结果、原生超时或 Channel 关闭时结束。

外部 group、SslContext 始终由调用方管理；不自动检测 Epoll/KQueue。应用选择 native transport 时须同时提供匹配 group 与 channelFactory。

start/close 是同步基础设施操作，禁止从相关 boss/worker/client EventLoop 调用，避免等待自身死锁。重复 close 是 no-op；并发重复 close 不作为另一调用已完成资源回收的屏障。清理使用 Netty shutdownGracefully(0, 5 秒)，尽力回收全部自有资源；中断标记保留，清理完成后报告 NetworkException。

Connection.close 始终非阻塞，可在任何回调中调用。它不是 Server/Client 的同步 close。

### 7.1 默认资源与注入资源

| 装配方式 | 资源生命周期 | 应用责任 |
| --- | --- | --- |
| 完全使用默认 NIO group | 各 Server/Client 拥有自己的资源 | 关闭对应入口 |
| 注入外部 group | 框架借用，不 shutdown | 关闭成功连接，再在合适时机关闭 group |
| Server 只注入 boss 或 worker | 分别按来源管理 | 只负责注入部分；框架回收自建部分 |
| native transport | 不自动选择 | 提供匹配的 group、channelFactory 和依赖 |

Server.build 不开始监听，也不启动默认 group；start 才开始同步建立监听。Client 不提供额外 start 方法。重复 build 的实例独立持有配置快照，只有显式注入的对象会共享。

同步基础设施操作必须在非相关 EventLoop 线程执行。Connection.close 则可以在 onConnected/onMessage/onException 中使用。不能为了方便把 Server.close 放进它自己的 onDisconnected 等回调中，它可能等待当前 EventLoop 退出。

### 7.2 关闭与成功交付的交叉

就绪连接的入口开放检查是接纳点。先观察到 close 时，Server 拒绝该 Channel，Client 报告 IllegalStateException；已经通过检查的交付允许与 close 并发完成，客户端仍以原生 Promise 与中断/传输失败仲裁。自建 group 关闭可能令已成功交付的 Connection 失效，此时按正常断开流程处理。

外部 group 下，close 不等待或取消握手。例如，静默 TLS 对端握手期间 Client.close，Channel 仍保持到原生握手超时或调用方主动关闭；随后回调观察到 closed，只报告一次失败。不要禁用原生超时后又依赖入口 close 取消尝试。关闭前已认领的传输失败仍可报告 NetworkException；失败路径观察到 closed 时报告 IllegalStateException。需要批量关闭的业务自行管理连接和资源。


## 8. 失败与兼容性

| 场景 | 表达 |
| --- | --- |
| 非法参数、缺必填项、生命周期误用 | 上述 Java 参数/状态异常；异步入口转 onFailure（null callback 除外） |
| bind/connect/TLS/WS/pipeline 初始化失败 | NetworkException，保留 cause |
| 就绪或建连前检查观察到 Client 已关闭 | IllegalStateException；借用 group 上已开始的操作不保证立即通知 |
| 建立后 Decoder/I/O/Handler 异常 | 原始 Throwable → onException |
| onException / ConnectCallback 自身异常 | System.Logger 最终诊断 |
| INACTIVE / NOT_WRITABLE | WriteStatus，调用方保留所有权 |

旧 ConnectionListener、ConnectionKey、CloseInfo、NettyConnector、NettyAcceptor、tryWrite 与 TestKit 路径已失效，不提供兼容层。game-rpc 已通过 ConnectionHandler/ConnectCallback 接入，并提供真实 TCP 集成测试。

### 8.1 后续实现不应重新引入的隐式行为

新增 codec 不应改变 write 的三态接纳和所有权；新增 Transport 应明确建立成功门槛、超时、消息边界与协议拒绝。新增异步接入不能在 EventLoop 阻塞等待自己；新增属性包装也不能默默改变关闭后的属性语义。

心跳、IdleStateHandler、自定义 HTTP Upgrade 检查和业务认证通过原生 Netty 接入或上层组件组合，当前没有独立框架 DSL。若要增加统一能力，应先说明可观察行为与资源责任，再同步两层 Spec；不能仅以“便于使用”为由默认加入自动重连、自动关闭或业务发送队列。

可运行入口以现有 [NetworkEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/network/NetworkEchoExample.java) 及下述示例测试为准。本章的流程图、引用计数说明和状态表是设计说明，不另行声明为独立可运行程序。


### 8.2 建立失败和晚到异常的日志

NetworkSupport 使用名为 cn.managame.network 的 System.Logger。服务端建立失败以及业务生命周期结束后的晚到传输异常，按以下类型分类；客户端建立失败仍通过建连结果返回，不额外记录重复的框架错误日志。

| 类型 | 诊断 |
| --- | --- |
| ClosedChannelException、SocketException、PrematureChannelClosureException | DEBUG 断开摘要 |
| SSLException、WebSocketHandshakeException、CorruptedFrameException、TooLongFrameException | DEBUG 协议拒绝摘要 |
| 其他异常，包括初始化/配置错误与未知异常 | ERROR，保留原始 Throwable 和堆栈 |

分类最多解包 8 层 DecoderException；不能将全部 DecoderException 都当作正常输入错误。例如 DecoderException 包裹 IllegalArgumentException 仍为 ERROR。上述规则是基于异常类型的分类，不保证确定每次断开的根因。DEBUG 摘要仅包含上下文、原因分类、远端地址和异常类型，不包含 Throwable、异常消息、对端请求头或正文；未启用 DEBUG 时不构造摘要。默认 INFO 级别下，这些可识别的接入失败不输出错误堆栈；调查握手拒绝时可为此 logger 开启 DEBUG。

例如原始 TCP 探测连接到 WS 端口后断开，会回收该 Channel，且只提供 DEBUG 摘要；pipeline 初始化抛出的配置异常仍保留 ERROR 堆栈。晚到的同类 TLS/WS 失败也沿用此分类，不在关闭之后重新升级为 ERROR。已建立连接的 onException 路由不变；用户 onException 或 ConnectCallback 自身抛错仍记录 ERROR。此策略仅控制框架自己的 logger，不配置 Netty/应用日志后端，也不添加全局限流状态。源码见 [NetworkSupport](../../game-network/src/main/java/cn/managame/network/netty/NetworkSupport.java)，验证见 EstablishmentLoggingTest。

## 9. 验证与边界

- [EstablishmentLoggingTest](../../game-network/src/test/java/cn/managame/network/netty/EstablishmentLoggingTest.java)：正常断开与协议拒绝的无堆栈 DEBUG 摘要、INFO 下静默、未知错误保留原堆栈、晚到 TLS 异常分类。
- [NetworkContractTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkContractTest.java)：真实 TCP/TLS/WS/WSS、发送顺序、生命周期、背压、属性、引用计数、业务异常、配置快照、外部资源。
- [WebSocketContractTest](../../game-network/src/test/java/cn/managame/network/netty/WebSocketContractTest.java)：分片、控制帧、Text/超限拒绝、精确路径、Header 快照与参数校验。
- [ConnectRaceTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectRaceTest.java)：独立结果竞争、中断、onConnected 内关闭、迟到就绪拒绝，以及外部 group 的 Channel 在入口关闭后保持到自身结果到达。
- [ConnectionSetupTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectionSetupTest.java)：handlerAdded 失败、绕过 WS 协议策略的写入异常路由、服务端静默连接的原生默认期限及取消，以及客户端静默 Upgrade 的原生超时。
- [NativeTlsTest](../../game-network/src/test/java/cn/managame/network/netty/NativeTlsTest.java)：显式 TLS 位置/数量、URI 一致性、信任与主机名校验失败、握手超时/取消，TLS 成功但 WS Upgrade 未成功，以及明文在进入 HTTP 处理前被拒绝。
- [DisconnectOrderingTest](../../game-network/src/test/java/cn/managame/network/netty/DisconnectOrderingTest.java)：EOF 尾帧先于断开、回调异常时的借用引用释放、产出尾帧后 decodeLast 失败、重复断开抑制，以及真实 TCP 上的有序异步解码。
- [NetworkEchoExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/network/NetworkEchoExampleTest.java)：在 game-demo 中编译运行完整示例，命令为 mvn -pl game-demo -am test。

`mvn -pl game-network -am test` 运行模块测试；仓库整体验证用 `mvn clean verify`。测试临时证书由当前 JDK keytool 生成；测试限定 Netty 默认线程数为 2，并在 Windows 下让 JDK Selector 唤醒管道回退到 TCP（测试专用的不可作为目录使用的 unixdomain.tmpdir，规避该环境 AF_UNIX connect 间歇失败），生产代码不修改 JVM 属性。

当前未进行真实公网/native transport/生产容量认证或跨语言互操作测试。game-rpc 已提供真实 TCP 集成测试；可选 RPC 到 Runtime 接入由 game-spring 提供。

2026-10-03 修复验证：NetworkClient 的 Bootstrap connect/任务拒绝/同步装配失败路径与握手失败路径统一观察 closed。上述 NetworkClient 失败路径观察到入口已关闭时，报告 IllegalStateException 并保留 cause；关闭前已经认领的 NetworkException 不被改写，不重复通知。NativeTlsTest 的取消断言保持不变，根 clean verify 通过 85 项 Network 测试。未新增连接/尝试注册表，也不将借用 group 的 close 改为批量取消。


### 9.1 易错契约的测试定位

| 契约 | 测试类与方法 |
| --- | --- |
| 四种 Transport 与确定顺序的写入 | NetworkContractTest.roundTripAndOrderedWrites |
| 四种协议两端在 onConnected 内立即发送首条消息 | NetworkContractTest.firstMessagesCanBeSentInsideOnConnected |
| 拒绝保留所有权、背压及发送失败 | NetworkContractTest.ownershipBackpressureAndOutboundFailure |
| Handler 抛错、普通事件、入站 retain | NetworkContractTest.handlerExceptionsEventsAndRetain |
| onConnected 先于成功回调，回调抛错不产生第二结果 | NetworkContractTest.asyncSuccessRunsAfterConnectedAndCallbackFailureIsNotConnectFailure |
| 借用 group 不被关闭、禁止阻塞自身 EventLoop | NetworkContractTest.externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls |
| 快照与每 Channel pipeline 顺序 | NetworkContractTest.snapshotsAndPerChannelPipelineOrder |
| 普通出站异常不自动关闭 TCP/TLS/WS/WSS 两端 | NetworkContractTest.outboundFailuresDoNotAutoCloseEitherPeer |
| 外部 Server close 保留已建立/握手中 Channel，拒绝后续交付 | ConnectRaceTest.externalServerCloseKeepsChannelsAndRejectsLateHandshake |
| Server 初始化期间关闭阻止迟到交付 | ConnectRaceTest.serverCloseDuringInitializationPreventsLateDelivery |
| EOF 尾帧、引用所有权、decodeLast 失败和有序异步断开 | DisconnectOrderingTest |
| 外部 Client close 不遍历尝试，后续 Channel 结果只通知一次 | ConnectRaceTest.externalClientCloseLeavesHandshakeToChannelOutcome / externalClientRejectsReadinessAfterClosure |
| 嵌套 Promise 通知中的握手和首条消息顺序 | ConnectionSetupTest.nestedHandshakeNotificationDeliversFirstMessageBeforeReturning |
| 同步中断回收与恢复中断标记 | ConnectRaceTest.interruptCancelsHandshakeAndRestoresFlag |
| 成功认领后的中断、执行器终止后的兜底、迟到 Channel 创建 | ConnectRaceTest.interruptDuringOnConnectedClosesUndeliverableConnection / terminatedExecutorReportsFailureOnCallingThread / interruptBeforeChannelCreationClosesLateChannel |
| 成功与关闭竞争、onConnected 内关闭仍成功 | ConnectRaceTest.successAndCloseRaceHasOneOutcomePerAttempt / onConnectedCloseStillReportsSuccessfulConnect |

测试方法是后续回归入口。修改计时、接管时点、pipeline 顺序或成功认领点时，必须重新检查相关契约；不能仅以 TCP echo 正常作为全部 Network 行为正确的证据。

<a id="92-突发连接与资源规模"></a>

### 9.2 突发连接与资源规模

NetworkClient 是可复用的连接工厂，不代表一条物理连接。并发 connectAsync 使用独立结果与 Channel，没有客户端全局连接锁或单目标限制。未注入 eventLoopGroup(...) 的每个客户端在 build 时就创建独立 NioEventLoopGroup。Netty 4.1.135.Final 通常配置可用处理器数量的两倍，可由 io.netty.eventLoopThreads 覆盖。工作线程按需启动，但 group 构造已分配各 loop 的 Selector 与队列，因此大量默认客户端会放大基础资源，即使并非所有 loop 都已运行。配置兼容时复用同一客户端；需要不同 handler/pipeline 时，显式共享应用拥有的 group。Client.close 不关闭借用 group 或其 Channel；应用关闭自己的连接，并最终关闭 group。源码：[NetworkClient](../../game-network/src/main/java/cn/managame/network/netty/NetworkClient.java)、[NetworkClientBuilder](../../game-network/src/main/java/cn/managame/network/netty/NetworkClientBuilder.java)。所有权回归入口：NetworkContractTest.externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls。

连接发起没有框架级并发上限、速率控制或有界等待队列。来自 loop 外的每次 connectAsync 都向选中 EventLoop 提交一个任务。Netty 默认事件循环待执行任务上限为 Integer.MAX_VALUE；提交速度超过处理速度时可能积累任务，随后创建大量 socket 或握手。Channel 可写性控制已建立连接的出站写入，不控制创建连接。按现有无连接注册表契约，突发控制属于应用接纳层。限制 loop 线程数本身不会限制等待中的尝试或连接数量。

CONNECT_TIMEOUT_MILLIS 只覆盖 socket connect 阶段，不表示从公开调用起算的总耗时。它不覆盖前置任务排队、阻塞域名解析，也不覆盖完整 TLS/WS 建立与应用 onConnected 回调。事件循环调度也可能延迟超时交付。例如 connect 任务等待前面 250ms 的 loop 工作时，50ms 的 socket 超时不能让该尝试提前完成。connectAsync 返回 void，不暴露逐次取消句柄；借用 group 的 Client.close 也不提供批量取消。当前 Bootstrap 使用基于 JDK 的默认阻塞解析器处理未解析地址；WS URI 创建未解析地址，因此未缓存或缓慢 DNS 可能阻塞选中的 loop，影响其上的其他 Channel。已解析的 TCP 地址可避开该步骤。原生 socket 超时与 TLS/WS 期限仍按 §6 分阶段定义。源码：NetworkClient.validate/startConnect 与 §4 的结果流程。这些是当前实现边界，不代表已经添加总期限或 resolver API。

pipeline 初始化、ConnectionHandler 回调及正常 ConnectCallback 交付均运行在 EventLoop。onConnected 中的重工作会延迟客户端成功通知及同 loop 其他连接。回调保持简短，应用工作通过自己的有界执行机制分发。按 §6 共享可复用 TLS context，每个 Channel 创建新 SslHandler；在每次 pipeline 配置时创建 context 或读取密钥库会增加可避免的握手路径成本。服务端 option(SO_BACKLOG, ...) 可调整监听 backlog，但 worker group、socket 资源、CPU 与应用处理仍是独立限制。backlog 和超时需要结合部署测量，不设通用更大默认值。

现有仓库回归测试覆盖小规模并发竞态与四种协议，不是可重复的容量基准。本机无业务负载的突发实验可验证独立回调与清理，但不能据此认定 TLS/WSS 容量、业务吞吐、公网表现或持续短连接容量。容量验证应分别改变复用客户端与共享 group 客户端、连接数/速率、TCP/TLS/WS/WSS、慢握手、拒绝及关闭竞争；记录成功/失败、回调唯一性、建立耗时分位数、loop 延迟、堆/直接内存、线程、socket 与关闭后回收。未来逐次取消、总期限、resolver 定制或连接接纳能力仍是待评估扩展；修改公开 API 或标准前，先确定可观察行为及所有权。
