# OGBS Router 规范 1.0

[English](OGBS-Router-1.0.md) | **[简体中文](OGBS-Router-1.0.zh-CN.md)**

文档类型：语言无关 Specification。组件：game-router。配套：[Java 25 开发规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。依赖：[RPC](OGBS-RPC-1.0.zh-CN.md)、[Core](OGBS-Core-1.0.zh-CN.md)。字节定义：[RPC Wire Profile 中的 Router Profile](../rpc-wire.zh-CN.md#router-profile-v4)。状态：Java 参考实现已实现，具有本地 TCP 契约测试；生产容量与跨语言互通尚未验证。

Router 是无状态中继。每个服务连接所有 Router；服务通过任一已连接的 Router 发送发往精确 Node 的消息，该 Router 把消息交给连在它上面的目标 Node。Router 不保存路由表、注册或副本，也从不与其他 Router 通信：它只持有每个已连接 Node 声明的身份，连接断开即消失。把玩家等业务键解析为 Node 由发送方负责。未解决的边界见 [Java 已知限制](OGBS-Router-Java-25-Specification-1.0.zh-CN.md#7-known-limits)。

## 1. 职责与组合

**RT-SCOPE-01** Router MUST 基于 RPC 完成消息发送、接收、直连 Peer、Slot、调用 ID、PendingCall、超时与回调。每个进程只有一个 RPC Node；安装路由能力 MUST NOT 创建第二个 Node 或独立传输。应用仍可通过同一个 Node 进行普通直连 RPC。Router 只负责中继；应用负责 RPC 启动、关闭和 Peer 配置，连接 Router 与连接其他 Node 使用同一个 RPC Peer 操作。声明服务使用哪些 Router MUST NOT 创建或移除 RPC Peer。

**RT-SCOPE-02** V1 通过无状态 Router 提供 Node 精确寻址和按服务广播。Router 不解析业务键，不保存绑定或注册，不复制任何数据，不实现发现或订阅，不存储或重试消息，不持有玩家状态，也不提供 Runtime 串行执行。Router 之间互不连接，因此每条路由消息恰好经过一个 Router。RPC 成功或超时均不证明业务恰好执行一次。

| 归属 | 职责 |
| --- | --- |
| RPC | 连接与 Slot、消息传输、调用 ID、PendingCall、超时与完成回调 |
| Router | 把消息中继给连在它上面的 Node，只使用该 Node 在其活动连接上声明的信息 |
| 发送服务 | 把业务键（玩家、房间）解析为 Node ID；每条消息选择一个已连接的 Router |
| 外部服务发现 | 存在哪些服务实例和 Router，以及它们的地址 |
| 应用 | 组件装配、启动/关闭，让每个服务连接所有 Router |

原因：Router 自身没有状态，因此重启、增加或移除 Router 都不需要任何恢复或同步协议，不存在副本滞后或绑定冲突，玩家位于何处的权威仍留在做出这一决定的业务代码中。新增层次或抽象需要现有归属方无法清楚表达的具体职责，仅为将来可能的扩展不够。

## 2. Node 与附着

**RT-NODE-01** Node 身份为 `(nodeId, nodeEpoch, serviceId)`。nodeId 为非零 uint32，nodeEpoch 为非零 uint64，serviceId 为正 int32。Router ID 与服务 Node ID 共享同一命名空间。每个 Node 只服务一个服务。nodeEpoch 区分进程代际：重启的进程使用新值，只要求不同，不要求更大。

**RT-NODE-02** 附着关系绑定在连接上。服务一看到连接就在每个已连接 Router 上声明自己的身份，之后周期性地再次声明；它发送的每条路由消息也同时声明身份。Router 只在该 Node 保持连接期间保存每个 Node 的最新声明；新代际替换旧代际。连接关闭或 Node 以相同 epoch 撤回后，声明即不再有效；只能在发送失败时才得知连接关闭的实现 MAY 届时再删除它。服务收到自己不使用的 Router 发来的投递时，会再次从该 Router 撤回，因此被先前声明超越的撤回仍会生效。声明不是注册：不确认、不复制、不持久化，也不表示服务是否在线。重启的 Router 从各 Node 的下一次声明中重新得知所有 Node。例如：Router 重启后每个服务都会看到新连接并立即声明；若服务的连接在它察觉前就已被替换，它会在一个声明周期内再次声明，在此之前经该 Router 发给它的消息以 PEER_NOT_FOUND 失败。

**RT-STATE-01** Router MUST NOT 保存活动连接声明之外的任何状态：没有绑定或路由表、缓存、注册、PendingCall、序号或副本。Router MUST NOT 与其他 Router 交换路由流量。因此 Router 可以互相替代：服务连接的任一 Router 都能承载它的消息。

## 3. 寻址与 Router 选择

**RT-ADDR-01** 路由消息的目标是精确 nodeId，可选附带由 Router 校验的精确 nodeEpoch。动态寻址由发送方在发送前解析；Router 从不把键映射到 Node。玩家示例：拥有玩家 B 的服务在业务状态中记录 B 位于 Node 12；发送方查到后发往 Node 12，并把 B 的 ID 放入业务身份或 payload。B 迁移后，消息去向由发送方的查询决定；Router 对已迁移玩家不作任何保证。

**RT-ADDR-02** 每个服务连接它发送或接收所经过的所有 Router。发送方为每条消息选择一个可用 Router。已连接的 Router SHOULD 在连接满一个声明周期后才算可用，以便 Router 重启后其他 Node 在流量到达前已在其上声明自己；没有可用 Router 时使用任一已连接 Router。在可用 Router 集合不变期间，该选择 MUST 是目标与 routeKey 的确定性函数，因此同一 routeKey 发往同一目标的消息保持顺序；集合变化前后的顺序不作保证。

**RT-ADDR-03** Router 只在没有投递时才以 PEER_NOT_FOUND 或 UNAVAILABLE 回答 Call，因此发送方 SHOULD 把这样的 Call 原样经另一个尚未为它尝试过的已连接 Router 重发，只有没有剩余 Router 时才报告该错误。这样，连接不完整（目标只连接了部分 Router）对调用不可见。Notify 没有这样的回答，不会重发；本地失败（例如连接丢失）的 Call 可能已经投递，也不重发。重发的 Call 可能超过同一键之后的消息。

## 4. 转发与调用完成

**RT-DATA-01** Router 只接受来源 Node 等于消息来路直连 RPC Peer 的路由消息，并且只投递给以所请求 epoch 附着的目标 Node（未请求 epoch 时不限代际），地址为该精确代际。Metadata、body、业务身份、command、routeKey 与调用 ID 在转发中保持不变；内层 RPC 帧除调用 ID 外逐字节传递。服务只接受自己声明过的 Router 发来的路由消息，且目标必须是自己的 ID 和 epoch。

**RT-DATA-02** 调用方 RPC Node 拥有路由调用的 pending 状态和超时；Router 不创建调用，也不保存关联。Router 把调用方的外层调用 ID 写入转发的请求；目标向投递该请求的 Router 回复，地址为调用方的精确代际；该 Router 用原生 RPC 响应完成调用方的调用。发给已不再附着的代际的响应被丢弃。Router 或调用方到它的连接丢失时，RPC 让调用方的 pending 调用失败，迟到的响应已无可完成的调用。

**RT-DATA-03** 发往未在所选 Router 附着的 Node 的 Call 立即以 RPC PEER_NOT_FOUND 失败；向已附着 Node 发送被拒绝时以 UNAVAILABLE 失败；目标路由 Handler 抛出异常时返回 HANDLER_ERROR。Notify 没有等待方，在这些情况下直接丢弃。Router 从不缓存或重试业务消息：写缓冲已满的下一跳会拒绝消息。ACCEPTED 只表示首跳准入；Router 不提供端到端收据。Router SHOULD 统计已转发的消息及每类丢弃（目标未附着、发送被拒、响应找不到调用方），使静默丢失可被观察。远端错误（包括 Router 自身返回的错误）进入响应 Handler，本地 RPC 失败进入失败 Handler。

## 5. 广播

**RT-BCAST-01** 广播仅支持 Notify。发送方把一条消息发给所选的一个 Router，该 Router 向附着在它上面的该服务每个 Node（发送方服务该服务时也包括发送方）各投递一份，地址为该 Node 的精确代际。由于每个服务都连接所有 Router，一个 Router 即可到达全部 Node；未连接该 Router 的 Node 收不到这次广播。没有订阅表、聚合结果、重放或投递保证。

## 6. 生命周期与所有权

**RT-LIFE-01** 增加 Router 不需要任何状态迁移：服务连接后自行声明。移除 Router 由应用决定：服务不再选择它并从中撤回；经它在途的调用在其连接关闭时失败。Router 崩溃时，经它在途的调用以 RPC 连接丢失错误失败，之后的发送选择其余 Router。重启的服务使用新 epoch，因此发给旧代际的流量被拒绝或丢弃。

**RT-LIFE-02** 应用拥有装配和关闭顺序：先关闭路由，再关闭 RPC。关闭服务角色会停止声明，并从已连接 Router 撤回本 Node；关闭 Router 会停止中继并忘记所有声明。路由不创建端点或业务 Executor；服务角色的周期声明是唯一的后台工作。已接受的工作没有取消操作，超时使执行结果不确定。完成使用回调，不提供返回 future 的 API。

## 7. 已确认取舍与验证

| 决策 | 原因 | 重新考虑条件 |
| --- | --- | --- |
| 无状态 Router，只保存每条连接的声明 | 重启与扩缩容不需要恢复协议；没有副本滞后或绑定冲突 | 部署无法让每个服务连接所有 Router |
| 发送方解析业务键 | 放置归业务所有；Router 不可能沿用过期绑定 | 大量发送方需要一个共享的键目录，且其保证超出业务存储能力 |
| 每个服务连接所有 Router，一跳 | 没有 Router 间流量、环路或放大；任一 Router 都能承载任意消息 | 服务数 × Router 数的连接过多 |
| 响应经投递请求的 Router 返回 | RPC pending 调用属于单个 Peer，因此 Router 无需保存调用状态 | RPC 支持跨 Peer 关联响应 |
| 周期声明代替有确认的注册 | Router 重启后无需重试、校验或恢复 | 必须保证 Router 静默重启后立即可投递 |
| 发送方预热并重发未投递的调用 | 无需 Router 状态即可覆盖 Router 重启和连接不完整；Router 的 PEER_NOT_FOUND/UNAVAILABLE 证明未投递 | Notify 也需要同样保证，或重发的调用必须严格保持同键顺序 |
| 现有 Node 上的普通 Handler | 一个身份、连接系统及调用完成拥有方；装配在 RPC 外完成 | 缺少具体可复用的 RPC 能力 |

Java 机制、默认值及包布局见 [Java 规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java) 覆盖真实 TCP 的调用/通知/响应字段、大 body、目标缺失、广播、同键有序及在两个 Router 间分散、Router 丢失与重启、重连 Router 的预热、连接不完整时重发调用、向不再使用的 Router 再次撤回、代际校验、Handler 异常、非法响应、伪造来源、未声明 Router 及生命周期；[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java) 覆盖字节布局。[可运行示例](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 为每个模拟进程装配一个 RPC Node。本地测试不是生产规模、集群安全或跨语言认证。服务发现、持久化、远端取消与 Runtime 适配不属于 V1。
