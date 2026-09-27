# OGBS Network Java 25 Development Specification 1.0

[English](OGBS-Network-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Network-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应标准：[OGBS Network Specification](OGBS-Network-1.0.zh-CN.md)。

本文规定 Java 实现的公开 API、默认配置、异常形式、线程与资源机制、扩展接入以及验证要求。Java 实现必须同时满足本文和对应标准规范；不能只满足方法签名而忽略行为契约。代码与规范冲突时应修正实现，设计变更则同步修订两层规范。下文明确标注的待实现、未验证能力不代表已完成。

状态：V1 Draft，当前 game-network 实现基线。通用语义单一来源：[Network Specification](OGBS-Network-1.0.zh-CN.md)。

Maven：`cn.managame:game-network:1.0.0-SNAPSHOT`。JDK 25，无 preview；Netty 版本由根 POM BOM 管理，当前 4.1.135.Final。接口与 Netty 实现同一 artifact，不依赖 game-core。

## 1. 包结构

| 包 | 公开 API |
| --- | --- |
| cn.managame.network.connection | Connection、ConnectionHandler、WriteStatus |
| cn.managame.network.connector | ConnectCallback、WebSocketConnectOptions |
| cn.managame.network.error | NetworkException |
| cn.managame.network.netty | NetworkServer、NetworkServerBuilder、NetworkClient、NetworkClientBuilder |

NettyConnection、ConnectAttempt、NetworkPipeline、TransportGate、ConnectionHandlerAdapter、WS 适配均保持 netty 包级封装。Server/Client 入口与其实现同包，避免为拆包公开内部协作类型。

不再建立 attribute 包或自定义 ConnectionKey，直接使用 Netty AttributeKey。原 Acceptor/Connector 抽象由具体 NetworkServer/NetworkClient 替代。模块入口及可运行示例见 [game-network](../../game-network/README.zh-CN.md)。

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
| sslContext(SslContext) | 必须是服务端 context；配置后为 TLS TCP / WSS |
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

一个 Client 可连接多个目标或同一目标多次。同步/异步共用 ConnectAttempt，一次 CAS 仲裁成功与失败；成功认领发生在交付 onConnected 前，最终成功回调在 onConnected 后。callback 自身抛异常只记录日志，不转成第二次结果或 ConnectionHandler.onException。

同步 connect 禁止从该 Client 的任意 EventLoop 调用，立即 IllegalStateException。等待线程被中断时恢复 interrupt flag，取消尝试并关闭底层 Channel，抛 NetworkException；即使成功刚好赢得竞争，也关闭同步调用方无法取得的连接。

有效尝试的异步 callback 在选定的 Netty EventLoop 上执行。入参非法、已关闭等发起前拒绝在调用线程直接 onFailure；如果外部 EventLoop 已终止且拒绝回调任务，也在当前线程兜底通知一次，避免永久无结果。null callback 直接 NullPointerException。

| Client Builder 方法 | 默认 / 含义 |
| --- | --- |
| handler(ConnectionHandler) | 必填 |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | 按注册顺序追加 |
| webSocket() / webSocket(int maxMessageSize) | WS 模式；默认 1 MiB |
| sslContext(SslContext) | 必须是客户端 context |
| eventLoopGroup(EventLoopGroup) | 缺省创建标准 NioEventLoopGroup() |
| channelFactory(ChannelFactory&lt;? extends Channel&gt;) | NioSocketChannel::new |
| option(ChannelOption&lt;T&gt;, T) | 原生 Bootstrap option |
| build() | 快照并创建独立 Client |

TCP 模式只接受 SocketAddress；WS 模式只接受 URI，错用为 IllegalStateException。URI 必须有有效 host，scheme 为 ws/wss，端口缺省分别 80/443；拒绝 user-info、fragment、0 或超范围端口。

WS 的 ws:// 即使配置 SslContext 也不启用 TLS；wss:// 使用自定义 context 或惰性创建的 JDK 默认客户端 context。默认信任库来自 JVM，TLS 启用主机名验证。TCP 配置 context 即 TLS TCP，需要 InetSocketAddress 提供主机名。Server 不自动生成 TLS 证书/context。

### 4.1 ConnectAttempt 的完成协议

ConnectAttempt 是内部一次性结果协调对象，包含选定 EventLoop、完成 CAS、Channel、成功 Connection/失败 cause、同步 latch 及可选 callback。同步与异步入口共用它，避免两套建立逻辑产生不同语义。

```text
创建尝试并加入 pending
→ 创建/附着 Channel
→ Transport 完成，且应用 pipeline 已装配
→ claimSuccess CAS
→ 创建 Connection，执行 onConnected
→ 存入成功值，移出 pending，唤醒同步等待者/调用 onSuccess
```

失败方 CAS 成功后保存 cause，标记取消，关闭已附着 Channel，移出 pending 并通知结果。若取消先发生、Channel 后附着，attach 会检查取消状态并关闭它，避免底层资源逃逸。

成功 CAS 特意早于 onConnected。否则 onConnected 已把 Connection 交给业务后，Client.close 仍可能把结果改成失败。成功认领后 onConnected 抛异常只走 ConnectionHandler.onException，ConnectCallback 不得再收到 onFailure。

### 4.2 中断和回调线程例外

同步 await 被中断时，即使成功已刚刚认领，也关闭已附着 Channel，因为同步调用者将以异常退出，无法接管成功值。恢复线程中断标记后抛 NetworkException；这不会制造第二次异步结果。

正常异步结果由尝试选择的 EventLoop 交付；发起前校验失败则直接在调用线程 onFailure。借用的 EventLoop 若已终止且拒绝结果通知，只能在当前线程兜底一次。因此上层不能要求 onFailure 永远具有 Channel EventLoop 上下文，更不能在回调内无条件调用同步 connect/close。

ConnectCallback 自身异常只做诊断，不转交 ConnectionHandler.onException：前者属于建连调用者的结果处理，后者属于已经建立的 Connection 生命周期。


## 5. 配置快照与握手参数

Builder 可重复 build，每个实例快照自己的 handler、pipeline 列表、options、Transport 参数。未提供外部 group 时分别创建资源；传入 group 时显式共享。快照不深复制用户的 handler、SslContext 或 pipeline lambda 内部状态。Builder 本身不是并发配置器。

handler/pipeline/group/factory/option/value 等 null 参数立即拒绝。build 缺必填项为 IllegalStateException；非法 path、非正消息长度、不匹配的 SSL context 为 IllegalArgumentException。

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

构造时复制 HttpHeaders，headers() 返回新的副本。默认空 headers、null subprotocol；可重复安全用于不同 connect。服务端默认不协商子协议，客户端要求子协议而服务端未返回时握手失败。高级服务端可通过 pipeline 配置 Netty WebSocket handler，框架不增加 Auth/Router/HTTP API。

### 5.1 快照的深度和可复用范围

Builder build 后再修改 options 或添加 pipeline，只影响下次 build。它不会改动已经建好的 Server/Client 配置。handler、SslContext 和 lambda 捕获对象仍为共享引用，因此“配置快照”不意味着复制它们内部的可变状态。

每条 Channel 调用各 pipeline configurer 来创建自己的编解码器。不要在 Builder 外只创建一个非 Sharable decoder，然后重复 addLast 到所有连接；应在 configurer 内 new。当业务需要共享 handler 时，由应用确认其 @Sharable 和并发安全性。

WebSocketConnectOptions 另行复制 Headers：构造后改变原 Headers，以及改变 headers() 返回值，都不会修改选项本体。这确保同一个 options 可以用于多个独立尝试；但不意味着 Server 自动理解这些 Header 或进行鉴权。


## 6. Pipeline 与超时

```text
TCP: [SslHandler] → transport lifecycle gate → 用户 pipeline → ConnectionHandler adapter
WS:  [SslHandler] → HTTP codec / HTTP aggregator / WS protocol / frame aggregator
     → transport lifecycle gate → binary decoder/encoder → 用户 pipeline → ConnectionHandler adapter
```

每条 Channel 重新执行 configurer；多次 pipeline(a).pipeline(b) 按 a、b 执行。用户 decoder/encoder 应各自创建非 Sharable 实例。出站逆序经过用户 encoder，再由 ByteBuf 转 BinaryWebSocketFrame。入站聚合后 content.retain，原 frame 由 Netty decoder 释放。

生命周期 gate 在用户 pipeline 前消费内部 TLS/WS 握手事件并观察真实 inactive；业务消息/异常 adapter 在末端。TCP 字节流不内置业务 framing。用户 IdleStateHandler 的事件经 onEvent 交付，不重新定义 Idle 类型。自定义异步 handler 必须自行维护事件传播及消息顺序，不得删除/重排内部 `managame-*` handler 或伪造生命周期/握手事件。

内置 handler 名称可用于原生插入，例如在 `managame-http-aggregate` 后添加 HTTP Upgrade 校验；在 `managame-tls` 上配置 Netty TLS handler。必须把 TLS 交给 sslContext(...) 才纳入建立语义，手动插入 SslHandler 不触发框架自动识别。

| 参数 | 当前默认与入口 |
| --- | --- |
| TCP connect | Netty CONNECT_TIMEOUT_MILLIS，默认 30 秒；option 设置 |
| TLS handshake | SslHandler 默认 10 秒；通过 pipeline 中原生 handler 设置 |
| WS client handshake | Netty WebSocketClientProtocolHandler 默认 10 秒 |
| WS server establishment | 从 Channel active 起最多 10 秒，覆盖静默/未发 Upgrade 的连接；使用 EventLoop 定时任务，成功/关闭取消 |
| HTTP Upgrade body | HttpObjectAggregator 64 KiB，独立于业务消息 |
| Binary WebSocket message | 1 MiB，同时约束 frame payload 和聚合长度；Builder 可调整 |
| NIO / socket options | Netty 默认值；原生 option / childOption |

Text 用关闭码 1003 拒绝。单帧/聚合超限和 WS 协议违规关闭，不进入 onMessage。Ping/Pong/Close 使用 Netty protocol handler。Server 初始化某一连接 pipeline 失败仅诊断并关闭该 Channel；Client 则本次建连失败。

### 6.1 装配时点与事件方向

NetworkPipeline.install 在 Channel 初始化期间装配内部 Transport handler、调用用户 configurer，再添加末端 ConnectionHandlerAdapter。它发生在 Transport 握手完成之前；这样用户可以配置已经装配的原生 handler。Connection 创建则等待握手完成并由 TransportGate 放行，不能把两者混为一个时点。

入站事件大体从头到尾传播，出站写入从尾到头传播。以下列出主要内部顺序，方括号表示可选：

```text
managame-write-errors
→ [managame-tls]
→ [managame-http → managame-http-aggregate
   → 服务端 managame-websocket-path → managame-websocket
   → managame-websocket-aggregate]
→ managame-transport
→ [managame-binary-in → managame-binary-out]
→ 用户编解码/事件 handler
→ managame-connection
```

WS 出站用户 encoder 先把业务对象变成 ByteBuf，binary-out 再包装为 BinaryWebSocketFrame，最后经过 WS/TLS 编码。TCP 不插入 binary 适配，也不提供业务消息长度头。

用户 configurer 若通过 addBefore/addAfter 插入原生 handler，应保持内部握手、生命周期和释放逻辑有效。吞掉 handshake/inactive 事件或删除内部 handler 会破坏前置条件；这不属于框架支持的任意流水线改写。

### 6.2 为什么区分生命周期 gate 与末端 adapter

TransportGate 位于用户 pipeline 之前，负责握手事件、建立前错误、真实断开及协议拒绝。末端 adapter 才负责业务消息和异常。用户 decoder 即使消费了某些普通消息，也不能因此阻止框架观察底层断开。

ready 放行时取消建立超时、解除建立中集合的关联并清除临时建立回调引用。成功 Channel 不需要长期通过建立闭包持有 Client/Server；业务连接管理仍由上层负责。

WS 的额外服务端建立计时从 channelActive 开始，是因为 Netty 的 Upgrade 握手计时不能独自覆盖连接建立后始终不发 HTTP 请求的静默客户端。这个计时器只限制建立阶段，不是业务心跳或读空闲超时。

### 6.3 写入错误的路由

当前实现使用 voidPromise 避免每次发送暴露或维护完成 Future。void-promise 的失败可能从 pipeline 头部发出；若直接穿过 WS protocol handler，普通发送错误可能被其默认逻辑关闭连接。

因此 managame-write-errors 在已建立后将这类错误转到 transport gate 后的应用方向，保留用户 pipeline 和 onException 的处理机会。它不是吞掉错误，也不取消 WS 对非法入站帧的关闭规则。建立前错误仍按握手失败处理。

原生自定义 handler 自己关闭 Channel 的行为不受“Network 普通异常不自动关闭”限制。接入者必须审视自己的 exceptionCaught；框架无法撤销用户 handler 已主动发起的关闭。


## 7. 资源与关闭

Server close 先关闭监听 Channel，再取消未交付握手，然后 shutdown 自建 boss/worker；Client close 先拒绝新尝试、取消 pending，再 shutdown 自建 group。成功连接不保存在业务集合内；自有 group shutdown 会自然关闭关联 Channel，外部 group 上已成功的 Connection 需应用单独关闭。

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

Client.close 会取消仍未完成的尝试；成功已经认领的尝试保持成功结果。自建 group 随后 shutdown 仍可能令这个成功 Connection 失效，调用方应按普通连接生命周期处理。

外部 group 场景下，关闭 Client 不遍历成功 Connection。若业务要求一次性关闭一批成功连接，应由上层持有该批连接并明确调用 close。不要通过依赖 Client 内部 pending 集合来实现连接清单，它只存在于建立阶段。


## 8. 失败与兼容性

| 场景 | 表达 |
| --- | --- |
| 非法参数、缺必填项、生命周期误用 | 上述 Java 参数/状态异常；异步入口转 onFailure（null callback 除外） |
| bind/connect/TLS/WS/pipeline 初始化失败 | NetworkException，保留 cause |
| Client close 取消 pending | IllegalStateException |
| 建立后 Decoder/I/O/Handler 异常 | 原始 Throwable → onException |
| onException / ConnectCallback 自身异常 | System.Logger 最终诊断 |
| INACTIVE / NOT_WRITABLE | WriteStatus，调用方保留所有权 |

旧 ConnectionListener、ConnectionKey、CloseInfo、NettyConnector、NettyAcceptor、tryWrite 与 TestKit 路径已失效，不提供兼容层。game-rpc 已通过 ConnectionHandler/ConnectCallback 接入，并提供真实 TCP 集成测试。

### 8.1 后续实现不应重新引入的隐式行为

新增 codec 不应改变 write 的三态接纳和所有权；新增 Transport 应明确建立成功门槛、超时、消息边界与协议拒绝。新增异步接入不能在 EventLoop 阻塞等待自己；新增属性包装也不能默默改变关闭后的属性语义。

心跳、IdleStateHandler、自定义 HTTP Upgrade 检查和业务认证通过原生 Netty 接入或上层组件组合，当前没有独立框架 DSL。若要增加统一能力，应先说明可观察行为与资源责任，再同步两层 Spec；不能仅以“便于使用”为由默认加入自动重连、自动关闭或业务发送队列。

可运行入口以现有 [NetworkEchoExample](../../game-network/src/main/java/cn/managame/network/example/NetworkEchoExample.java) 及下述示例测试为准。本章的流程图、引用计数说明和状态表是设计说明，不另行声明为独立可运行程序。


## 9. 验证与边界

- [NetworkContractTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkContractTest.java)：真实 TCP/TLS/WS/WSS、发送顺序、生命周期、背压、属性、引用计数、业务异常、配置快照、外部资源。
- [WebSocketContractTest](../../game-network/src/test/java/cn/managame/network/netty/WebSocketContractTest.java)：分片、控制帧、Text/超限拒绝、精确路径、Header 快照、TLS 不可信证书、参数校验。
- [ConnectRaceTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectRaceTest.java)：pending 关闭、中断恢复、并发完成、onConnected 内关闭。
- [NetworkEchoExampleTest](../../game-network/src/test/java/cn/managame/network/example/NetworkEchoExampleTest.java)：完整示例编译运行。

`mvn -pl game-network -am test` 运行模块测试；仓库整体验证用 `mvn clean verify`。测试临时证书由当前 JDK keytool 生成；测试限定 Netty 默认线程数为 2，并在 Windows 下让 JDK Selector 唤醒管道回退到 TCP（测试专用的不可作为目录使用的 unixdomain.tmpdir，规避该环境 AF_UNIX connect 间歇失败），生产代码不修改 JVM 属性。

当前未进行真实公网/native transport/生产容量认证或跨语言互操作测试。game-rpc 已提供真实 TCP 集成测试；自动 RPC 到 Runtime 接入仍未实现。


### 9.1 易错契约的测试定位

| 契约 | 测试类与方法 |
| --- | --- |
| 四种 Transport 与确定顺序的写入 | NetworkContractTest.roundTripAndOrderedWrites |
| 拒绝保留所有权、背压及发送失败 | NetworkContractTest.ownershipBackpressureAndOutboundFailure |
| Handler 抛错、普通事件、入站 retain | NetworkContractTest.handlerExceptionsEventsAndRetain |
| onConnected 先于成功回调，回调抛错不产生第二结果 | NetworkContractTest.asyncSuccessRunsAfterConnectedAndCallbackFailureIsNotConnectFailure |
| 借用 group 不被关闭、禁止阻塞自身 EventLoop | NetworkContractTest.externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls |
| 快照与每 Channel pipeline 顺序 | NetworkContractTest.snapshotsAndPerChannelPipelineOrder |
| 普通出站异常不自动关闭 TCP/WS 两端 | NetworkContractTest.outboundFailuresDoNotAutoCloseEitherPeer |
| 关闭 pending 只通知一次 | ConnectRaceTest.closeCancelsPendingHandshakeExactlyOnceOnEventLoop |
| 同步中断回收与恢复中断标记 | ConnectRaceTest.interruptCancelsHandshakeAndRestoresFlag |
| 成功与关闭竞争、onConnected 内关闭仍成功 | ConnectRaceTest.successAndCloseRaceHasOneOutcomePerAttempt / onConnectedCloseStillReportsSuccessfulConnect |

测试方法是后续回归入口。修改计时、接管时点、pipeline 顺序或 CAS 认领点时，必须重新检查相关契约；不能仅以 TCP echo 正常作为全部 Network 行为正确的证据。
