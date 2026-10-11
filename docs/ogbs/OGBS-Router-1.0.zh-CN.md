# OGBS Router 规范 1.0

[English](OGBS-Router-1.0.md) | **[简体中文](OGBS-Router-1.0.zh-CN.md)**

文档类型：语言无关 Specification。组件：game-router。配套：[Java 25 开发规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。依赖：[RPC](OGBS-RPC-1.0.zh-CN.md)、[Core](OGBS-Core-1.0.zh-CN.md)。字节定义：[RPC Wire Profile 中的 Router Profile](../rpc-wire.zh-CN.md#router-profile-v2)。状态：Java 参考实现已实现，具有本地 TCP 契约测试；生产容量与跨语言互通尚未验证。

装配、回调锁循环、多 Slot 重启、注册 ACK 恢复、发现移除、晚到 Handler、入站转发就绪及校验失败重试缺陷已有代码调整和永久本地回归。RPC 有限 pending 准入、转发临界区成本及生产边界仍未解决，详见 [Java 审查修复与剩余边界](OGBS-Router-Java-25-Specification-1.0.zh-CN.md#7-known-limits)。

## 1. 职责与组合

**RT-SCOPE-01** Router MUST 基于 RPC 完成消息发送、接收、直连 Peer、Slot、调用 ID、PendingCall、超时与回调。每个进程只有一个 RPC Node；安装路由能力 MUST NOT 创建第二个 Node 或独立传输。应用仍可通过同一个 Node 与其他服务进行普通直连 RPC。Router 负责下一跳和控制状态；应用负责 RPC 启动、关闭和 Peer 配置。连接 Router 与连接其他服务使用同一个 RPC Peer 操作。Router 成员及服务附着声明路由角色，MUST NOT 创建或移除 RPC Peer，也不能持有地址或 Slot 配置。

**RT-SCOPE-02** V1 提供 nodeId 精确寻址、`(serviceId, bindingKey)` 动态寻址以及按服务广播。Router 之间形成全连接拓扑；禁止从一个远端 Router 再转发到另一个远端 Router。Router 不实现发现、订阅、可靠持久消息、业务重试、玩家状态或 Runtime 串行执行。RPC 成功或超时均不证明业务恰好执行一次。

已确认的架构约束是保持组合简单，每项职责只有一个明确的归属：

| 归属 | 职责 |
| --- | --- |
| RPC | 连接与 Slot、消息传输、调用 ID、PendingCall、超时与完成回调 |
| Router | 注册与绑定状态、Router 同步、路由选择与转发 |
| 外部服务发现 | 服务实例的存在性与代际信息；权威的实例移除事件 |
| 应用 | 组件装配、启动与关闭、Peer 配置，以及向 Router 传递发现变化 |

协议注册表示 Router 接受了路由状态，不能替代服务发现对实例存在性的判断。连接可用也不能证明远端 Router 重启后仍保有注册。恢复需要协调 Router 协议状态，同时保持连接和调用完成归 RPC 所有。业务放置与重试仍由应用负责。

职责适合现有组件时，扩展该组件的行为。新增层或抽象需要具体职责或集成边界，并且现有归属无法清晰表达；仅有未来可能扩展的设想不足以支持新增抽象。这些边界用于指导修复已知实现缺陷，不要求另一套传输、连接 API 或调用完成机制。

## 2. Node 与绑定模型

**RT-NODE-01** Node 注册信息为 `(nodeId, nodeEpoch, serviceId)`。nodeId 是非零 uint32，nodeEpoch 是非零 uint64，serviceId 是正 int32。每个 Node 只承载一种服务，只附着到一个 Router，该附着关系可含多个 RPC Slot。Router 与服务 Node 使用同一 ID 命名空间。部署负责 ID 和新 epoch；epoch 区分附着代际，不是时钟或租约。

**RT-NODE-02** 传输可用性 MUST NOT 决定服务上线/下线。多个 Slot 是同一实例的连接，不是多个注册。中间 Slot 丢失不改变路由状态。最后一个 Slot 丢失暂时阻止传输/协议工作，但 MUST 保留 Router 的权威 Node 注册和绑定。外部服务发现对精确代际的下线通知、显式注销或权威快照移除才清理它们。重连校验同一注册并恢复已接受的期望键，不改变 epoch。切换 Router 使用新 epoch；过期下线事件 MUST NOT 删除较新代际。 精确代际的发现移除 MUST 限制该代际随后重新注册，包括移除先于注册到达的情况。移除依据具有实现定义的有限容量与生命周期，MUST NOT 为接纳旧注册而静默淘汰。路由状态不持久化，Router 重启后发现需要重新提供当前状态。

**RT-BIND-01** bindingKey 是 uint64，包括零。Map Key 存 serviceId、bindingKey；Value 只存 nodeId、nodeEpoch。Router MUST 只允许 Node 绑定其注册服务。同 owner 重复 bind 幂等；不同可见 owner MUST 返回 BINDING_CONFLICT，不能覆盖。不存在的 unbind 幂等；unbind MUST 按精确 owner/epoch 条件删除，MUST NOT 删除其他 owner 的条目。

**RT-BIND-02** bind 成功表示本地 Router 已接受绑定，并尝试将变更排入其他 Router 的控制流。它 MUST NOT 等待集群应用，不代表持久化，也不保证其他 Router 立即可见。Peer 丢失或控制流拒绝不回滚已接受的本地绑定。传播窗口中的路由缺失仍按真实缺失处理。

**RT-BIND-03** 业务协调负责独占放置。隔离 Router 异常同时 bind 时，各副本 MUST 保留独立权威分桶，并使用同一确定性规则选择可见结果：在有效 owner 中选择无符号 nodeId 最小者。Node 注册重复时，选择无符号 nodeEpoch 最大者，再选择所属 Router 无符号 ID 最小者；只有与选中 Node 代际、服务匹配的绑定才有效。冲突 MUST 产生诊断。此兜底不是分布式锁，业务 MUST NOT 依赖它进行放置。胜者移除后，仍然绑定的败者可能重新可见。

示例：Node 1 在 A 绑定 `(MATCH, 1001)`，隔离的 Node 2 同时在 B 绑定。两个本地操作都可能成功。交换快照后双方都解析为 Node 1；Node 2 unbind 只删除 B 的条目。没有选举或补偿回滚。

epoch 比较是确定性冲突选择，不是时间新旧的证明。新 epoch 要求不同，不要求数值递增。如果保留权威包含 Node 1/epoch 10，而替代实例在另一个隔离 Router 注册 Node 1/epoch 1，两次本地注册与绑定都可能成功；同步后仍选择 epoch 10，直到其权威被显式移除。部署不能假定随机或新 epoch 自动覆盖旧条目。若采用有序分配策略，需要权威来源及明确契约决策；当前不提供这类分配器。有序分配也不能替代精确的发现退役。

绑定权威选择目标，不授予业务执行的独占权。已经投递给旧 owner 的请求在解绑/迁移后仍可继续执行，另一分区在收敛前也可能向竞争 owner 投递。Runtime 的 same-Route 顺序只在一个进程内生效。要求玩家/房间修改独占的应用，需要由执行或存储拥有方检查的放置协调、排空和/或业务所有权限制。这不改变本地 bind 完成语义，不给 Router 增加分布式锁，也不使调用超时取消执行。

## 3. 权威状态与同步

**RT-SYNC-01** 每个 Router 拥有其直连 Node 注册及 LocalBindings。它 MUST 只发布自身权威状态，不能将合并后的 RouteTable 再当作权威发布。远端状态按来源 Router 分桶。可读路由表由本地权威和已同步远端桶重建；实现不必物理维护一张合并 Map。

**RT-SYNC-02** 新建 Router 关系或已失效的同步流 MUST 交换带版本的身份、快照开始及计数、Node 分块、绑定分块和快照结束。Node 条目先于其绑定条目。先捕获本地快照，再将完整快照排在后续变更之前，保持控制流顺序。暂存数据 MUST 在条数、owner 校验通过并应用结束消息之前不可见。双向初始化完成后，关系才进入可转发的 READY 状态。

两端 Router 分别显式声明对方的路由成员身份，该声明与连接发起独立。普通直连 RPC Peer MUST NOT 自动成为 Router；未声明成员的 Router 握手被拒绝。成员声明后若 RPC 连接未就绪，该 Router 不可用，也不自行发起连接。移除成员会丢弃其可见桶，RPC 关系仍由应用管理。应先声明成员再连接，避免握手拒绝及恢复循环。

**RT-SYNC-03** 后续 register/remove/bind/unbind 只更新来源桶。Node removal 清理其精确 Node/epoch 的绑定。Router 传输丢失保留最后提交的桶，但同步不可用；服务发现驱动的成员移除才删除桶。协议恢复发送新快照，合法结束后才替换已提交视图；epoch/revision 校验 MAY 在短暂重连后保留未变化的已提交关系。连续本地 revision 用于发现遗漏增量；版本缺口触发新快照，不重放。没有控制流期间的变化包含在新快照中。V1 不重放序号缺口：非法状态、准入拒绝或完成失败作废路由控制流，触发新的身份/快照交换。恢复 MUST NOT 重置共享 RPC Peer、改变配置或取消无关 pending 调用。重复身份消息重置接收暂存；已同步的接收方也发送本地快照，使双向恢复。交换尚在进行时不得无限回送身份消息。恢复期间最后提交的权威视图仍可见，转发等待双向新快照完成。

**RT-SYNC-04** 快照和增量缓冲 MUST 具有由实现定义的容量边界。Router 控制 ACK MAY 用于推进有限控制流；ACK 只确认一个 Peer 应用了这一条控制消息，MUST NOT 将 RT-BIND-02 改为集群 bind ACK。溢出放弃远端同步，保留本地权威状态。

分区期间，服务发现成员和最后确认的路由仍可见，即使下一跳不可用。已知路由的传输不可用返回 UNAVAILABLE，不臆造服务下线或路由缺失。视图可过期，直到显式发现移除或新的权威快照到达。没有共识、租约或分区强一致性；全连接恢复且来源状态停止变化后收敛。Router 重启通过 Node 注册及 Peer 快照重建状态，没有持久路由数据库。

就绪边界同时约束发送和接收 Router 业务 envelope。例如，B 作废与 A 的同步但保留 A 的已提交桶时，B 仍可查询 A 的绑定；A 已发出的消息不能借该桶绕过 B 的就绪检查。B 丢弃 Notify/广播/响应转发，并尝试沿原来源附着关系为 Call 返回 UNAVAILABLE，仍受返回链路可用性限制。业务消息不缓冲、不重放。普通直连 RPC 和同步/错误控制仍可使用，以便完成恢复。[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 验证拒绝、保留查询及快照恢复后重新转发。

## 4. 转发与调用完成

**RT-DATA-01** 动态请求在来源 Router 只解析一次，得到精确 Node/epoch；物理请求直接使用 nodeId。来源 Router 本地投递，或转发到目标所属 Router；接收 Router 只向自己的本地目标投递。转发 MUST 保留 Metadata、body、业务身份、command、亲和字段及原调用 ID。直连 Node 的来源身份按附着关系校验；已同步、受信任的 Router 为其转发消息的来源身份负责。

玩家寻址示例：拥有玩家 B 的服务绑定 `(PLAYER_SERVICE, playerBId)`；处理玩家 A 已认证请求的服务向该逻辑服务/key 发送，无需知道 B 的实例 ID 或地址。Router 投递到拥有方服务实例，应用 Handler 从业务请求识别 B，再分派到本地玩家/会话。绑定键选择实例，不会自动复制为内层业务身份。路由 sourceNodeId 标识服务器 Node，不标识玩家 A；发送玩家身份与权限归应用。客户端入口、玩家/会话查询、最终客户端投递和离线存储不属于 Router。迁移仍需更新绑定；传播缺口或在途旧路由不能保证投递到已迁移玩家。

**RT-DATA-02** 原始 RPC Node 拥有路由调用的 pending 状态和超时。中间 Router MUST NOT 新建另一笔业务调用、分配新的业务 requestId 或自行关联响应。控制同步可使用独立普通 RPC call。响应指向原 sourceNodeId、sourceEpoch，不能重新查 binding；过期目标附着关系 MUST 拒绝投递。响应被接受不证明来源仍有 PendingCall。

**RT-DATA-03** 动态路由缺失时 Notify 丢弃；Call 返回 ROUTE_NOT_FOUND，不能主动等待超时。物理 Node 不存在及下一跳不可用复用 RPC PEER_NOT_FOUND/UNAVAILABLE。转发使用现有 RPC 发送结果；ACCEPTED 仅表示本地传输准入。如果来源或返回链路已经消失，错误响应也可能无法返回，最终仍由 RPC 超时。Router 不提供端到端发送收据。远端错误进入响应 Handler，本地 RPC 失败进入失败 Handler。

Router 产生的错误返回独立于快照、增量和校验确认，立即尝试向原来源的精确附着关系进行传输准入，不创建中间 pending 调用，也不重试。例如，在返回连接可用时，暂扣同步校验 ACK 不能使已知的 PEER_NOT_FOUND 排在该 ACK 后。传输背压或来源消失仍可能使错误无法在原调用超时前到达。[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 覆盖这一失败隔离，传输表示见 [Wire Profile](../rpc-wire.zh-CN.md#router-profile-v2)。

示例：Room 调用 `(MATCH, playerId)`；Match 收到原始来源身份，随后解绑 playerId。响应仍返回 Room 的精确 Node/epoch。Room 切换或移除附着关系可能丢失响应；超时不会撤销 Match 的执行。[集成测试](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java) 验证这一边界。

## 5. 广播

**RT-BCAST-01** 广播只允许 Notify，投递到当前可见的全部指定服务 Node，包括服务匹配的来源 Node。来源 Router 本地 fan-out，并向每个 READY Router 发送一份消息。接收 Router 只向自身本地匹配 Node fan-out，MUST NOT 再转发到其他 Router。各副本使用自身当前注册视图；没有跨集群原子成员快照。

没有订阅表、广播 call、接收者聚合结果、重放或可靠投递。目标缺失或下一跳拒绝独立丢弃。首跳接受不表示所有接收者均已收到。

## 6. 切换、生命周期与所有权

**RT-LIFE-01** 正常切换先在旧 Router 删除全部绑定和注册，再附着到新 Router、重新绑定期望键。显式 detach 可原子完成旧关系的 unbind-all/removal。传输可用的失败选择可通过协议注销清除；确认精确代际已不存在视为成功清理，MUST NOT 移除其他代际。路由 detach MUST NOT 移除共享 RPC Peer；应用可以保留它供普通直连 RPC 使用，或在路由清理后显式移除。旧附着关系全部 Slot 丢失后允许异常切换；其他存活 Router 在服务发现确认旧 Router 下线后清除其桶。Node 在异常丢失后保留期望绑定，并显式选择新 Router/epoch；不自动选择 Router。清除丢失的附着同样把 RPC 配置留给应用，旧 Peer 随后的恢复 MUST NOT 恢复已清除的附着关系。

**RT-LIFE-02** RPC 拥有自身生命周期/资源屏障；应用拥有组件装配及关闭顺序。显式路由关闭时 MUST 停止接纳状态变更、限制晚到 Handler 并释放自有表/控制缓冲，随后应用关闭 RPC；已分派回调可按 RPC 契约结束。远端实例清理仍需发现通知。路由不创建端点或业务 Executor，自行拥有有限协议恢复调度并在关闭时停止。恢复仅重试幂等注册/同步，不能重试业务执行。选中关系 MUST 在传输仍可用时协调远端 Router 替换和失败注册；连接可用不是注册依据。校验失败停止自动注册重试，并保持可观察。调度间隔/超时由实现定义，不是恢复期限或存在性租约。业务分派及借用 payload retain 归应用。已接受工作没有取消操作，超时/丢 ACK 使执行不确定；控制完成使用回调，不提供返回 future 的 API。

非瞬时协议校验失败停止自动注册恢复，暴露失败，并保留已接受的期望绑定。瞬时传输错误或确认注册缺失仍允许恢复。例如，注册成功后，ACK 的校验数据非法使服务路由不可用；随后连接恢复不能抹除已停止的选择。应用可以检查失败，再显式重试同一选择或清除它。这落实 RT-LIFE-02 的校验边界，不把连接可用当成注册成功。Java 回调顺序和重试入口见 [Java 规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md#3-服务注册和回调)；[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 覆盖非法校验、显式重试及可恢复失败。

## 7. 已确认取舍与验证

身份、迁移与控制/数据耦合的后续方案未实现、不是规范条款，见[审查记录](../reviews/2026-10-07-router-rpc-review.zh-CN.md#design-proposals)。采纳任一方案时，须一起更新对应条款、Java 绑定、互通规则、示例及失败测试。

| 决策 | 原因 | 重新考虑条件 |
| --- | --- | --- |
| 本地 bind 接受，副本最终一致 | 放置由业务负责，避免集群协调器 | 放置需要跨分区原子性 |
| Router 权威分桶与重连快照 | 删除、重建明确，不需要增量重放日志 | 快照规模或恢复时间超过实测预算 |
| 全连接、最多一跳 Router | Router 数量较少，避免环路和广播放大 | Router 规模或拓扑需要多跳 |
| 现有 Node 上的普通 Handler | 一个身份、连接系统及调用完成拥有方；装配在 RPC 外完成 | 缺少具体可复用的 RPC 能力 |

持续负载同步与进程总资源预算仍未验证。建议下一步评估有界、有序的同步流水线，再根据测量决定是否批量发送；两者都不是已实现契约。后续设计仍须保持暂存不可见、连续 revision 校验、快照先于增量和 bind 仅确认本地的边界。有界队列不能无限承受到达速率大于排出速率；显式总量准入或背压策略会改变可观察的拒绝行为，需要规范与实现一起调整。索引和将编码移出状态锁可以保持行为，前提是保留冲突选择、捕获身份和关闭边界。没有发现证据证明已移除代际不可能再注册时，不能静默过期清除移除依据。Java 机制及测量入口记录在[审查记录](../reviews/2026-10-07-router-rpc-review.zh-CN.md)。

Java 机制、默认值及包布局见 [Java 规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java) 覆盖真实 TCP 调用/响应字段、广播、分区冲突、多分块快照与增量顺序、重连、切换和 Handler 异常。[可运行示例](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 为每个模拟进程装配一个应用拥有的 RPC Node。本地测试不是生产规模、集群安全或跨语言认证。自动发现/故障目标选择、持久化、远端取消与 Runtime 适配不属于 V1。
