# 架构与执行契约

本实现依据引用对话的最新结论；较早版本中预置 Domain、自动 getter 猜测、ReplyHandle、Metadata type 字段等方案均未采用。引用对话的历史下载附件不在当前仓库，本仓库代码、文档和测试共同定义当前 Java binding。

分组件条款及 Java 开发规范 见 [OGBS 1.0 文档索引](ogbs/README.md)。本文保留组合视角，具体接口与边界以对应组件文档及当前源码为准。

## Network / RPC 包边界

Java Network 接口与 Netty 实现在 game-network 内统一发布；RPC 依赖 game-network 和 game-core，已实现内部 TCP 与多 Slot 通信。Network 按 connection、connector、error、netty 分包，直接使用 Netty AttributeKey；RPC 按 node、message、call、transport、error、netty 分包。

目录划分保留包级封装：Network 的 NettyConnection/NetworkPipeline 留在 netty 包内，RPC 的 ConnectionSlot 留在 node 包内。调用方需要更新已迁移类型的 import。具体类型映射见 [Network Java 开发规范](ogbs/OGBS-Network-Java-25-Specification-1.0.md) 和 [RPC Java 开发规范](ogbs/OGBS-RPC-Java-25-Specification-1.0.md)。

## Runtime 包边界

根包 `cn.managame.runtime` 只保留 GameRuntime 和 GameRuntimeBuilder。公开 API 按 context、route、executor、protocol、handler、event、timer、time、error 拆分；编译与运行期装配放在 internal。Builder 负责配置收集，RuntimeCompiler 负责注册校验与方法编译，RuntimeTimers 负责一次性延迟，DefaultCronScheduler 负责周期管理。目录与迁移说明见 [game-runtime](../game-runtime/README.md)。

## 配置与所有权

所有 Runtime 配置都通过批量 Builder 输入。build 收集、校验、编译 MethodHandle、冻结注册表，再启动调度资源。校验失败不接管调用方传入的执行器。成功后 Runtime 负责对每个唯一 RouteExecutor 调用一次 close。

NetworkServer/NetworkClient 默认创建 NIO EventLoopGroup 并在 close 时同步关闭自有资源。显式传入的 group 不由框架关闭。Server 同步 start，只能启动一次；Client close 取消未完成建连。成功 Connection 由上层持有，外部 group 上的成功连接不因 Server/Client close 被主动关闭。详细语义见 Network 两层规范。

RPC Node 自己创建并同步关闭 NetworkServer、NetworkClient、自有 NIO groups、时间轮、全部连接与 PendingCall。其 Builder 不接受应用的 Network 实例或 EventLoopGroup。

官方 RouteExecutor 的 close 拒绝新任务并异步排空已经 ACCEPTED 的任务，不等待工作线程，允许从 Handler 内调用。应用负责整个服务器的停服顺序，并应在关闭 Runtime 前处理需要保留的异步业务。

## Network

当前实现为单一 game-network 模块。通用契约以 [Network Specification](ogbs/OGBS-Network-1.0.md) 为准，Java 开发规范、默认配置、线程及资源所有权以 [Network Java 开发规范](ogbs/OGBS-Network-Java-25-Specification-1.0.md) 为准。

NetworkServer / NetworkClient 提供 TCP、TLS TCP、Binary WebSocket 和 WSS。全部 Transport 握手成功后才创建 Connection；不暴露公共 ready 状态。ConnectionHandler 在 Channel EventLoop 串行接收 onConnected、onMessage/onEvent/onException、onDisconnected。

write 检查 active/writable 后直接 writeAndFlush；ACCEPTED 转移所有权但不保证送达，INACTIVE/NOT_WRITABLE 保留调用方所有权。没有框架发送队列或自动重试。异步写入及业务回调异常交给 onException，普通异常不自动关闭；协议非法消息由 Transport 拒绝并关闭。

入站 ReferenceCounted 在 onMessage 范围内借用，返回或异常后由框架释放；异步持有/回写需 retain。属性直接使用 Netty AttributeKey，不额外定义关闭冻结/清空规则。

Pipeline 为 Transport handlers → binary adapter（WS）→ 用户 codec/handler → ConnectionHandler adapter。内部生命周期 gate 消费 TLS/WS 建立事件；发送异常入口避免 Netty WS protocol handler 将普通编码失败自动解释成连接关闭。高级用户直接配置 ChannelPipeline、ChannelOption、EventLoopGroup、ChannelFactory 和 SslContext。

Network 示例为 NetworkEchoExample，RPC 双节点示例为 RpcEchoExample；自动 RPC→Runtime 接入尚未实现。
## RPC

[game-rpc](../game-rpc/README.md) 已提供 RpcNode Builder、统一 RpcHandler 和泛型 RpcCallback。RPC 不自动解释业务 body、恢复 Runtime Context 或执行业务 callback；应用接入层负责这些工作。

主动 Peer 由 addPeer 注册，维护固定数量 Slot；合法入站握手可以创建被动 Peer。被动 Peer 无连接且无 PendingCall 时回收，也可以原地升级为主动 Peer。Peer/Slot/调用内部状态保持包级封装。

call/notify 按非零 routeKey 的无符号余数选起点，零值 round-robin；reply 优先实际来源 Slot，再按 routeKey 回退。首个 ACCEPTED 后不重发。requestId 仅在 Peer 内匹配调用，响应可以从任意 Slot 返回。

每个 Node 一个 HashedWheelTimer 负责调用超时、握手超时与固定重连延迟；连接 IdleStateHandler 负责心跳。断线不立即失败已接纳调用。远端所有合法错误响应交给 onResponse，本地可用性/超时/生命周期竞争走 onFail。错误码区间由 Core 定义，无高位封装。

出站 ByteBuf body 经参数和生命周期校验后被消费，编码复制到单个连续 frame；入站 body 在回调内借用，需要 retain/copy 才能跨线程使用。消息使用只读 record，发送 ID 在内部编码时赋予，不公开 requestId setter。

start/close 只在管理上下文执行；关闭立即拒绝新工作，并等待已接纳 RPC 操作、自有网络与时间轮退出，不等待应用另行投递的业务。完整边界、默认值、取舍与验证见 [RPC 两层规范](ogbs/OGBS-RPC-Java-25-Specification-1.0.md)。

## Runtime

Route identity 是完整的 domain + key，不是 worker/thread/executor。key=0 无效，其他 64 位值可用。Domain 为用户定义的正整数。

默认平台线程执行器按完整 Route hash 分 Stripe，每个 Stripe 单线程有界队列；不同 Route 可能共享 Stripe。虚拟线程执行器按完整 Route 维护 FIFO mailbox，一个活跃 mailbox 由一个虚拟线程排空，空 mailbox 回收。两种实现均隔离任务异常并提供非阻塞 admission。

Runtime 负责 same-route inline，Executor SPI 不感知 Context。同 Route 嵌套先执行内层任务，内层返回后恢复外层 Context。不同 Runtime 实例即使 domain/key 相同也不 inline。

Contexts 使用 Java 25 ScopedValue，且只在真正执行任务时绑定。无当前 Context 时 current() 抛异常，currentOrNull() 返回 null。

| 执行入口 | Context | 继承调用身份与 Metadata |
| --- | --- | --- |
| dispatch | 调用方创建的 HandlerContext | 接入方明确提供 |
| Event | DefaultEventContext | 从当前同 Runtime InvocationContext 继承 |
| Timer/Cron | DefaultTimerContext | 不继承 |
| call action | DefaultRouteCallContext | 从来源 InvocationContext 继承 |
| call callback | 原始来源 Context | 完整恢复同一对象 |

ProtocolRegistry 只索引 (type,command) ↔ MessageClass 与 Req→Res。MessageClass 唯一，request/response 可同 command。Registry 不包含 Codec、Handler 或 Route。

RouteKeyRegistry 是单独的 exact-class 查询：没有绑定返回 0，extractor 异常按调用方异常传播，不做继承查找或命名猜测。dispatch 不查询它，只验证 Context 中的实际 Route。

Handler 方法必须为 public、非 static、返回 void，且只有一个已注册 REQUEST/NOTIFY 参数，可以另带一个 Context 参数（支持业务子类型及参数顺序互换）。HandlerMethod 的非零 domain 覆盖 Handler.domain。重复 message handler、非法 Context 类型、未注册 Domain 等在 build 时失败。

Event 通过自己的 routeDomain()/routeKey() 指定唯一 Route。EventMethod 按具体 Event 类 exact lookup、order 升序运行；相同 order 无相对顺序保证。单个监听方法失败上报后继续其他监听方法。

GameTime 是进程级可替换的业务墙钟，默认 Clock.systemUTC()；setClock/resetClock 不自动调整 Timer 或 Cron。RuntimeTimer 只关心单调 delay。动态业务 deadline 由应用根据 GameTime 换算并在需要时 cancel + schedule。

TimerRef.cancel 只在到期任务尚未被调度线程认领时返回 true；认领后不会从 Route 队列删除任务。Timer 和 Cron 的业务动作始终进入 Route 执行，异常不重试。Cron 在本轮方法结束后重新读取 GameTime、计算下一次触发，并注册新的普通 Timer；Route 接纳失败时报告错误后也继续下一周期。

Cron 是六字段数字子集，支持 wildcard、问号（仅日/周字段）、列表、区间、步长。日和周同时限定时按 AND 匹配。默认 UTC，可通过 cronZone 配置 ZoneId；表达式必须在未来八年内存在一次匹配。错过的历史触发不补跑，按当前 GameTime 计算下次执行。

runtime.cron() 使用声明类 + 方法名管理 Cron。cancel 停止后续周期，reschedule 取消旧 Timer 并重新计算，rescheduleAll 重排所有注册项（包含已取消项）。重排代次阻止旧任务恢复旧周期；已经开始的业务方法允许完成。继承且未覆盖的方法使用父类作为 Key，重复 Key 在 build 时拒绝。

runtime.call 的 action 业务失败应作为正常结果返回。action 抛异常或目标无法接纳时 onFail；同 Route 调用可同步完成回调。跨 Route callback 仍参与原 Route 的队列顺序。回原 Route 失败时只报告 ROUTE_CALLBACK_DISPATCH_FAILED，绝不在目标线程直接调用业务 callback。

同步 dispatch 接入错误抛 RuntimeDispatchException；任务开始后的异常由 RuntimeErrorHandler 接收。ErrorHandler 自己抛异常时兜底记录，保持 worker 可用。

## 可扩展点

- 自定义 RouteExecutor 并显式绑定 Domain。
- 继承 DefaultHandlerContext 增加 Session/reply 等业务能力。
- ProtocolProvider 可由外部生成器生成。
- MetadataKey 自带编码规则。
- Netty pipeline 直接放入原生 Decoder/Encoder、IdleStateHandler、LoggingHandler、FlushConsolidationHandler。
- RpcHandler 统一解码、解释远端错误并投递 Runtime；外部服务发现通过 addPeer/removePeer 更新拓扑。

Spring 扫描、Protobuf 生成、跨节点 Router 与游戏业务不属于这些组件的 Core；数据库接入由独立 game-data 负责。

## Data 组合与所有权

game-data 依赖 game-core，按 annotation/key/meta/mapper/codec/error/mysql/mongo 分包；Repository 与写回包级实现位于 cn.managame.data。MySQL/MongoDB 与缓存核心在同一 artifact 发布，Mongo Driver 为 optional 依赖。game-examples 尚未实现；Data 的无数据库测试位于 game-data。

Data 不依赖 Runtime 或 RPC；应用可在已有 Route 上串行业务访问。get/getGroup 缓存未命中会同步访问存储，调用方需考虑 Route 执行线程上的数据库延迟。同 Route 的顺序不意味着后台序列化线程看到了多字段原子快照；实体并发可见性仍需应用保证。

GameDataBuilder 校验 Repository/身份及映射、执行状态 Schema 初始化，再启动一条保存流水线。DataSource、MongoClient 由应用持有；GameData.close 只停止自己的调度并同步排空写回/日志，最终保存失败抛 DataSaveException。停服先停止业务入口并等待已接纳业务，再关闭 GameData，最后关闭数据库客户端。

Data 采用两个缓冲与固定 100ms 宽限期，接受超长线程停顿风险；没有 WAL 或容量背压。所有未验证的生产及实机边界见 [Data Java 开发规范](ogbs/OGBS-Data-Java-25-Specification-1.0.md)。通用行为见 [Data Specification](ogbs/OGBS-Data-1.0.md)，共享保存错误码见 [Core](ogbs/OGBS-Core-1.0.md)。


