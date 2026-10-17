# 生命周期与可观测性评审（2026-10-18）

[English](2026-10-18-lifecycle-and-observability-review.md) | **[简体中文](2026-10-18-lifecycle-and-observability-review.zh-CN.md)**

> 非规范记录。当前契约由各组件规范持有。本页不重复[缺口分析](2026-10-17-framework-gap-analysis.zh-CN.md)与
> [架构与 API 评审](2026-10-17-architecture-api-review.zh-CN.md)：§2 说明那两份在一天的工作之后变成了什么状态，
> §3 是新发现，来自通读关停路径与刚加上的那批读数。§3 的每条结论都出自源码，标注文件与行号。

## 1. 为什么再过一遍

2026-10-17 之后有三件事变了：Router 改成两级网格、各组件补上了可观测性、风格门禁变绿。每一件都关掉了前面清单
上的条目，其中两件又打开了新的。清单在这种情况下最容易"被重读但没被重新推导"而变质，所以这一轮先把旧发现对着
工作树逐条复核，再去新工作落地的地方看。

## 2. 前两份清单的现状

2026-10-17 之后已关闭：

| 条目 | 怎么关的 |
| --- | --- |
| 缺口 §4.1 风格门禁红、CI 失败 | 删掉残留导入并把 Checkstyle 扫描钉到 `src/main/java`；`mvn clean verify -Pquality` 十个模块全过 |
| 缺口 §5.1–5.3 规范自相矛盾 | 英中各三处"有限准入"表述已改正、术语已统一、`getAsync` 说法已更正 |
| 缺口 §2.5 没有可观测性出口 | Core metrics SPI、`NetworkStats` 与 `RpcStats`、Spring 适配、`/metrics` 与 `/readiness` |
| 缺口 §2.2 广播只覆盖一个 Router | 网格把广播扇出到各 Router；覆盖范围变为其 Router 可达的该 service 全部 Node |
| 架构 §2.2 Router 没有后台线程 | 已不成立：有一个守护线程做位置刷新。该记录已加注 |

仍然打开、未变、此处不再论证——每一条今天都对着工作树复核过：

| 条目 | 位置 |
| --- | --- |
| 脏集合无界；数据库故障会把它与缓存一起撑大到堆耗尽 | [缺口 §3.1](2026-10-17-framework-gap-analysis.zh-CN.md) |
| `EntityCache` 没有条目上限，只有空闲淘汰 | 缺口 §3.2 |
| 没有单实体持久化保存；唯一屏障是全局 `flush()` | 缺口 §3.3 |
| 启动期 DDL 无条件执行、check-then-act，且类型检查忽略长度 | 缺口 §3.4 |
| 没有任何测试接触真实 MySQL 或 MongoDB，CI 也没有服务容器 | 缺口 §3.5 |
| Error Prone profile 仍然失败，仍然不在 CI 里 | 缺口 §4.2 |
| 没有发布路径：全部模块 `1.0.0-SNAPSHOT`，无 BOM，版本逐模块重复 | 缺口 §4.3 |
| 面向客户端的监听没有连接上限、单 IP 上限、空闲或首包超时 | 缺口 §2.6 |
| 跨集群没有实体单写者保证 | 缺口 §2.3 |
| 线上协议没有截止时间、取消与追踪上下文 | 缺口 §2.4 |
| Core 保留了错误码区间，但没有保留 MetadataKey id 区间，日后新增框架 Key 会冲突 | 缺口 §2.4 |
| Cron 是进程级；N 个节点会把同一个 `@Cron` 触发 N 次 | 缺口 §2.7 |
| `RpcCallback` 声明了一个 RPC 核心从不调用的方法 | [架构 §4.1](2026-10-17-architecture-api-review.zh-CN.md) |
| 同一角色有 `onFail` / `onFailure` / `onException` 三种拼法 | 架构 §4.2 |
| 九个分发入口靠位置化基本类型区分 | 架构 §4.3 |
| `SingleRepository.delete(K)` 对 `GroupRepository.delete(E)`；`insert` 在一个兄弟里返回 `boolean` | 架构 §4.4 |
| HTTP/JSON 住在业务执行模块里 | 架构 §3.1，按所有者意见暂缓 |

## 3. 新发现

> **§3.1 与 §3.2 已于当天修复。** 关停现在是一份声明过的预算——`game.shutdown.budget-millis`，默认 25 000，位于 Kubernetes 宽限期之下——切分为提前量、推导出的摘流，以及传给 Data 的保留刷盘份额，超额的切分会在 refresh 时失败。提前量正是就绪此前缺的那个阶段：它在准入保持开放时报 503。第三个问题的答案在 §3.2：定时器与 Cron 仍在准入关闭时被取消，因为已触发的定时器提交会以 RUNTIME_CLOSED 被拒并丢弃，让它们跑过摘流只会丢弃而不是执行——但它们在提前量期间确实会触发。§3.3 与 §3.4 仍然打开。以下发现作为"当时发现了什么"的记录保留。

### 3.1 关停预算比任何常见宽限期都大，而真正落盘的那一段恰好是被杀掉的那一段

关停时有两个超时顺序执行，且彼此互不知情：

| 阶段 | 默认值 | 来源 |
| --- | --- | --- |
| Runtime 摘流 | **60 秒** | `game.runtime.drain-timeout-millis`，默认 `60_000`（[RuntimeConfiguration.java:37](../../game-spring/src/main/java/cn/managame/spring/runtime/RuntimeConfiguration.java#L37)） |
| Data 最终刷盘 | **30 秒** | `shutdownTimeout`，默认 `Duration.ofSeconds(30)`（[GameDataBuilder.java:22](../../game-data/src/main/java/cn/managame/data/GameDataBuilder.java#L22)） |

顺序由 Spring 固定：`RuntimeDrainListener` 是 `HIGHEST_PRECEDENCE` 的 `ContextClosedEvent` 监听器，因此摘流
会在 Bean 销毁之前跑完，而 `GameData.close()`——D-CLOSE-01 做最终刷盘的地方——在它之后。这个顺序本身是对的：
在途 Route 工作会先完成它的 `repository.update(...)`，Data 才停止接受变更。

预算不对。最坏情况是 **60 + 30 = 90 秒**的顺序关停。Kubernetes 的 `terminationGracePeriodSeconds` 默认 30 秒，
`docker stop` 默认 10 秒，之后进程被 SIGKILL。按框架默认值，这一刀因此落在**摘流过程中**，Data 刷盘根本还没
开始：每个玩家最后不超过一个 `flushInterval` 的进度，加上已经积压的全部内容，都丢了，而且没有任何东西报告它
——因为负责报告的那个阶段从未运行。

顺序让情况更糟而不是更好。长的那一段只是在等，短的那一段才是真正往数据库写的。只缩短宽限期的部署会把写饿死；
只缩短摘流的部署仍然要先发现"这两个数字是要相加的"。规范里没有把它们关联起来，没有哪个默认值由另一个推导，
也没有任何日志说出总和可能是多少。

能修好它的是一个决定，不是一个补丁：要么摘流默认值降到典型宽限期之下，要么两者共享一份声明过的预算且为刷盘
保留一块保证份额，要么框架把总和写明并要求运维把宽限期设得高于它。三条都便宜；工作量在"选一条"。

### 3.2 就绪没有提前量，于是它做不了它被加进来要做的事

`/readiness` 的依据是 `GameRuntime.stats().accepting()`。该标志由 `runtime.shutdown()` 清除
（[DefaultGameRuntime.java:338](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java#L338)），
而那正是摘流监听器的第一条语句。因此准入关闭与就绪翻成 503 **发生在同一次调用里**。SIGTERM 之后的时序是：

1. `runtime.shutdown()`——准入关闭，就绪此刻变 503
2. `awaitTermination(60 秒)`——收尾已接纳的工作
3. Bean 销毁——HTTP 监听关闭，就绪彻底不再作答
4. `GameData.close()`——最终刷盘

负载均衡器要等到它下一次探测才知道第 1 步，通常是几秒之后。这期间它发来的一切都会被拒绝，因为实例在"开始说
自己不行"的同一瞬间就停止接受了。就绪本该创造的那个窗口——*不就绪，但仍然服务已经到达的请求*——并不存在。

这是昨天刚加进来的东西的缺陷，不是旧问题。修法需要一个框架目前没有的阶段：在 `shutdown()` 之前进入一段可配置
的间隔，期间就绪报 503 而准入保持开放。没有它，这个端点对"这个实例是不是病了"有用，对"干净地把它摘出轮转"
无用——而后者恰恰是多数部署加它的原因。

同一行代码还有第二个后果：`shutdown()` 会立即调用 `crons.close()` 与 `timers.close()`，因此在那 60 秒摘流期间
到期的定时器或 Cron 永远不会触发。对一个游戏来说这是真实行为——建筑完工、奖励解锁——在每次部署时被静默跳过，
再叠上缺口 §2.7 的"定时器仅在内存"。

### 3.3 Runtime 的读数既没有维度也没有分母

`RuntimeStats` 完全由全局 LongAdder 求和构成
（[DefaultGameRuntime.java:351](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java#L351)）：
`queued.sum()`、`running.sum()`、`rejected.sum()`。没有按 Domain 的细分，也没有按 Route 的细分，于是
`route.rejected` 上升只说明实例在丢工作，却不说明是哪个 Domain，更永远不说明是哪个玩家或哪个房间。对一个
核心保证就是"按 `(domain, key)` 串行执行"的框架来说，"哪个 key 热"是运维最先问的问题，而它恰恰是唯一回答
不了的读数。

`VirtualThreadRouteExecutor` 把 `capacity` 与 `routeCapacity` 保持为私有字段且没有访问器，`activeMailboxes`
同样如此，因此配置上限也不上报。于是 `route.queued` 没有分母：运维能看到一个数字在涨，却看不到它距离
`tryExecute` 开始返回 OVERLOADED 还有多远。两者合起来意味着这个执行器可以被读，但无法被判断。

现在关掉它们都很便宜，因为管道已经在了：读数只需要一组按 Domain 的计数器——Domain 是前置注册的且数量很少，
基数有界，不会危及 C-OBS-05——以及两个表示配置容量的 gauge。按 Route 的细分按设计仍然做不到，因为 routeKey
的基数无界；诚实的替代是上报当前最大的那个 mailbox 的深度、或者它的 key，而执行器可以在不维护一张计数器表的
前提下暴露它。

### 3.4 Router 网格把故障转移挪到了每个应用身上，而没有任何东西填上这个缺口

2026-10-17 之前，服务连接每个 Router，发送方逐条消息选一个，于是丢掉一个 Router 是看不见的：下一次发送会选
另一个。网格有意替换了这一点，而这个决定是所有者做的——`RT-SCOPE-01` 保留了"声明 Router 不得创建或删除
RPC Peer"这条规则，因此 `ServiceRouting` 从不碰 Peer（它唯一提到 `addPeer` 的地方是 Javadoc，在告诉应用去
调用它）。

那次改动的记录把这一点列为"接受的代价"，而它值得作为一个打开的条目重述，因为没有任何东西填上它：Router 挂掉
之后，附着其上的服务要等到**应用**注意到、向另一个已声明 Router 开 Peer、并等那个 Router 的位置传播开，才重新
可达。于是每个应用都要实现同一套探测与切换，并以同样的方式犯同样的细微错误——没有退避、没有滞回、在两个
Router 之间来回抖动，或者重连到一个活着但已与网格其余部分分区的 Router。`ServiceRouting` 本来就跑着一个线程
在评估"哪个已声明 Router 处于连接状态"，也本来就知道"一个都没有"的时刻；缺的是策略，不是机械。

这是整个框架里"对框架很便宜、对每个应用很贵"这种形状最清楚的一例，而这种形状值得优先关掉。

## 4. 建议顺序

**要决定，因为这里的默认值是错的而不是缺的：**

1. 把两个关停超时关联起来：一份声明过的总预算、为刷盘保留的份额，或者把摘流默认值降到典型宽限期之下，并在
   Spring 规范里写出总和（§3.1）。
2. 加上摘流前的就绪阶段，让就绪能做它该做的事（§3.2），并决定摘流期间定时器与 Cron 是否应继续触发。

**便宜，而且管道已经在了：**

3. 按 Domain 的 `rejected`、`queued` 与 `completed`，外加表示执行器配置容量的 gauge（§3.3）。
4. 在 `ServiceRouting` 里给出 Router 选择策略——或者明确声明它归应用，并把它已经具备的探测能力暴露出来，让
   策略有东西可依（§3.4）。

**与前两份清单一致、顺序不变：**缺口 §3.1–3.4 的数据路径加界、CI 里的数据库（缺口 §3.5）、架构 §4.1–4.4 的
API 决定，以及缺口 §2.3–2.4 的架构问题。

## 5. 验证状态

§2 的"已关闭"逐条对着工作树复核过，不是凭记忆：风格门禁实际跑过，规范段落重读过，Router 与 metrics 代码读过。
§2 的"仍然打开"逐条以检查确认——脏集合、缓存、DDL 路径、监听 builder、Core 的 Key 区间、CI 工作流与执行器
访问器。§3 的四条发现标注了文件与行号，出自通读关停路径与新加的读数；没有一条经过运行期测量。特别是没有对任何
一次部署计时：§3.1 的 90 秒是两个有文档默认值的和，而"SIGKILL 落在摘流过程中"是把它与各编排器有文档的宽限期
相比得出的，不是观测到的一次关停。
