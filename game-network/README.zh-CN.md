# game-network

[包结构、职责与 import 迁移](../docs/package-layout.md)

[English](README.md) | 简体中文

Java 25 + Netty 4.2.15.Final 的游戏服务器网络组件，版本 0.1.0-SNAPSHOT。

## 接口与实现

| 公共接口 | 具体类 | 协议入口 |
|---|---|---|
| NetworkServer：start / stop | TcpNetworkServer | listen(host, port) |
| NetworkServer：start / stop | WsNetworkServer | listen(host, port)，配置 sslContext 后为 WSS |
| NetworkServer：start / stop | HttpNetworkServer | listen(host, port)，原生 HTTP Handler |
| NetworkClient：init / destroy | TcpNetworkClient | connect(host, port, callback) |
| NetworkClient：init / destroy | WsNetworkClient | connect(uri, callback)，URI 选择 WS / WSS |

Connection 和 NetworkHandler 公共接口保留。协议专属建连方法位于具体 Client，公共 NetworkClient 只定义共同生命周期，不包含不适用的 connect 重载。

game-network-api 仅依赖 JDK；game-network-netty 提供以上具体实现和原生扩展。当前不提供 HTTP Client。

## 多协议网关

~~~java
NetworkResources resources = NetworkResources.builder()
    .ioThreads(4).httpThreads(2)
    .shutdownTimeout(Duration.ofSeconds(5)).build();

TcpNetworkServer tcp = TcpNetworkServer.builder()
    .resources(resources).listen("0.0.0.0", 7000)
    .handlerFactory(EchoHandler::new)
    .pipeline((connection, pipeline) -> pipeline.addLast(
        new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
        new LengthFieldPrepender(4)))
    .build();

WsNetworkServer ws = WsNetworkServer.builder()
    .resources(resources).listen("0.0.0.0", 7001)
    .handlerFactory(EchoHandler::new)
    .webSocketServer(config -> config.websocketPath("/game"))
    .build();

HttpNetworkServer http = HttpNetworkServer.builder()
    .resources(resources).listen("0.0.0.0", 8080)
    .contextPath("/game")
    .httpPipeline(pipeline -> pipeline.addLast(new HealthHandler()))
    .build();
~~~

应用统一管理 List<NetworkServer> 的启动和停止，退出时先 stop 所有 Server、destroy 所有 Client，再关闭 resources。可运行的完整初始化与异常清理示例见 [GatewayExample](game-network-netty/src/test/java/cn/managame/network/tests/GatewayExample.java)，在 IDE 中以 JDK 25 运行 main，输入回车停止。提供 PEM 证书链与私钥两个参数时另启动 WSS Server。

每个 Server 只负责自己的协议，可通过多个 listen 监听该协议的多个地址。命名监听用 listen(name, address)，端口 0 的实际绑定地址通过 boundAddresses() 查询。多个协议用多个 Server 组合；TCP 与 WS/WSS 共用实时 IO，HTTP 使用 NetworkResources 中独立的 HTTP IO group，HTTP 消息不进入 NetworkHandler。

HTTP 的 `contextPath("/game")` 为该 Server 的所有监听地址设置统一项目路径：外部请求 `/game/health?detail=1`，业务 HTTP Handler 收到的 `request.uri()` 为 `/health?detail=1`；`/game` 和 `/game/` 均映射为 `/`。按原始路径和完整路径段匹配，区分大小写；`/game2/health` 等不匹配路径返回 404，不进入业务 Handler。查询参数和剩余路径不做 URL 解码。

默认 contextPath 为 `""`，与 `"/"` 一样表示根路径，不修改 URI；配置末尾的 `/` 会移除。非根配置必须以 `/` 开头，路径段支持英文字母、数字、`-._~`，支持 `/my-game/api` 这样的多级路径；拒绝空段、`.` / `..` 段、百分号编码、查询参数和片段。

网关或 Nginx 若保留 `/game` 前缀转发，后端配置 `/game`；若代理已移除前缀，后端使用根路径配置。组件只处理入站路径，不自动修改响应的 `Location`、Cookie Path 或页面中的链接，也不从转发请求头推断前缀。

WsNetworkServer 配置服务端 sslContext 后，其监听使用 WSS。需要同时监听 WS、WSS 时创建两个 WsNetworkServer 并共享资源。省略 resources 时组件自有资源随 stop 关闭，显式提供时借用。

## 多目标客户端

~~~java
TcpNetworkClient tcpClient = TcpNetworkClient.builder()
    .resources(resources).handlerFactory(ClientHandler::new)
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
    .pipeline((connection, pipeline) -> pipeline.addLast(
        new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
        new LengthFieldPrepender(4)))
    .build();

WsNetworkClient wsClient = WsNetworkClient.builder()
    .resources(resources).handlerFactory(WsClientHandler::new).build();

tcpClient.init();
wsClient.init();

tcpClient.connect("127.0.0.1", 7000, callback);
tcpClient.connect("10.0.0.8", 7000, anotherCallback);
wsClient.connect(URI.create("ws://127.0.0.1:7001/game"), wsCallback);
wsClient.connect(URI.create("wss://gateway.example/game"), secureCallback);
~~~

客户端生命周期为 build → init → 多次 connect → destroy。init 初始化所需的 IO 资源、解析器和 Bootstrap，不建立远端连接；重复 init 不重新创建 Bootstrap。未初始化时 connect 同步报错；destroy 后不能重新初始化，需要新建客户端。Connection.close 只关闭该连接，客户端仍可继续连接其他目标。

TcpNetworkClient 在 init 中创建一个 Bootstrap，并配置整个 EventLoopGroup；所有 connect 共享该实例，由 Netty 分配 EventLoop。每次目标作为 connect 参数传入，共享 Bootstrap 不保存本次地址、回调或临时属性，也不按连接 new / clone。ChannelInitializer 在对应 Channel 上创建独立结果 Promise，由原生建连 Future 的监听器接续用户初始化结果。

WsNetworkClient 同样在 init 中创建一个共享 Bootstrap，配置整个 EventLoopGroup。每次 connect 先调用 Bootstrap.register，由 Netty 分配 Channel 和 EventLoop；注册成功后，在该 EventLoop 上按本次 URI 配置管线，再调用 Channel.connect。地址解析复用 Netty 原生 ResolveAddressHandler，WS / WSS 握手由原生 channelActive 触发；URI 和回调只属于本次调用，不存入共享 Bootstrap。默认 SslContext 在 init 中创建并复用；用户提供的 SslContext 在构建时保存引用。

Client 不保存唯一目标地址。正常提交的 connect 通过 Netty Promise 向 ConnectCallback 通知一次结果；参数错误、未初始化或销毁后调用或 EventLoop 拒绝提交时同步抛异常。不增加连接数量限制或整体建连计时器，TCP 超时通过 ChannelOption.CONNECT_TIMEOUT_MILLIS 配置，DNS、TLS、WS 超时通过对应 Netty 原生入口配置。WS/WSS 成功结果等待原生握手。WSS 默认校验证书和主机名，自定义信任链通过 WsNetworkClient.Builder.sslContext 配置。

成功结果还要求 NetworkHandler.onConnected 正常返回。该回调抛异常时，客户端通过 ConnectCallback.onFailure 返回原始异常并关闭连接；服务端记录初始化失败并关闭连接。初始化失败不交付业务消息，也不调用业务 onDisconnected；原生 channelInactive 仍沿 Pipeline 传播。

正常注册的连接回调运行在网络 EventLoop；TCP 在 Channel 创建等注册前阶段失败时，失败通知沿用 Netty 原生 Future 的执行器。回调应避免阻塞，不能在所属网络线程调用 Server.start、Client.init、Server.stop、Client.destroy 或 resources.close。

## Connection 与原生 Pipeline

Connection.type() 返回 ConnectionType.TCP、WS 或 WSS，类型在创建时确定且不随握手状态变化；HTTP 使用原生 Handler，不创建 Connection。NettyConnection 持有 Channel 和不可变的 ConnectionType。id() 直接返回 String，取自 channel.id().asLongText()。状态、远端地址、属性、close 都调用原生方法；write 先预留每连接出站容量，再调用 writeAndFlush，不另建消息队列。

write 返回 false 表示 Channel 已不活跃或出站预算不足，均未接管消息；true 表示已交给原生写路径，不代表对端收到，也不额外保证与并发 close 之间的原子准入。需要写完成通知时，使用 connection.write(message, failure -> { ... })：null 表示本地写成功，否则为原始失败原因。Netty 已实现此可选入口。返回 true 后即使稍后失败，引用仍由传输负责；false 不触发回调，引用由调用方释放。回调可能在返回前或传输线程执行，不能阻塞。未支持此能力的 Connection 在取得引用前抛出 UnsupportedOperationException。需要直接访问 ChannelFuture 时仍可使用 NettyAccess.channel(connection).writeAndFlush(message)。直接 Channel 写绕过连接的出站预算。两条写路径只选择一条，避免重复转交消息引用。

TCP / WS 的 builder 均可设置 `.outboundWriteLimits(new OutboundWriteLimits(1024, 8L * 1024 * 1024))`，类型为 `cn.managame.network.netty.connection.OutboundWriteLimits`，这也是默认值。待完成写条数和估算字节在调用线程原子预留，覆盖提交 EventLoop 前的排队；原生写成功或失败后归还预算。`isWritable()` 同时检查此预算。返回 false 表示准入拒绝，调用方释放消息，并根据业务协议决定丢弃、有界重试或关闭慢连接。

ByteBuf / ByteBufHolder 按 readableBytes 计费，FileRegion 按剩余字节计费，其他业务对象使用 builder 的原生 `ChannelOption.MESSAGE_SIZE_ESTIMATOR`（服务端 `childOption`、客户端 `option`）。业务对象编码后明显膨胀时应提供合适估算器，必要时计入帧头。此字节数属于准入估算，任意业务编码与 TLS 膨胀后的内存并不等于它；写条数上限始终适用于所有对象。原生 HTTP Handler 与直接 Channel 写仍由应用的原生管线管理。

NetworkHandlerBridge 是 Pipeline 中的普通 SimpleChannelInboundHandler，直接调用用户 NetworkHandler，沿用原生串行事件传播。onMessage 的引用计数参数是借用引用；回调返回由原生自动释放机制释放。回写示例：

~~~java
public void onMessage(Connection connection, Object message) {
    ReferenceCountUtil.retain(message);
    if (!connection.write(message)) ReferenceCountUtil.release(message);
}
~~~

业务协议由用户提供 Decoder / Encoder，组件不自动编码 String、byte[] 或业务对象，也不为 TCP 假定消息边界。未安装业务 Decoder 时，TCP 交付 ByteBuf 数据块，WS/WSS 交付原生帧。

入站顺序如下；出站按 Pipeline 的反方向经过 Encoder：

~~~
TCP：用户 Decoder / Encoder → NetworkHandlerBridge → NetworkHandler
WS/WSS：TLS（WSS）→ HTTP / WebSocket 原生处理器 → 用户 Decoder / Encoder → 桥接层
~~~

例如，使用长度字段的 TCP 业务管线：

~~~java
.pipeline((connection, pipeline) -> pipeline
    .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4))
    .addLast("frameEncoder", new LengthFieldPrepender(4))
    .addLast("messageDecoder", new GameMessageDecoder())
    .addLast("messageEncoder", new GameMessageEncoder()))
~~~

GameMessageDecoder / GameMessageEncoder 是应用自行实现的原生 Netty Handler。这样 NetworkHandler.onMessage 收到业务对象，connection.write(业务对象) 先经过 GameMessageEncoder 转为 ByteBuf，再由 LengthFieldPrepender 写入长度。WS 的业务 Encoder 应输出 WebSocketFrame，Decoder 从帧中解析业务对象。WS 帧聚合默认关闭，需聚合分片时显式设置 WS_AGGREGATION 或安装自己的聚合器。

pipeline 配置直接添加原生 Handler；组件最后添加 network.handler 桥接层。运行时可通过原生 ChannelPipeline 修改，NettyAccess.editPipeline 仅是 EventLoop.submit 的便捷入口，不保护节点或补发被用户 Handler 消费的事件。

五个具体 Server / Client 直接实现公共接口，各自调用 Bootstrap 和 ChannelGroup；没有 ComponentSupport、通用生命周期父类、后台停止任务、连接计数或独立终止 Future。

各具体类通过自己的私有 initPipeline 方法初始化原生管线，已移除 ProtocolSupport 和 install 入口。TCP 配置用户 Handler 与桥接层；WS/WSS 在对应类中直接配置协议 Handler；HTTP 同样在 HttpNetworkServer 中配置。用户仍可通过原生 addBefore / addAfter 在编解码前后插入 Handler。

Connection.close 直接走原生 Channel.close，包括 TLS/WS 的原生处理。Server.stop 和 Client.destroy 直接等待 ChannelGroup 关闭结果，再释放自有资源；对应的等待选项为 STOP_TIMEOUT、DESTROY_TIMEOUT，超时后可再次调用以等待关闭。它们不额外等待业务回调返回；尚在 EventLoop 排队的建连任务会报告客户端已销毁，使用共享资源时回调可能晚于 destroy 返回。Channel 属性不在关闭后自动清空，boundAddresses 保留已成功绑定过的地址供查询。

## 配置

Server 的 option(ChannelOption, value) 配置监听 Channel，childOption 配置接入 Channel；Client 的 option 配置连接。未指定时保留 Netty 原生默认，不增加组件收发积压限额或大小计算接口。

组件行为通过 option(NetworkOptions.KEY, value) 选择；默认值见 [实现记录](docs/implementation-status.md)。WS Builder 提供 webSocketServer / webSocketClient、sslContext / tlsHandler。HTTP Builder 提供 contextPath、httpPipeline、httpDecoder、httpAggregation（0 为流式模式）。同一配置入口重复设置时后值覆盖前值，build 后配置快照固定。

内部 Settings 只保存 ChannelOption / NetworkOption 的只读快照并读取默认值。用户 Handler 与 Pipeline 配置由 TCP / WS 具体类持有；TLS 和 WS 协议定制器只属于对应的 WS 类及 Builder；HTTP 保留自己的配置。公共 Builder 基类各自单独成文件，仅复用共同配置，不把协议专属字段放进公共选项层。

组件选项在 build 时检查适用范围；例如 TCP 配置 WS_AGGREGATION、Client 配置 START_TIMEOUT 会立即报错。原生 ChannelOption 保持 Netty 的处理方式。

| 组件 | 支持的 NetworkOptions |
|---|---|
| TCP Server | READ_IDLE / WRITE_IDLE / ALL_IDLE、START_TIMEOUT、STOP_TIMEOUT |
| WS Server | TCP Server 的选项，加 WS_AGGREGATION / WS_UPGRADE_AGGREGATION |
| HTTP Server | START_TIMEOUT、STOP_TIMEOUT |
| TCP Client | READ_IDLE / WRITE_IDLE / ALL_IDLE、DESTROY_TIMEOUT |
| WS Client | TCP Client 的选项，加 WS_AGGREGATION / WS_UPGRADE_AGGREGATION |

STOP_TIMEOUT / DESTROY_TIMEOUT 控制各组件 ChannelGroup 的关闭等待；NetworkResources.Builder.shutdownTimeout 控制随后自有 EventLoopGroup 的终止等待，默认 5 秒，分别计时。resources.close(Duration) 可覆盖某次资源关闭的等待期限；超时后仍可再次 close。借用的 EventLoopGroup 不由 NetworkResources 关闭。

## 构建与验证

使用 JDK 25、Maven 3.9，在项目根目录运行：

~~~sh
mvn verify
~~~

如本机镜像不可用，可使用项目提供的 Central 设置与项目本地缓存：

~~~sh
mvn -s .mvn/settings.xml -gs .mvn/settings.xml "-Dmaven.repo.local=.m2" verify
~~~

两个模块 target 下分别生成 JAR。测试源包含原生委托、协议集成、Bootstrap 复用及并发多目标隔离测试；默认 verify 是功能回归；独立容量测试与 Docker Linux 入口见 [运行验证](docs/operations.md)，实际环境和测量结果见 [实现记录](docs/implementation-status.md)。

Windows 测试进程使用模块 target 作为 JDK 本地套接字临时目录，不修改组件运行时全局属性。相关属性见 [JDK 网络属性](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html)。

- [Game Network Specification](docs/Game%20Network%20Specification.md)：Draft 0.7 合并版，当前唯一的语言无关通用规范。
- [Java / Netty 实现设计](docs/java-netty/implementation-design.md)：具体类与原生适配设计。
- [实现及验证记录](docs/implementation-status.md)：默认值和验证范围。
- [接入与运行验证](docs/operations.md)：原生监控 Handler、DNS TCP 回退、Windows / Linux CI、Docker 与容量测试命令。
- [Linux 验证报告](docs/linux-validation-2026-09-06.md)：双平台 37 项功能测试与 TCP 1 万、WS 2000、WSS 1000 连接的短时容量结果。

当前未提供自动重连、RPC、玩家会话、异步 NetworkHandler、组件自定义排空模式、UDP/KCP/QUIC/HTTP2，也不提供 HTTP 与 WS 共端口路由。
