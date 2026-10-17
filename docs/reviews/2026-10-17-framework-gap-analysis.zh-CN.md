# 框架整体缺口分析（2026-10-17）

[English](2026-10-17-framework-gap-analysis.md) | **[简体中文](2026-10-17-framework-gap-analysis.zh-CN.md)**

> 非规范记录。当前契约由各组件规范持有；本页记录一次全仓库审阅的发现、每条发现的证据，以及补齐它需要什么。
> 本文不改变任何契约。上一次审阅已决策的条目不再重开，参见
> [2026-10-14 框架审阅](2026-10-14-framework-review.zh-CN.md)。
>
> 本记录写成之后当天已修复：§4.1 的残留导入与 Checkstyle 的扫描范围，`mvn clean verify -Pquality` 现已对
> 全部模块通过；§5.1/§5.2 英中两版的"有限准入"表述，
> 术语统一为**有限调用接纳**，并在规定该上限的 R-SEND-04 处给出名称；以及 §5.3 的 `getAsync` 说法。
> 因此 §6 第 1–3 条已完成，§5.5 记录了修复过程中发现的一处相关过时表述。以下发现作为本次审阅的记录保留。

## 1. 范围与方法

七个框架组件（game-core、game-network、game-rpc、game-router、game-runtime、game-data、game-spring）连同
game-demo 与 game-benchmark，均对照其两层规范通读。问题不是"哪几行可以写得更好"——2026-10-14 审阅已覆盖
——而是**一个团队要把这套框架用于生产，还必须自己构建或自己承担什么**。

在本工作树上实测（提交 `f6f555f`，JDK 25，Windows）：

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 测试 | `mvn -B -fae test` | **通过** —— 358 个测试，0 失败，0 跳过，9 个模块全绿 |
| 风格门禁 | `mvn -B -Pquality verify` | **失败** —— 1 条违规，构建在 game-runtime 停止 |
| 静态分析 | `mvn -B -Perrorprone clean compile` | **失败** —— 121 条去重发现，75 条为 ERROR，game-data 无法编译 |
| 真实数据库 | `DatabaseIntegrationTest` | **未运行** —— 受 `OGBS_DATA_MYSQL_URL` / `OGBS_DATA_MONGO_URI` 门控 |

发现按代价分组：能力缺口（§2）、数据路径风险（§3）、门禁与构建（§4）、规范一致性（§5）。§6 给出建议顺序。

## 2. 能力缺口：框架未提供什么

这些都不是违反规范的缺陷。每一条要么被文档声明为范围外，要么只是不存在。合起来它们决定了一个生产级游戏后端
还有多大比例仍是应用自己的问题。

### 2.1 没有集群面

拓扑靠手工接线，而且要接两次，身份靠人工分配：

- `RpcNode.addPeer(int remoteNodeId, SocketAddress address, int slotCount)`
  （[RpcNode.java:192](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java#L192)）——调用方同时
  提供对端 Node ID 与地址。
- `ServiceRouting.addRouter(int routerId)`
  （[ServiceRouting.java:76](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java#L76)）
  只声明一个 Router，其自身 Javadoc 写明"应用另行用 RpcNode.addPeer 连接它"。两处登记、两个事实来源、没有
  对账机制。
- `nodeId` 是运维必须保证全局唯一的 `int`；没有任何分配、租约或校验。一个节点声称已被占用的 ID，属于配置错误
  且没有任何检测路径。
- 地址就是应用传入的那个 `SocketAddress`，恢复路径复用同一对象
  （[RpcNode.java](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java) 的 `connect` →
  `client.connectAsync(peer.target, …)`）。因此一个已解析的 `InetSocketAddress` 会把一个 IP 钉死在进程生命周期
  内，对端迁移（新 Pod、新主机）后永远连不上。是否重解析，取决于调用方构造地址的细节，而没有任何规范对此
  表态。

RPC 规范已指出这点："TLS/WS RPC Builder 和发现尚未实现"
（[OGBS-RPC-1.0.zh-CN.md:164](../ogbs/OGBS-RPC-1.0.zh-CN.md)）。但后果比"发现"更宽：没有节点元数据（服务、
版本、可用区、容量），没有成员视图，没有健康传播，也无法向集群宣告"我正在摘流"。横向扩容与滚动重启需要一个
控制面，而框架既未提供也未规范。

### 2.2 路由止于 Node ID——位置解析缺失

Router 按精确目标转发。`ServiceRouting` 的契约写得很明确："目标是精确 Node ID；把业务键（一个玩家、一个房间）
解析到 Node 属于应用职责。"

现有：`DIRECT`（单个 Node ID）、`BROADCAST`（挂在所选 Router 上的该服务全部节点）、`RESPONSE`。框架中完全
不存在：

- 实体 → 节点归属登记（此刻哪个节点持有玩家 10001），
- 服务级分片表或一致性哈希，
- 带负载均衡的"调用服务 X 的任意节点"，
- 实体在节点间的交接/迁移，以及让交接安全的 fencing。

广播在构造上也是最大努力：只挑选一个 Router
（[ServiceRouting.java:142](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java#L142)），
投递范围就是那一刻挂在**该 Router**上的节点。稳态下每个服务都挂到每个 Router，集合是完整的；但在预热期、
Router 重启或网络分区时它会静默不完整，没有确认，调用方也无从知道漏掉了谁。

### 2.3 Route 串行承诺只在进程内，跨集群无人保证

这是后果最重的缺口。Runtime 保证同一 `(domain, key)` 的任务串行、按提交顺序执行——
`RouteExecutor.tryExecute`
（[RouteExecutor.java:5](../../game-runtime/src/main/java/cn/managame/runtime/executor/RouteExecutor.java#L5)）
——**在一个进程内**。`EntityCache` 与 `PendingBuffer` 同样是进程级，Data 规范把边界讲得很直白："当前没有承诺 WAL、
崩溃恢复、无界积压保护、跨表事务或多进程状态一致性"
（[OGBS-Data-1.0.zh-CN.md:246](../ogbs/OGBS-Data-1.0.zh-CN.md)）。

把 §2.1–2.3 合起来，失效模式就很具体了。没有任何机制决定哪个节点拥有玩家 10001；一旦两个节点同时服务该玩家
——客户端重连到另一个网关、一次故障切换、一次 Router 分区、一次运维失误——那么：

1. 两个进程各自把同一行加载进自己的 `EntityCache`，
2. 两边各自本地串行，都看不到冲突，
3. 两边各自把变更记入自己的 `PendingBuffer`，
4. 两边都写整行（`update` 保存全部可变状态，D-SINGLE-02），后落盘的那次胜出。

结果是静默丢更新。Data 层没有行版本、没有乐观并发列、没有租约、没有 epoch fence，所以这种碰撞既不被阻止，
事后也无法发现。Router 的 `nodeEpoch` 只对**消息投递**做了化身级 fencing，对**存储**没有任何 fencing。

框架把放置策略留给应用是合理的。但同时留下放置策略**并且**不给应用任何让归属变安全的原语，这才是最该先补的
缺口。

### 2.4 线上协议没有截止时间、取消与追踪上下文

请求信封（[rpc-wire.zh-CN.md](../rpc-wire.zh-CN.md)）承载 `command`、`requestId`、`routeKey`、
`businessIdType`、`businessId`、`metadata`、`body`。没有 deadline，没有 trace ID，没有幂等键。

- 超时只属于发起节点（`RpcNode.call(…, timeoutMillis, …)`）。Router 以 notify 转发，目标对调用方的截止时间
  一无所知，于是调用方放弃之后目标仍在执行，并把响应回到一个被丢弃的通道
  （`GameRouter.Stats.responsesDropped`）。框架中任何地方都没有取消信号。
- 没有关联标识，日志用 `System.Logger` 且没有 MDC 等价物，所以一次玩家操作无法沿网关 → Router → 服务 →
  数据库被串起来看。
- Core 对**错误码**区间划分得很仔细（1..999、1000..1999 game-network、……5000..5999 game-router，
  [OGBS-Core-1.0.zh-CN.md:50](../ogbs/OGBS-Core-1.0.zh-CN.md)），但**没有为框架保留任何 MetadataKey id
  区间**："新增工厂不分配预置业务 Key"（同文件第 41 行）。于是应用可以随意使用 key 1，而框架日后新增的任何
  Key（deadline、trace、租户）
  都会与已上线应用冲突。这种不对称是一个前向兼容隐患：现在补一张表就能解决，以后只能靠破坏 Wire 兼容解决。

### 2.5 没有可观测性出口

`RuntimeStats`、`DataStats`、`GameRouter.Stats` 都是由应用主动拉取的进程内 record 快照。没有指标导出（整个
工作树里没有 Micrometer、OpenTelemetry 或 Prometheus），没有追踪，没有健康/就绪端点，也无法内省运行态
（每 Route 队列深度、待持久化集合、Peer/Slot 状态）。对于一个在自家文档里反复写着"生产容量未验证"的框架，
恰恰缺少能验证它的仪表，这是自我强化的。

### 2.6 不可信面未加固，可信面已加固

只与其他服务器通信的内部 RPC 监听具备准入控制与认证：`maxConnections`（4096）、`maxInboundPeers`（1024）、
`maxPendingCalls`（65536）、`maxFrameSize`、写水位线、带时间窗与 nonce 防重放的 HMAC-SHA256 握手、心跳与重连
（[RpcNodeBuilder.java](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java)、
[HandshakeGuard.java](../../game-rpc/src/main/java/cn/managame/rpc/node/HandshakeGuard.java)）。

面向真实玩家的那个监听一项都没有。
[NetworkServerBuilder](../../game-network/src/main/java/cn/managame/network/netty/NetworkServerBuilder.java)
提供 `bindAddress`、`handler`、`pipeline`、`webSocket`、EventLoopGroup、`channelFactory`、`option` 与
`childOption`，仅此而已。没有连接数上限，没有单 IP 上限，没有空闲或读超时，没有首包截止时间，没有读取限速，
没有写水位线便捷设置。一切交给 `pipeline(Consumer<ChannelPipeline>)`，即手写 Netty Handler。同一模块里的
`HttpServer` **却**限制了 `maxContentLength`、`maxInitialLineLength`、`maxHeaderSize` 与 `readTimeoutMillis`
——这种不对称在 game-network 内部甚至都不自洽。

同样没有任何会话、登录、认证原语。game-demo 不得不自己写 `GameSession`、`GamePacket` 及其解码器、编码器、
Handler，以及"入队前拒绝缺失会话"的策略——每个应用都要重复一遍，包括其中与安全相关的部分。

一处相关设计限制值得写明：握手密钥由整个集群共享，所以它证明的是"本集群的成员"，而不是"声称 ID 为 7 的那个
节点"。一个被配错或被攻破的成员可以以任意 `serviceId` attach 并接收该服务的广播。要更强的身份声明需要按节点
分发密钥或签名身份；当前模型对可信内网是够用的，应当就这样明确写进文档。

### 2.7 Cron 与定时器是进程级且仅在内存

`@Cron(value, domain, routeKey)`
（[Cron.java](../../game-runtime/src/main/java/cn/managame/runtime/timer/Cron.java)）调度在本地
`CronScheduler` 上。在 N 个节点的集群里同一个注解会触发 N 次：每日重置、排行结算、邮件清扫都会每节点各跑一次。
Runtime 规范与 Spring 集成都没有涉及"只在一个节点执行"，也没有可供构建它的选主或分布式锁。定时器仅在内存，
所以一个已排定的游戏效果（四小时后完工的建筑）无法跨重启存活。两者都可能是有意为之；但都没有在读者会查看的
位置写成限制。

## 3. 数据路径风险

### 3.1 `PendingBuffer` 是最后一个无界队列

[PendingBuffer](../../game-data/src/main/java/cn/managame/data/PendingBuffer.java) 按实体类型各用一个
`ConcurrentHashMap` 持有脏集合，没有容量、没有准入检查、没有背压，而 `EntityCache` 按契约"绝不淘汰仍有未保存
变更的条目"。于是一次数据库故障会无上限地撑大脏集合**并且**把每个脏实体钉在缓存里：两个无界结构互相喂养，
终点是堆耗尽。唯一信号是 `DataStats.pendingChanges`；业务侧从不被减速或拒绝。

不一致本身和风险同等重要。2026-10-14 审阅为日志队列加了界（`logQueueCapacity`，默认 100,000，满时 insert
返回 false），也为 RPC 待完成调用加了界（`maxPendingCalls`，默认 65,536，立即 UNAVAILABLE）。而承载玩家进度的
实体脏集合被留作无界。Data 规范确实声明不承诺积压保护，所以这是已知缺口而非违反契约，但它现在是三个队列中
唯一没有上限的那个。

### 3.2 `EntityCache` 没有容量上限

淘汰只按空闲时间（`cacheExpire`，默认 30 分钟），规范也记录了这个决定："不新增自动脏检测、不可变保存快照或
缓存容量淘汰"（[OGBS-Data-1.0.zh-CN.md:196](../ogbs/OGBS-Data-1.0.zh-CN.md)）。后果是工作集由访问模式决定，而不
由内存预算决定：一次扫过大量 id 的宽遍历（维护任务、排行构建）会撑大堆，并在整个空闲窗口里保持不放。一个
可配置的最大条目上限，配合缓存已有的"不淘汰脏条目"规则，可以在保持现有语义的同时，把内存从结果变成设置。

### 3.3 没有单实体持久化保证

唯一的持久化屏障是全局的：`GameData.flush()`
（[GameData.java:17](../../game-data/src/main/java/cn/managame/data/GameData.java#L17)）对全部内容跑一轮，
而 D-FLUSH-01 要求调用方先让写入方静默才能得到稳定屏障。不存在"立刻持久化这个实体并确认"的调用。
`flushInterval` 默认 1 秒，意味着崩溃时每个玩家最多丢一秒进度——对一般玩法通常可以接受，对一次购买、一次奖励
领取或一次道具交易则不行。今天需要这个能力的应用只能停下所有写入方再 flush 全世界，而这不是一个请求处理器
能做的事。

### 3.4 启动期 DDL 无条件执行且存在竞态

[MysqlSchema.initialize](../../game-data/src/main/java/cn/managame/data/mysql/MysqlSchema.java) 由
`MysqlEntityMapper` 对每个注册仓储调用，没有 Builder 开关可以关闭，没有迁移版本，也没有只校验模式。它先查
`information_schema`，然后发出 `CREATE TABLE`（不带 `IF NOT EXISTS`）、`ALTER TABLE … ADD COLUMN` 与
`CREATE INDEX`。

这是针对共享状态的 check-then-act。两个同时启动的节点都观察到"表不存在"，都发出 `CREATE TABLE`；失败的一方
在**启动期**收到不可重试的 `SQLException`，于是启动失败。`ADD COLUMN` 与 `CREATE INDEX` 同理。多副本启动与
滚动发布恰恰就是会撞上它的时刻。

类型校验也比看上去更弱。`compatible` 从第一个括号起整段截掉
（[MysqlSchema.java:55](../../game-data/src/main/java/cn/managame/data/mysql/MysqlSchema.java#L55)），所以声明为
`varchar(255)` 的字段对已有 `varchar(32)` 列会被接受：启动通过，失败推迟为运行期数据截断。精度与 unsigned
差异因同样原因不可见（`information_schema.columns.data_type` 两者都不携带）。

### 3.5 存储适配层对其目标数据库基本未验证

358 个测试通过，而**没有一个接触真实 MySQL 或 MongoDB**。`DatabaseIntegrationTest` 由
`@EnabledIfEnvironmentVariable(named="OGBS_DATA_MYSQL_URL")` / `OGBS_DATA_MONGO_URI` 门控，CI 工作流没有定义
任何服务容器，而且这些测试是被整体过滤掉而不是报告为跳过——上一次审阅记录"2 个跳过"，本次运行报告 0，于是这个
覆盖缺口在构建输出里已经不可见了。

game-data 是 2,299 行 SQL 生成、DDL、分区与类型映射，由 62 个针对 H2 的测试与单元级映射断言覆盖。因此未被
执行到的包括：真实 DDL 与迁移行为、分区日志表、JSON 列语义、`rewriteBatchedStatements` 批处理——以及
`RetryPolicy`，它靠匹配 **MySQL 厂商错误码 1205/1213** 与 **MongoDB 异常类名子串**
（`name.contains("Timeout")`、`"NotPrimary"`、`"NodeIsRecovering"` 等，
[RetryPolicy.java](../../game-data/src/main/java/cn/managame/data/error/RetryPolicy.java)）来决定一次失败的
保存是保留还是丢弃。H2 一个都产生不了。于是"存储故障不丢数据、一条坏行不拖累其他行"这整条保证，建立在从未被
任何测试走过的分支上。这是证据最弱的最高风险模块，而在 CI 里加一个 MySQL 服务容器，一个提交就能改变现状。

## 4. 门禁与构建

### 4.1 `main` 上的 CI 是红的

`mvn -B -Pquality verify` 失败：

```text
game-runtime/src/main/java/cn/managame/runtime/internal/CronSchedule.java:3:8:
  UnusedImports: 无用的导入 - cn.managame.runtime.timer.Cron
```

该导入在编译器拆分后变成残留；`CronSchedule` 只在散文注释里提到 Quartz，并不引用任何 `Cron` 类型。由于
Checkstyle 在 game-runtime 快速失败，**game-data、game-spring、game-demo、game-benchmark 根本从未被做过风格
检查**。[.github/workflows/ci.yml](../../.github/workflows/ci.yml) 的 `quality` 任务跑的正是这条命令，所以
流水线自门禁加入起就一直在失败。2026-10-14 审阅记录"尚未执行：风格门禁"；三个提交之后它仍未通过。改动只有
一行，但一个从未变绿的门禁不是门禁。

已于 2026-10-17 修复，而删掉这行导入恰好印证了上面那句：Checkstyle 随后抵达了它从未扫过的那四个模块，
game-benchmark 立刻失败——失败的不是它那三个手写类，而是 `target/generated-sources/annotations` 下 JMH 生成的
基准测试壳，它声明了自己并不使用的导入。该插件会遍历全部编译源根，而注解处理器把那个目录加进了源根，所以这个
门禁从来就不像它自己的注释所称的那样"只检查主源码"。把 `sourceDirectories` 钉到
`${project.build.sourceDirectory}` 即恢复了文档所述的意图。这与 §4.2 是同一个"扫到生成代码"的缺陷，所以值得
修门禁本身，而不是把那个模块静默跳过。`mvn clean verify -Pquality` 现已端到端通过：十个 reactor 模块全绿，
每个被检查的模块 0 条违规，358 个测试，0 失败，0 跳过。

### 4.2 `errorprone` profile 无法构建、不在 CI、且在分析生成代码

`mvn -Perrorprone clean compile` 的首次干净运行：121 条去重发现，75 条为 ERROR 级，game-data 无法编译。其中
有实质内容的逐条核对，**没有一条是功能缺陷**：

| 发现 | 判定 |
| --- | --- |
| `FutureReturnValueIgnored` ×21（`NettyConnection.write`、各监听） | 有意为之——`channel.voidPromise()` 把出站失败导向 `exceptionCaught`，代码注释已说明 |
| `NonAtomicVolatileUpdate` ×2（`PeerTable.inbound`） | 安全——写入持有 lifecycle 锁，`volatile` 是为了无锁读 |
| `ReturnValueIgnored` ×4（`Duration.toNanos()`） | `GameDataBuilder` 校验里有意的溢出探测 |
| `UnusedVariable`：`WriteBehindManager.deferred`、`MysqlLogWriter.zone` | 仅是死字段。`flush()` 返回剩余计数且 `flushNow()` 使用它，D-FLUSH-01 仍成立；`zone` 已被构造器经 `withZone` 消费 |
| `Finally` ×2（`JdbcMysqlAccess`） | 窄但真实：在**提交成功后**恢复 `autoCommit` 失败时，该 `SQLException` 会被抛出，于是一个已提交的事务被报告为失败。没有测试覆盖 |
| `MissingOverride` ×80 | 注解卫生 |

37 条 `ThreadPriorityCheck` 中有 36 条位于**生成的 JMH 源码**（`jmhTest.java`），所以这个 profile 需要先排除
生成源码，其输出才有意义。而且 POM 文档给出的命令（`mvn -Perrorprone compile`）在已构建的工作树上是空操作
——实测每模块 0.04–0.08 秒，即不带 `clean` 时它静默地什么都没分析。三个可修的问题：它不通过、它不在 CI、它
文档化的调用方式不工作。

### 4.3 没有发布与被依赖路径

每个模块都是 `1.0.0-SNAPSHOT`。整个工作树里没有 `distributionManagement`，没有 source 或 javadoc jar，没有
BOM 模块，也没有任何 `module-info.java`。AGENTS.md 把"独立发布"列为创建 Maven 模块的理由，但今天这棵树里没有
任何东西能被发布或按版本被依赖。

第三方版本是逐模块声明而非集中管理——`dependencyManagement` 里只有 Netty（BOM）与 JUnit。漂移已经可见：

| 依赖 | 位置 | 版本 |
| --- | --- | --- |
| jackson-databind | game-runtime、game-data | `2.21.3` 各写一次，互不关联 |
| mysql-connector-j | game-data（test）/ game-demo | `9.4.0` / **`9.7.0`** |
| mongodb-driver-sync、spring-context、HikariCP、H2 | 各模块内联 | 没有任何单一来源 |

### 4.4 基准只覆盖了便宜的部分

game-benchmark 是三个 JMH 类：`MetadataBenchmark`、`RpcWireBenchmark`、`RouteExecutorBenchmark`——纯 CPU 路径。
没有任何端到端度量：RPC 往返延迟、压力下的分发吞吐、写回每秒行数、Router 扇出。"生产容量未验证"出现在六份
文档里，而任何地方都没有出现一个数字，所以也不存在可用于发现回退的基线。

## 5. 规范一致性

### 5.1 RPC 规范在"有限准入"上自相矛盾，且两种语言都如此

`R-SEND-04` 已完整规定 Peer 级待完成调用上限
（[OGBS-RPC-1.0.zh-CN.md:89](../ogbs/OGBS-RPC-1.0.zh-CN.md)，EN:101），Java 规范已绑定它
（`maxPendingCalls`，默认 65536，[第 58 行](../ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)与第 151 行），
`RpcNodeBuilder` 已实现它。然而同一份标准仍在三处声明该能力缺失：

| 位置 | 残留文字 |
| --- | --- |
| `OGBS-RPC-1.0.zh-CN.md:5` | 有限调用接纳仍未实现并暂缓 |
| `OGBS-RPC-1.0.zh-CN.md:10` | 有限准入仍为已声明缺口 |
| `OGBS-RPC-1.0.zh-CN.md:164` | 有限接纳仍暂缓 |
| `OGBS-RPC-1.0.md:5,10,186` | "Finite admission remains unimplemented/deferred" / "a declared gap" / "remains deferred" |

这正是 AGENTS.md 专门点名的情形：过时表述被留在原处，于是这份规范性的跨语言文档现在与它自己的条款、与 Java
层、与代码三者都不一致。从文档头读起的读者会得出"该上限不存在"的结论。两种语言需要同一处修正。

### 5.2 一个概念，三种中文术语

中文标准在上述三处把同一概念分别写成"有限调用接纳""有限准入""有限接纳"。双语规则要求同一契约的两种语言
表达；术语应当定一次并贯穿使用。

### 5.3 审阅记录提到了从未实现的 API

[2026-10-14-framework-review.zh-CN.md](2026-10-14-framework-review.zh-CN.md) 第 10 条记录"新增
getAsync/getGroupAsync"。`git log -S getAsync --all` 只匹配到添加这句话的那个提交：这两个方法既不在
`game-data` 中，也不在任何规范中。该记录是非规范的，但它正是维护者用来了解当前设计的文档，而它现在承诺了一个
并不存在的异步加载 API。`SingleRepository` 提供的是 `get`、`getOrCreate`、`insert`、`update`、`delete`——全部
同步，未命中时内联加载。同步加载本就是契约（"读取缓存未命中时同步加载"，D-MODEL-01），异步访问方法是有意不
纳入 API 的，所以要改的是那份记录，而不是 game-data。

### 5.4 一个值得写进文档的后果：谁可以调用阻塞加载

`SingleRepository.get` 在缓存未命中时同步加载，而没有任何东西约束由哪个线程调用它——game-data 与 game-runtime
中都不存在 `inEventLoop` 检查或线程断言。RPC Handler、Router Handler 与 `ConnectionHandler` 回调全部运行在
Netty EventLoop 线程上，所以从其中任何一处调用仓储的业务代码，都会阻塞一个服务着大量连接的事件循环。在
`StripedRouteExecutor` 上它会卡住整条 stripe；在虚拟线程上它正确但相对固定大小的 JDBC 池无界，于是一个慢数据库
会转化为不断累积的在途 Route，直到准入开始返回 OVERLOADED。这些都不是缺陷——它们是既有设计选择的推论——但
"仓储调用可能阻塞，因此只应从 Route 内调用"正是 Data 规范应当直接写明的边界。

### 5.5 2026-10-07 记录仍把 R-SEND-04 描述为暂缓

修 §5.1 时发现。[2026-10-07-router-rpc-review.zh-CN.md](2026-10-07-router-rpc-review.zh-CN.md) 实测了 10,000
个发往不回复服务的 Call 全部滞留在 pending，并得出"Node 级 Call 准入归 RPC 暂缓的 R-SEND-04"。该结论在当日是
准确的，且该文件是有日期的记录，所以没有改写它。但它也正是维护者判断"调用准入是否存在"时会读的探测记录，而它
现在指向一个已经交付的能力。仓库里已有先例：[2026-10-13](2026-10-13-router-forwarding-review.zh-CN.md) 在开头
加了一条后续说明，指出它哪些章节已被取代。给 2026-10-07 记录加一行带日期的说明即可。

## 6. 建议顺序

按各项所买到的东西分组。§2 中的条目是所有者的设计决策，不是应当被静默修掉的缺陷。

**第一步 —— 让门禁名副其实（量小、机械、解除后续阻塞）：**

1. 删除残留的 `Cron` 导入使 `-Pquality` 通过，然后确认 Checkstyle 从未到达过的那四个模块（§4.1）。
2. 修正英中各三处"有限准入"表述，并统一术语（§5.1、§5.2）。
3. 修正 2026-10-14 记录中的 `getAsync` 说法（§5.3）。
4. 一次性清理 Error Prone：排除生成源码、补齐缺失的 `@Override`、为有意模式加带理由的抑制，然后把该 profile
   接入 CI（§4.2）。
5. 在 CI 中加入 MySQL（与 MongoDB）服务容器并在那里运行 `DatabaseIntegrationTest`——本清单中单位改动降风险
   最高的一项（§3.5）。

**第二步 —— 收掉数据路径风险（工作量有界、契约清晰）：**

6. 给脏集合加界：一个 `pendingChangeCapacity` 与定义好的拒绝行为，与日志队列和 RPC 调用已有的做法一致（§3.1）。
7. 为 `EntityCache` 加最大条目上限，保留"不淘汰脏条目"规则（§3.2）。
8. 让启动期 DDL 可关闭且幂等：`schema(CREATE | VALIDATE | NONE)` 设置、`IF NOT EXISTS`、容忍并发创建错误，
   并在兼容性检查中纳入长度与精度（§3.4）。
9. 为购买与领取类场景提供单实体持久化保存并报告结果（§3.3）。
10. 在 Data 规范中写明阻塞仓储调用的线程边界（§5.4）。

**第三步 —— 定架构（所有者决策，每项都应先有规范再写代码）：**

11. 现在就在 Core 中保留框架 MetadataKey id 区间，趁应用还没占满低位 id（§2.4）。
12. 定下归属模型：提供让某个节点成为实体唯一写者的租约/epoch 原语，或者明确且显著地声明"单写者放置是应用责任，
    并发写者会静默丢更新"（§2.3）。
13. 把截止时间传播与取消作为线上协议问题定下来——它是协议版本变更，越早决定越便宜（§2.4）。
14. 决定发现/成员管理属于框架还是属于一个文档化的外部控制面；若是后者，规定它必须满足的接口（§2.1、§2.2）。
15. 为面向客户端的监听加界：连接数上限、单 IP 上限、空闲与首包超时（§2.6）。
16. 决定集群级 Cron：要么提供协调原语，要么写明进程级限制（§2.7）。
17. 增加指标/健康出口，哪怕是一个最小的拉取端点，使容量声明可被度量（§2.5）。

## 7. 本记录的验证状态

§1、§4.1、§4.2、§3.5 中每一条实测结论都来自在本工作树上执行的命令，可用文中给出的命令复现。代码结论标注了
文件与行号。§2 与 §3 陈述的是能力缺口与风险，而不是观察到的生产故障：没有多节点部署、没有真实数据库、没有
压力测试可用，所以本文任何内容都不应被当作已度量的线上事故。§4.2 的 Error Prone 发现逐条读过并分类；没有一条
被确认为功能缺陷，该表格对此直接写明，而不是把它们计为缺陷。
