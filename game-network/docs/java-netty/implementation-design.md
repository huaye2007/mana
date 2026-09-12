# game-network Java / Netty 实现设计

**版本：** Implementation Draft 0.19  
**基线：** JDK 25、Netty 4.2.15.Final  
**标准：** [GNS Draft 0.7 合并版](../Game%20Network%20Specification.md)  
**状态：** 已实现；功能测试记录见 [实现记录](../implementation-status.md)

## 1. 对象职责

### NettyConnection

持有 Channel 和不可变的 ConnectionType。Connection.type() 返回 TCP、WS 或 WSS；其余 Connection 操作直接调用 Channel 对应方法。TCP 端固定为 TCP；WS 客户端根据本次 URI，WS 服务端根据是否配置 TLS，确定 WS 或 WSS。类型在创建时即可读取，不代表握手已经完成。HTTP 仍由原生 Handler 处理，不创建 Connection。

| Connection | 原生委托 |
|---|---|
| id | 返回 String，直接使用 channel.id().asLongText()，不额外包装标识对象 |
| type | 构造时显式传入的 ConnectionType，不通过 Channel 状态或管线推断 |
| isActive | channel.isActive() |
| isWritable | channel.isWritable() |
| write | channel.writeAndFlush(message) |
| close | channel.close() |
| remoteAddress | channel.remoteAddress() |
| get / set / remove / compareAndSet | channel.attr(key) 的对应方法 |

不保存 NetworkHandler、连接就绪／终止状态、远端地址副本、写计数、关闭锁、暂存消息或回调队列。属性不由组件在关闭时清空，沿用 Channel 的属性生命周期。标识采用 Netty ChannelId，不再另建资源域和连接序列号。

现有 write 返回 boolean：调用前 Channel 已不活跃时返回 false，不接管消息；调用原生 writeAndFlush 后返回 true。它不是写入结果，也不承诺在并发 close 期间另建原子准入边界。需要原生写入成功／失败通知时，使用 NettyAccess.channel(connection).writeAndFlush(message) 返回的 ChannelFuture。引用计数消息只向其中一条写路径转交一次引用。

### NetworkHandlerBridge

桥接层就是 Pipeline 中的一个普通 SimpleChannelInboundHandler；它持有 NettyConnection、用户 NetworkHandler 和建连结果 Promise，根据 connection.type() 区分 TCP 激活与 WS/WSS 握手，不再额外传入或保存 boolean websocket：

| Netty 事件 | 用户回调 |
|---|---|
| TCP channelActive | onConnected |
| WS/WSS 握手完成事件 | onConnected |
| channelRead0 | onMessage |
| channelInactive | onDisconnected |
| exceptionCaught | onException |
| IdleStateEvent | onIdle(connection, IdleType) |

SimpleChannelInboundHandler 使用原生自动释放机制，因此 onMessage 参数是借用引用。回调后保留或回写需要 retain / copy。

TCP、WS/WSS 的建连结果通过 Netty Promise 通知 Client；WS/WSS 等原生握手完成，且用户 onConnected 正常返回后才通知成功。onConnected 抛异常时以原始原因完成失败结果并关闭连接；服务端通过诊断入口记录失败。初始化失败不调用业务 onMessage / onDisconnected，原生 channelInactive 继续传播。这个 Promise 仅用于建连结果，没有终止 Future、额外消息队列或连接状态机。

### 按协议划分 Server / Client

公共 NetworkServer 定义 start / stop；公共 NetworkClient 定义 init / destroy。客户端初始化不使用 start 命名。具体类为 TcpNetworkServer、WsNetworkServer、HttpNetworkServer、TcpNetworkClient、WsNetworkClient。TCP Client 提供 connect(host, port, callback)，WS Client 提供 connect(uri, callback)。

每个 Server 支持同协议的多个 listen。多协议网关由应用组合多个 Server，共享 NetworkResources。WsNetworkServer 配置 sslContext 后支持 WSS；WsNetworkClient 根据每次 URI 选择 WS/WSS。旧通用实现、混合 EndpointSpec 和 Client profile 入口已移除。

TcpNetworkClient 在 init 中创建一个 Bootstrap，group 配置整个 IO EventLoopGroup，后续直接调用该实例的 connect(address)。由 Netty 分配 EventLoop，组件不调用 next、不维护 Bootstrap 映射、不修改共享 Bootstrap 的临时属性。ChannelInitializer 创建属于该 Channel 的结果 Promise；原生建连 Future 监听器读取并解除属性，再通知用户初始化结果。Channel 创建失败等尚无管线结果的情况直接通知原生原因，不将已关闭的失败占位 Channel 加入关闭集合。

WsNetworkClient 同样在 init 中创建一个配置完整 IO EventLoopGroup 的 Bootstrap。connect 调用 Bootstrap.register，注册监听器在该 Channel 的 EventLoop 上创建结果 Promise、配置本次 URI 的管线，然后调用 Channel.connect。共享的 Netty ResolveAddressHandler 使用资源解析器完成异步地址解析，并在转交连接后从该管线移除自身。用户管线在 Channel 已注册但尚未连接时配置，可以接收后续原生 channelActive；不要依赖此前已经发生的 channelRegistered。WS / WSS 原生 Handler 在连接前就位，正常启动握手与超时处理。URI、回调和结果只属于本次调用，不修改共享 Bootstrap、不维护 EventLoop 映射。两个 Client 均以 initialized 的 volatile 发布完整初始化结果，不按连接 new / clone Bootstrap。

Client 的调用顺序是 build → init → 多次 connect → destroy。init 不建立连接，重复调用幂等；未初始化时拒绝 connect，初始化失败清理已持有的资源并终止该实例。destroy 之前可以反复连接和关闭单条 Connection；destroy 之后不能重新 init。无需引入通用生命周期父类。

### 直接管理原生生命周期与资源

具体类直接 implements NetworkServer / NetworkClient，不继承 ComponentSupport 或通用生命周期父类。Server.start 直接创建 ServerBootstrap、配置原生 option / childOption 并绑定监听；部分绑定失败时关闭已创建的 Channel；只将仍打开的监听 Channel 加入 ChannelGroup，避免 Channel 创建失败时原生占位 Channel 破坏回滚和后续 stop。Server.stop 和 Client.destroy 直接关闭原生 ChannelGroup，再释放自有资源；没有后台清理任务、任务集合或独立终止 Future。

Client 每次建连只保留原生 Promise，以及 WS 所需的 URI。就绪、失败和关闭统一尝试完成该 Promise，ConnectCallback 由其监听器通知。不维护 Attempt 状态机、连接计数、连接数量限额或组件整体截止时间。DNS、TCP、TLS、WS 各阶段使用 Netty 自身超时配置。

未显式传入资源时组件拥有资源；显式传入时借用。调用方应先停止服务端并销毁客户端，再关闭共享 NetworkResources。HTTP 与实时 TCP / WS / WSS 使用不同的 IO group。

## 2. 原生扩展

协议预设、用户 Decoder / Encoder、NetworkHandlerBridge 均处于同一个原生 ChannelPipeline。配置时可直接 addLast 用户 Handler，随后组件添加 network.handler 桥接节点。

各具体 Server / Client 的 ChannelInitializer 调用本类私有 initPipeline 方法。已删除 ProtocolSupport，不再通过协议枚举、server 布尔参数和 install 重载分派初始化；TCP、WS 客户端、WS 服务端分别直接配置所需 Handler，HTTP 沿用自己的原生管线。可选空闲检测和用户 Handler 的少量配置代码就近保留在具体类中。

TCP 默认只安装桥接层，业务分帧、Decoder、Encoder 由应用安装。启用空闲选项后才安装 IdleStateHandler。组件不自动把 String 或 byte[] 编码为 ByteBuf。WS/WSS 安装必要的 HTTP Upgrade、WebSocket、TLS 处理器，然后调用用户 pipeline 配置，最后添加桥接层；帧聚合默认禁用，可通过 WS_AGGREGATION 显式启用。

NetworkHandler 收到用户 Decoder 产生的业务对象。connection.write(业务对象) 经用户 Encoder 转成 TCP ByteBuf 或 WS WebSocketFrame，再沿原生出站管线发送。带长度字段的 TCP 管线依次安装分帧 Decoder、LengthFieldPrepender、业务 Decoder、业务 Encoder，使出站先编码业务再添加长度。

不维护保留节点快照，不做节点删除／移动校验，不因用户编辑管线额外关闭连接。运行期可直接操作原生 Pipeline，或使用 NettyAccess.editPipeline 将编辑提交给 EventLoop。编辑遵循原生执行和取消语义。

用户 Handler 是否继续传播 channelActive、channelInactive、消息和异常，决定后续桥接层是否收到事件；组件不会另起观察器补发被消费的事件。需要标准回调时，应保留桥接层及其必需的事件传播。

同一连接在默认 EventLoop 上串行处理消息；不创建业务线程池或禁止同步嵌套的回调队列。原生出站 Promise 的失败不会自动重新投递为 NetworkHandler.onException。

## 3. 关闭与状态

close 直接走 Channel.close，包括 TLS／WS／用户 Handler 的原生关闭处理，不绕过 Pipeline 使用私有 transport context。

isActive、isWritable 和远端地址就是原生观察值，不是组件推导出的逻辑状态。关闭请求与观察到原生关闭之间可能存在时间差。

Server.stop 和 Client.destroy 等待原生关闭 Future，不额外保证所有 Disconnected 用户回调已经返回。用户如果需要等待自己业务清理，应在业务层管理自己的完成信号。回调异常沿原生管线处理，不汇总成额外连接终止异常。

Server.stop / Client.destroy 超时后可以再次调用以等待原生关闭，分别由 STOP_TIMEOUT / DESTROY_TIMEOUT 配置等待期限。共享资源上的排队建连任务会在执行时报告客户端已销毁，结果回调可能晚于 destroy 返回；应用应先停止服务端并销毁客户端，再关闭资源。boundAddresses 保留已成功绑定过的地址，不能据此判断监听仍然活跃。

## 4. 配置与协议范围

支持 Server TCP / WS / WSS / HTTP1.1；Client TCP / WS / WSS。多端点分别监听；HTTP 使用独立原生 HTTP Handler，默认 404，不进入 NetworkHandler。

HttpNetworkServer.Builder.contextPath 设置该实例所有监听地址的统一项目路径，默认空字符串；`/` 等价于根路径，末尾 `/` 归一化移除。非根配置使用以 `/` 开头的字面路径，段内支持 ASCII 字母、数字和 `-._~`，禁止空段、点段、编码、查询与片段。配置在 build 时快照化。

配置非根路径时，原生管线顺序为 `network.httpCodec → network.httpContextPath → network.httpAggregate（启用时）→ 用户 HTTP Handler`。HttpContextPathHandler 只做逐请求路径匹配和改写：`/game/login?x=1` 在 contextPath 为 `/game` 时变为 `/login?x=1`，`/game` 变为 `/`；匹配区分大小写和完整路径段，不解码剩余路径与查询。用户可以通过原生 addBefore / addAfter 在 context 节点两侧扩展，节点之前看到原 URI，之后看到相对项目根路径的 URI。根路径配置不添加该节点。

不匹配请求返回空体 404，释放该请求的 HttpContent，读至 LastHttpContent 后可处理同连接的下一请求；支持聚合和流式模式。拒绝带 Expect: 100-continue 的请求时返回 404 并关闭连接，避免等待客户端不会发送的请求体；其他拒绝响应遵守请求的 keep-alive 语义。匹配的请求继续使用 Netty 原生聚合与 Expect 处理。

反向代理保留项目前缀时，后端配置同一 contextPath；代理移除前缀时，后端使用根路径。此功能不提供 Servlet 容器，不重写响应 Location、Cookie Path 或页面链接，不从代理头推断前缀。

直接开放 ChannelOption、WebSocketServerProtocolConfig / WebSocketClientProtocolConfig 定制器、SslContext / SslHandler、HTTP decoder / aggregation。Netty 原生选项未配置时保留底层默认；无额外收发条数、字节积压预算、消息大小注册或写调度层。

Settings 仅持有 ChannelOption 与 NetworkOption 的不可变 Map 快照，并负责读取组件选项默认值。它不再持有 NetworkHandler 工厂、Pipeline 回调、TLS 或 WebSocket 配置。具体类在构建时保存自身所需的 final 配置引用；之后修改 Builder 的选项或替换回调，不影响已构建实例。回调闭包中的外部可变状态仍由调用方管理。

具体组件向 Settings 声明支持的 NetworkOption，在构建时拒绝不适用的组件选项，错误信息包含选项和 Builder 类型。原生 ChannelOption 不增加组件白名单。组件选项适用表见 README。

资源关闭由 NetworkResources.Builder.shutdownTimeout 设置默认等待期限（5 秒），也可由公开 close(Duration) 覆盖单次调用。它控制自有 EventLoopGroup 的终止等待，与 STOP_TIMEOUT / DESTROY_TIMEOUT 控制的 ChannelGroup 关闭等待分别计时；不是覆盖全部业务清理过程的总截止时间。无效期限在执行关闭前拒绝，关闭超时后可以重试。

OptionsBuilder、ServerBuilder、RealtimeServerBuilder、ClientBuilder 各自位于独立文件：OptionsBuilder 只收集选项；ServerBuilder 管理公共监听地址和资源；RealtimeServerBuilder / ClientBuilder 保存实际共享的业务 Handler 和 Pipeline 配置。TLS 与 WS 定制器字段位于 WsNetworkServer.Builder / WsNetworkClient.Builder 内部，HTTP 配置位于 HttpNetworkServer.Builder。现有公开 Builder 方法不变。

同步生命周期等待期限与协议聚合默认值见 [实现记录](../implementation-status.md)。WS 单帧大小沿用原生协议默认值，通过原生协议 builder 定制；WsNetworkClient 在 init 中创建默认 SslContext，后续连接复用，构建时只保存用户提供的上下文引用。这些不要求其他语言实现同名类型或字段。

## 5. 验证

保留 TCP 顺序和引用责任、多目标、WS/WSS 实际握手、TLS 主机验证、HTTP 独立处理、Client 销毁竞争、原生管线扩展等功能测试。额外测试 NettyConnection 方法与 Channel 行为一致，并覆盖三种 Server 的创建失败回滚、DNS 故障、慢客户端及监控 Handler。Linux Docker 与独立容量测试见 [运行验证](../operations.md)。

不再以“禁止原生回调嵌套、关闭后自动清空属性、保护管线节点、等待写计数归零”作为验收目标。

本版延续 Draft 0.14 的配置归属，完善 onConnected 失败结果、组件选项适用范围和资源关闭配置，并将客户端运行时资源统一在 init 准备。建连路径简化不代表已验证万级连接吞吐提升；历史草案与本版冲突时以本版及用户后续决定为准。
