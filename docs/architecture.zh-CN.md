# 架构与执行契约

[English](architecture.md) | **[简体中文](architecture.zh-CN.md)**

本架构以仓库内的 OGBS 标准规范和 Java 开发规范为基线，源码与测试提供实现和验证入口。Domain 由应用显式注册，RouteKey 由显式提取器或接入层计算，响应发送由接入层负责，Metadata 不携带 type 字段。

分组件条款及 Java 开发规范 见 [OGBS 1.0 文档索引](ogbs/README.zh-CN.md)。本文保留组合视角，具体接口与边界以对应组件文档及当前源码为准。

## 应用骨架

[game-demo](../game-demo/README.zh-CN.md) 是普通 Spring 应用，继承仓库 Java 25 基线，不新增框架组件。GameDemo 初始化 Spring Context 并执行用户/角色写入示例，不包含阻塞等待；MysqlConfig 持有 Hikari DataSource。GameDataConfig 收集组件扫描得到的业务 `@Repository` 类型，通过 GameDataBuilder.mysql(source) 构建 Data，并将 Data 初始化的实例作为对应 Spring Bean。Bean 依赖保证 Repository Bean 先于 Data、Data 先于应用连接池关闭，不再构造第二个未初始化 Repository；既有可运行组件示例仍位于 game-example。Spring 依赖管理及 Repository 适配仅位于 game-demo，框架模块保持既有依赖边界。

## Network / RPC 包边界

Java Network 接口与 Netty 实现在 game-network 内统一发布；RPC 依赖 game-network 和 game-core，已实现内部 TCP 与多 Slot 通信。Network 按 connection、connector、error、netty 及独立 http 包组织，直接使用 Netty AttributeKey；RPC 按 node、message、call、transport、error、netty 分包。

目录划分保留包级封装：Network 的 NettyConnection/NetworkChannelInitializer 留在 netty 包内，RPC 的 ConnectionSlot 留在 node 包内。调用方需要更新已迁移类型的 import。具体类型映射见 [Network Java 开发规范](ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) 和 [RPC Java 开发规范](ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)。

游戏服务器内部 HTTP 在 game-network artifact 的 cn.managame.network.http 包中独立实现。HttpServer 自行管理 ServerBootstrap、监听、生命周期和请求/响应管线，不包装 TCP/WS 的 NetworkServer，不使用 Connection/ConnectionHandler。包内公开 HttpServer/HttpServerBuilder/HttpResponseCallback，HttpServerTransport 保持包级封装。健康检查、管理操作、路由和序列化由应用负责。初始 Profile 仅支持 HTTP/1.1、完整请求/响应 body、Keep-Alive、可配置请求限制及入站无数据超时。pipeline(...) 在聚合之后、可选兜底之前安装原生 HTTP 扩展（默认兜底为 404），包括应用选择的鉴权、CORS 和压缩。同步 handler 与 asyncHandler 回调共享逐连接顺序：等待响应会延迟后续请求处理及自动协议响应，不阻塞传输线程。可选外部有序执行器与 HTTP 扩展共享该上下文；异步使用请求需要独立所有权，晚到/重复回调响应释放。不新增客户端、HTTP/2、连接注册表或业务定时器。契约见 [HTTP Profile](ogbs/OGBS-Network-1.0.zh-CN.md#http-server-profile) 与 [Java 绑定](ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md#native-http-server-api)。HttpServerExample 位于 game-example 的 cn.managame.example.network，无需新增 Maven 模块或依赖。

## Runtime 包边界

根包 `cn.managame.runtime` 只保留 GameRuntime 和 GameRuntimeBuilder。公开 API 按 context、route、executor、protocol、handler、http、event、timer、time、error 拆分；编译与运行期装配放在 internal。Builder 负责配置收集，RuntimeCompiler 负责注册校验与方法编译，RuntimeTimers 负责一次性延迟，DefaultCronScheduler 负责周期管理。目录与迁移说明见 [game-runtime](../game-runtime/README.zh-CN.md)。

## 配置与所有权

所有 Runtime 配置都通过批量 Builder 输入。build 收集、校验、编译 MethodHandle、冻结注册表，再启动调度资源。校验失败不接管调用方传入的执行器。成功后 Runtime 负责对每个唯一 RouteExecutor 调用一次 close。

NetworkServer/NetworkClient 默认创建 NIO EventLoopGroup，同步回收自有资源。Server 只能启动一次，close 停止监听；Client close 拒绝新尝试。两者不保存连接或未完成尝试集合。外部 group 的已有 Channel 由调用方管理，握手继续到自身结果/超时，就绪检查观察到入口关闭时拒绝交付；竞争与所有权详见 Network 两层规范。

RPC Node 自己创建并同步关闭 NetworkServer、NetworkClient、自有 NIO groups、时间轮、全部连接与 PendingCall。其 Builder 不接受应用的 Network 实例或 EventLoopGroup。

官方 RouteExecutor 的 close 拒绝新任务并异步排空已经 ACCEPTED 的任务，不等待工作线程，允许从 Handler 内调用。应用负责整个服务器的停服顺序，并应在关闭 Runtime 前处理需要保留的异步业务。

## Network

当前实现为单一 game-network 模块。通用契约以 [Network Specification](ogbs/OGBS-Network-1.0.zh-CN.md) 为准，Java 开发规范、默认配置、线程及资源所有权以 [Network Java 开发规范](ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) 为准。

NetworkServer / NetworkClient 提供 TCP、TLS TCP、Binary WebSocket 和 WSS。全部 Transport 握手成功后才创建 Connection；不暴露公共 ready 状态。ConnectionHandler 在 Channel EventLoop 串行接收 onConnected、onMessage/onEvent/onException、onDisconnected。

write 检查 active/writable 后直接 writeAndFlush；ACCEPTED 转移所有权但不保证送达，INACTIVE/NOT_WRITABLE 保留调用方所有权。没有框架发送队列或自动重试。异步写入及业务回调异常交给 onException，普通异常不自动关闭；协议非法消息由 Transport 拒绝并关闭。

入站 ReferenceCounted 在 onMessage 范围内借用，返回或异常后由框架释放；异步持有/回写需 retain。属性直接使用 Netty AttributeKey，不额外定义关闭冻结/清空规则。

Pipeline 为调用方提供的 SslHandler（可选，首位）→ 发送异常入口 → HTTP/WS handler 与 binary adapter（启用时）→ 用户 codec/handler → ConnectionHandler adapter。NetworkChannelInitializer 装配具体 handler，不使用通用 Transport 接口或 TLS 包装。末端 adapter 直接衔接原生 TLS/WS 完成事件、入口检查与 onConnected，不创建中间就绪/WS Promise 或 PromiseCombiner；Client 仅用原生 Promise 承接建连结果。不保存连接清单，不使用 ChannelGroup 或集合锁；单 Channel 处理将消息交给传入的 ConnectionHandler。TLS context、证书和主机名校验由调用方通过 pipeline(...) 配置，WSS 必须显式配置 TLS；位置与失败规则见 Network Java 规范。

[game-example](../game-example/README.zh-CN.md) 统一收纳独立可运行示例及其执行测试，按 `cn.managame.example.<component>` 分包。Network 的 TCP 示例为 NetworkEchoExample，HTTP/1.1 同步示例为 HttpServerExample，异步回调示例为 HttpAsyncServerExample，RPC 双节点示例为 RpcEchoExample。模块依赖 game-network、game-runtime 与 game-rpc；框架模块不反向依赖示例，也不发布示例类。其他组件在有实际示例时再添加依赖。示例是已有契约的应用演示，不是新的框架组件或规范层。自动 RPC→Runtime 接入尚未实现。
## RPC

[game-rpc](../game-rpc/README.zh-CN.md) 已提供 RpcNode Builder、统一 RpcHandler 和泛型 RpcCallback。RPC 不自动解释业务 body、恢复 Runtime Context 或执行业务 callback；应用接入层负责这些工作。

主动 Peer 由 addPeer 注册，维护固定数量 Slot；合法入站握手可以创建被动 Peer。被动 Peer 无连接且无 PendingCall 时回收，也可以原地升级为主动 Peer。Peer/Slot/调用内部状态保持包级封装。

call/notify 按非零 routeKey 的无符号余数选起点，零值 round-robin；reply 优先实际来源 Slot，再按 routeKey 回退。首个 ACCEPTED 后不重发。requestId 仅在 Peer 内匹配调用，响应可以从任意 Slot 返回。

每个 Node 一个 HashedWheelTimer 负责调用超时、握手超时与固定延迟重连；连接 IdleStateHandler 负责心跳。当前还原后的源码在 Peer 重建时重置调用 ID，没有配置调用接纳上限，直接在时间轮执行超时 onFail，关闭时为每个 Peer 扫描全局连接集合。已确认缺陷与验证状态见 [RPC Java 9.1](ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#91-审阅确认的缺陷与规模风险)，此前修复声明不代表当前源码。断线不立即失败已接纳调用。远端所有合法错误响应交给 onResponse，本地可用性/超时/生命周期竞争走 onFail。错误码区间由 Core 定义，无高位封装。

出站 ByteBuf body 经参数和生命周期校验后被消费，编码复制到单个连续 frame；入站 body 在回调内借用，需要 retain/copy 才能跨线程使用。消息使用只读 record，发送 ID 在内部编码时赋予，不公开 requestId setter。

start/close 只在管理上下文执行；关闭立即拒绝新工作，并等待已接纳 RPC 操作、自有网络与时间轮退出，不等待应用另行投递的业务。完整边界、默认值、取舍与验证见 [RPC 两层规范](ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)。

## Runtime

Route identity 是完整的 domain + key，不是 worker/thread/executor。key=0 无效，其他 64 位值可用。Domain 为用户定义的正整数。

默认平台线程执行器按完整 Route hash 分 Stripe，每个 Stripe 单线程有界队列；不同 Route 可能共享 Stripe。虚拟线程执行器按完整 Route 在 ConcurrentHashMap 持有活跃 FIFO mailbox，仅把完全空闲的 mailbox 放入 Caffeine 有界复用（默认 60 秒，缓存最大条数等于任务容量）。按 Key 的原子 Map 操作协调激活/排空，不使用执行器全局监视器。Caffeine 从 game-core 传递引入，使用默认被动维护，不配置过期调度器。过期 mailbox 不可复用，物理回收可等待后续缓存访问。两种实现均隔离任务异常并提供非阻塞 admission。

Runtime 负责 same-route inline，Executor SPI 不感知 Context。同 Route 嵌套先执行内层任务，内层返回后恢复外层 Context。不同 Runtime 实例即使 domain/key 相同也不 inline。

Contexts 使用 Java 25 ScopedValue，且只在真正执行任务时绑定。无当前 Context 时 current() 抛异常，currentOrNull() 返回 null。

| 执行入口 | Context | 继承调用身份与 Metadata |
| --- | --- | --- |
| dispatch | 调用方创建的 HandlerContext | 接入方明确提供 |
| HTTP dispatch | 默认或工厂创建的 HttpContext | 不隐式提供业务身份/Metadata |
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

game-data 依赖 game-core，按 annotation/key/meta/mapper/codec/error/mysql/mongo 分包；Repository 与写回包级实现位于 cn.managame.data。MySQL/MongoDB 与缓存核心在同一 artifact 发布，Mongo Driver 为 optional 依赖。Data 可运行示例尚未实现；Data 的无数据库契约测试仍位于 game-data。

Data 不依赖 Runtime 或 RPC；应用可在已有 Route 上串行业务访问。get/getGroup 缓存未命中会同步访问存储，调用方需考虑 Route 执行线程上的数据库延迟。同 Route 的顺序不意味着后台序列化线程看到了多字段原子快照；实体并发可见性仍需应用保证。

GameDataBuilder 校验 Repository/身份及映射、执行状态 Schema 初始化，再启动一条保存流水线。DataSource、MongoClient 由应用持有；GameDataBuilder.mysql(DataSource) 装配默认 JDBC Access/Mapper，默认 JSON 初始化绑定声明泛型和状态初始化实现类，可覆盖 codec，具体约定见 [Data Java §6.2](ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding)；GameData.close 只停止自己的调度并同步排空写回/日志，最终保存失败抛 DataSaveException。停服先停止业务入口并等待已接纳业务，再关闭 GameData，最后关闭数据库客户端。

Data 采用两个缓冲与固定 100ms 宽限期，接受超长线程停顿风险；没有 WAL 或容量背压。所有未验证的生产及实机边界见 [Data Java 开发规范](ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md)。通用行为见 [Data Specification](ogbs/OGBS-Data-1.0.zh-CN.md)，共享保存错误码见 [Core](ogbs/OGBS-Core-1.0.zh-CN.md)。

<a id="runtime-http-integration"></a>

## Runtime HTTP 组合

game-runtime 依赖 game-core 与 game-network，在已有 artifact 中发布 cn.managame.runtime.http。显式 HttpHandler/HttpMethod 注册编译 HttpRequestMethod 枚举/原始 path 查找，方法默认 POST，GET 需显式选择；显式 RouteKey 字段规则在 GET 读取 query，其他方法读取 JSON body，方法配置覆盖 Handler；也可指定 Handler 方法提取自定义 Key。HttpContextFactory 接收并保留选定 Key，可提供自定义 HTTP Context 字段，不包含框架业务身份/Metadata；无规则时由工厂选 Key。JSON 字段提取与结果编码使用 Jackson Databind 2.21.3 及传递 Core/Annotations。RuntimeHttp 接到 HttpServer.asyncHandler，与普通 Handler/Event/call 使用相同 Route 执行器和上下文路径。返回业务对象自动完成，void 方法通过 HttpResultCallback 提交对象。HttpResultCodec 默认编码 JSON，RuntimeHttp 在内部构造 Network 响应，业务结果不携带 HTTP 版本。不绑定请求 DTO 或鉴权。

Runtime 对已接纳请求 retain 到方法返回，不延长到延迟回复。跨 Route 回调恢复同一 HttpContext，但不延长请求生命周期。不增加监听器/执行器所有权：应用单独创建、启动、关闭 HttpServer，在关闭 Runtime 前安排在途完成。详见 [Runtime HTTP 语义](ogbs/OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile)、[Java 绑定](ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api)、[RuntimeHttpExample](../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java)。普通消息契约和独立 Network HTTP pipeline 保持原有行为，RPC 接入仍需显式实现/待完善。
