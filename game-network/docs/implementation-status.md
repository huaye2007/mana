# Java / Netty 当前实现记录

日期：2026-09-07。实现版本：0.1.0-SNAPSHOT。

本记录描述按最新薄封装决定调整后的仓库代码；设计见 Implementation Draft 0.19，标准见 [GNS Draft 0.7 合并版](Game%20Network%20Specification.md)。标准尚为讨论草案，本仓库不宣称已完成全部 GNS 条款、任意用户 Handler 和全部部署组合的合规验收。

## 对照设计的落地选择

- JDK 25、Maven 多模块，Netty BOM 锁定 4.2.15.Final，默认 NIO。
- 公共 NetworkServer / NetworkClient 保留生命周期；具体构建入口按 TCP、WS、HTTP Server 及 TCP、WS Client 区分。协议专属 connect 在具体 Client 上。
- Server 使用 start / stop；Client 使用 init / destroy，先初始化再多次 connect。init 创建 IO 资源、解析器和 Bootstrap；重复 init 不重复创建，destroy 后不可再次初始化。
- 不新增 HandlerPipeline，管线末端的普通原生 Handler 直接调用 NetworkHandler。
- onConnected 正常返回后才通知建连成功；抛异常时保留原始原因、通知失败并关闭连接，不再交付业务消息或业务 onDisconnected。
- 已删除 ProtocolSupport 及其 install 方法；五个具体 Server / Client 各自通过 initPipeline 配置原生 Handler，不再集中判断 TCP / WS / WSS 和客户端 / 服务端。
- 五个具体类直接实现公共接口；移除 ComponentSupport、AbstractNetworkServer、AbstractNetworkClient、CallbackScope。直接调用原生绑定、关闭和资源释放，不增加后台停止任务或客户端 Attempt 状态机。
- 用户通过 pipeline 安装业务 Decoder / Encoder。移除自动 String / byte[] 编码；TCP 默认无业务编解码器，WS/WSS 默认仅协议处理，帧聚合可选。
- NettyConnection 持有 Channel 和不可变 ConnectionType，type 返回 TCP / WS / WSS，id 直接返回底层长格式标识字符串，其他方法直接委托；桥接 Handler 根据 connection.type() 区分就绪事件，在原生 Pipeline 中调用 NetworkHandler，不再保存 websocket 布尔变量。不记录在途写操作，不维护独立连接状态、锁或事件队列。
- Server 只监听自身协议，多协议由应用组合多个 Server；HTTP 与实时连接使用独立 IO group；显式资源借用，内部创建资源自有。
- TLS 使用 WsNetworkServer / WsNetworkClient Builder 的 sslContext / tlsHandler，无额外 profile。
- WsNetworkClient 在 init 中创建默认 SslContext 并复用，构建阶段只保存用户配置。
- 地址与构建配置使用不可变对象或配置快照；配置函数本身由调用方保证线程安全，不应依赖构建后可变的捕获状态。
- Settings 只负责原生和组件选项的不可变快照及默认值读取。业务 Handler、TLS、WS 配置由使用它们的具体类保存；四个公共 Builder 基类分别成文件，公共选项层不包含协议专属字段。
- build 时拒绝组件不支持的 NetworkOption；原生 ChannelOption 不增加组件白名单。支持范围见 README 的组件选项表。
- 普通 Java 对象按 GC 管理；带外部资源的自定义消息通过 Netty ReferenceCounted 契约及原生 Handler 管理，本版没有单独的资源清理策略注册表。
- public API 不使用 CompletionStage；内部完成状态不构成对外异步生命周期 API。
- HTTP/WS 同端口分流不属于当前端点模型。每个端点有独立监听地址，可配置端口 0 后通过 boundAddresses 查询实际地址。

## 组件选项默认值

| 选项 | 默认 |
|---|---|
| READ_IDLE / WRITE_IDLE / ALL_IDLE | Duration.ZERO，禁用 |
| START_TIMEOUT | 30 秒 |
| STOP_TIMEOUT | 10 秒，Server.stop 等待期限 |
| DESTROY_TIMEOUT | 10 秒，Client.destroy 等待期限 |
| NetworkResources.Builder.shutdownTimeout | 5 秒，自有 EventLoopGroup 的终止等待；close(Duration) 可覆盖单次调用 |
| WS_AGGREGATION | 0，禁用；正数启用原生帧聚合 |
| WS_UPGRADE_AGGREGATION | 1 MiB |
| WS 原生单帧上限 | Netty 原生默认，通过原生协议 builder 调整 |
| HTTP 聚合请求 | 1 MiB，通过 httpAggregation 调整，0 禁用 |
| HTTP contextPath | 空字符串，根路径；可设置 /game 等统一项目前缀 |
| 自有 acceptor / 实时 IO / HTTP IO 线程数 | 1 / 可用处理器数 / 可用处理器数 |

已移除 CONNECT_DEADLINE、MAX_CONCURRENT_CONNECTS 和 MAX_CONNECTIONS，不再提供组件连接配额或跨阶段建连计时器。

原生 ChannelOption 不预设新值。TCP 建连期限通过 CONNECT_TIMEOUT_MILLIS 定制；DNS 通过资源 resolver 配置；TLS 通过 tlsHandler 定制；WS 通过 webSocketClient / webSocketServer 的 handshakeTimeoutMillis 定制。聚合、空闲检测使用 Netty 原生设施。

同入口后配置覆盖先配置；每个具体实例拥有独立配置快照。TCP Client 共享一个配置完整 EventLoopGroup 的 Bootstrap，由 Netty 分配连接的 EventLoop；每次结果在 Channel 上传递，不修改共享 Bootstrap 的配置。WS Client 同样共享一个配置完整 EventLoopGroup 的 Bootstrap，先 register，再在 Channel 的 EventLoop 上配置本次 URI 管线，通过原生 ResolveAddressHandler 和 Channel.connect 解析并连接；不保存映射或临时共享属性。两个 Client 均不按连接 new / clone Bootstrap。

STOP_TIMEOUT / DESTROY_TIMEOUT 只控制组件 ChannelGroup 的关闭等待；NetworkResources 的 shutdownTimeout 控制随后自有 EventLoopGroup 的终止等待，分别计时。资源关闭超时后允许重新 close，借用的 EventLoopGroup 不由该资源对象关闭。

## 功能验证

测试源包括原有协议、配置与 Bootstrap 复用测试，以及 ServerFailureTest、DnsFailureTest、SlowPeerTest、TransportMetricsHandlerTest、HttpContextPathHandlerTest。使用真实本机套接字、JDK TLS、临时端口及 Netty paranoid 引用泄漏检测配置。2026-09-07 在 Windows / Oracle JDK 25.0.3 上 Maven verify 通过 42 项测试（无失败、错误或跳过），两个模块 JAR 均构建成功。此前 2026-09-06 的 Linux Docker / Temurin 25.0.4 验证为 37 项，不包含本次 contextPath 新增用例。Linux 原始报告位于 target/linux-verify-tcp；CI 工作流已准备，但尚未推送或在云端运行。

覆盖内容：

- NettyConnection 与原生 Channel 的 ID、状态、地址、写入、关闭及属性对应关系；真实 TCP、WS、WSS 连接的客户端和服务端均返回正确 ConnectionType。

- TCP 100 条有长度帧消息顺序、回写引用与关闭后 write(false)。
- 单 Client 多目标连接、独立连接 ID、单 Server 停止不关闭其他 Server。
- TCP / WS 客户端初始化前拒绝建连；重复 init；关闭单条 Connection 后继续建连；destroy 关闭管理连接并保留借用资源，之后拒绝 init / connect；未初始化也可 destroy。
- 四个调用线程、两批共 128 条 TCP 连接在两个 EventLoop 上分配，始终共享同一个 Bootstrap，两个目标和回调不串用。
- 四个调用线程并发 64 条 WS 连接，跨两个 EventLoop 始终共享同一个 Bootstrap，验证两个目标、不同路径和查询参数不串用，回调各一次。
- 同一 WsNetworkClient 同时连接 WS 与 WSS，分别正常回写；上述测试不是万级连接性能压测。
- 独立 TCP / WS / HTTP Server 共享资源，停止某个 Server 不影响其他 Server；WS 路径、文本往返与 HTTP 默认 404。
- HTTP 原生 Handler 独立 IO；HTTP Handler 被阻塞时实时 TCP 仍可处理消息。
- HTTP contextPath 默认与根路径、末尾斜杠、完整路径段边界、原始查询保留；聚合与流式真实请求、404 请求体释放与下一请求复用，以及拒绝 Expect: 100-continue 时关闭连接。
- 原生入站异常经过桥接层调用 onException，默认关闭；不合成写 Promise 失败事件。
- 统一 READ Idle 回调与原生 Channel 属性读写、移除；关闭不自动清空属性。
- 回调中同步生命周期拒绝，防止网络线程阻塞等待自身。
- 部分监听成功后绑定失败，已绑定端口可重新绑定。
- TCP / WS / HTTP Server 第一次或第二次 Channel 创建失败均保留原始异常，回滚无次生异常，重复 stop 正常，先前监听端口释放，借用资源保持可用。
- 本地 DNS 截断响应通过显式配置的原生 resolver 回退 TCP；NXDOMAIN、查询超时及解析期间销毁均通知一次结果并关闭 Channel，覆盖 TCP / WS。
- 慢客户端停止读取时触发原生水位通知，关闭释放仍积压的直接缓冲；监控 Handler 保留写失败原因并转发原生事件。
- 连接拒绝、Netty 原生握手超时、销毁中断握手均只产生一次结果；握手失败后仍可正常连接其他目标。
- TCP / WS 的 onConnected 抛异常后只通知一次失败、保留原始原因并关闭 Channel。
- TCP / WS 管线初始化异常、Channel 创建失败均保留原始原因，失败后的客户端可以正常销毁。
- 五种组件拒绝不适用的组件选项；自定义资源关闭期限生效、超时后可重试，无效期限不会关闭资源。
- 50 次连接尝试在销毁竞争下最终均有一次结果，不要求所有回调在 destroy 返回前完成。
- destroy 超时后继续等待原生关闭 Future；Disconnected 异常遵守原生管线传播。
- 外部 EventLoopGroup 不被组件或借用它的资源对象关闭。
- 原生自定义对象 Encoder、运行中直接编辑 Pipeline，不维护保留节点。
- build 后修改 Builder 的原生选项、组件选项、WS 协议参数和 Handler / Pipeline 回调，不影响已构建实例；通过实际 WS 分片往返验证快照行为。
- TCP 和 WS 两端安装用户编解码器，NetworkHandler 收发业务对象；TCP 中文消息往返、WS 可选聚合后分片消息往返。
- 并发 write / close 后所有测试 ByteBuf 引用归零。
- WSS 实际加密往返，受信任证书成功，不匹配目标主机失败。

测试证书 localhost-test.p12 位于测试资源目录，别名 localhost，密码 changeit，仅用于测试；不会进入主 JAR。只含 localhost DNS 名称，127.0.0.1 用于验证主机名不匹配失败。

## Linux 短时容量回归

Docker / Temurin 25.0.4、NIO、4 CPU / 6 GiB 限制、同 JVM 两端，开启 paranoid 泄漏检测：

| 协议 | 保持连接数 | 持续秒数 | 核对回写次数 | 重建连接数 | 结果 |
|---|---:|---:|---:|---:|---|
| TCP | 10000 | 120 | 1210000 | 2500 | 通过 |
| WS | 2000 | 60 | 122000 | 500 | 通过 |
| WSS | 1000 | 60 | 61000 | 250 | 通过 |

实际 allocator 为 AdaptiveByteBufAllocator，三组测试关闭后的直接内存采样均为零，FD 均回到基线附近。完整环境、RTT、CPU、内存数据和原始 JSON 见 [Linux 验证报告](linux-validation-2026-09-06.md)；监控与 DNS 示例、复现命令见 [运行验证](operations.md)。这些结果不代表跨机生产延迟、吞吐上限或小时/天级稳定性已经验收。

## 尚未验证的范围

独立生产 Linux 主机 / macOS、epoll / kqueue / io_uring、OpenSSL provider、IPv6、复杂 DNS 部署与服务器切换、小时/天级持续压力、所有 TLS 待写缓冲关闭组合、任意用户异步 Handler。已验证的 Linux 环境为 Docker Desktop / WSL2 内核上的 NIO。用户若主动关闭借用的 EventLoopGroup、破坏底层原生节点、吞掉 Promise 或异步重排消息，不能据此测试结果推断仍满足生命周期保证。

本版 Windows 测试将 jdk.net.unixdomain.tmpdir 指向模块 target；本机默认临时目录曾导致 JDK Selector 的 AF_UNIX 连接初始化失败，采用项目目录后真实测试能够运行。该设置不进入组件运行逻辑。
