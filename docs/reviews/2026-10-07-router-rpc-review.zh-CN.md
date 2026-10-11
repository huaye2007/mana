# Router / RPC 审查记录（2026-10-07）

[English](2026-10-07-router-rpc-review.md) | **[简体中文](2026-10-07-router-rpc-review.zh-CN.md)**

> 非规范性记录。现行契约以 OGBS 规范为准；本文保留审查过程、证据、探针结果和后续方案，便于追溯，不新增 MUST 要求。
>
> 后续变化（2026-10-11）：已补充 demo 缺失的 GameRpcConfig，DemoRpcBootstrapTest 可以编译并通过；Spring 托管 RPC 已可通过 `GameRpcConfigurer.decorate/attach/detach` 组合 GameRouter/ServiceRouting。路由请求进入 Runtime 及精确路由回复身份仍由应用实现。

<a id="router-java-review"></a>

## 1. Router Java 审查修复与剩余边界

RPC 的 Handler 修改、就绪和清理钩子已移除，由普通 Handler 装配、组件拥有的协议维护和应用显式关闭替代。已复现的回调锁循环、多 Slot Router 重启、缺失注册 ACK 恢复、已移除精确代际复活、关闭后晚到 Handler 修改状态均有代码调整及永久回归。服务 FIFO 已限制准入；Node 绑定移除使用 owner 索引，不再扫描全部绑定。

RPC 暂缓的有限 pending 准入仍未实现。转发 monitor 仍覆盖编码/发送、快照/冲突扫描；resolveKey 可能重复扫描 Router 来源桶。生产内存/吞吐及持续分区/溢出恢复未验证。移除限制有容量上限，且不跨 Router epoch 持久化，需要发现重放和容量规划。进一步索引、流式快照生成或可配置预算需要实测需求；不引入新的通用扩展框架。

同日后续发现的偏差已有实现修复及 [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 永久回归。入站 DATA 使用与出站转发相同的就绪限制，恢复时保留已提交查询但拒绝业务投递。非瞬时 VERIFY 失败停止注册恢复、暴露错误，并保留期望绑定供显式重试；传输重连不能复活已停止的选择。测试覆盖 Notify/广播/Call/响应拒绝、普通 RPC 连续可用、重新同步后的投递、非法/非瞬时校验 ACK、排队回调完成、显式重试和瞬时错误/NOT_REGISTERED 恢复。这些调整落实 RT-SYNC-03 和 RT-LIFE-02，不替换其契约。临时评估探针已由这些维护中的验证入口替代。

修复验证：`mvn -pl game-router -am test` 通过，Router 共 37 个测试，两轮审查共新增十个回归场景。最新两项回归在修复前的错误返回、就绪实现上均失败。根 `mvn clean verify` 中 Router 和其他框架模块通过，但 game-demo 测试编译因现有 DemoRpcBootstrapTest 导入缺失的 GameRpcConfig 而失败。已确认改动前 HEAD 同样具有该过期导入且缺少对应源码。前一轮审查中，RouterEchoExample.run 使用清理重建后的类独立编译/执行，返回 `hello game-router`。这不代表根目录完整验证成功；无关的示例启动测试编译问题仍未解决。

容量还需要恢复延迟预算。在默认桶上限下，快照需要 391 个 Node 分块、3,907 个绑定分块及 HELLO/BEGIN/END，共 4,301 个确认控制，每个 Peer 只有一个在途。因此理想的串行 ACK 延迟项约为 `4,301 × RTT`（RTT 为 10 ms 时约 43 秒），尚未包含编码、排队和并发变化。服务恢复为每个期望键发送一次确认 bind，对应项为 `keyCount × RTT`。这些是从协议推导的估算，不是实测吞吐或恢复保证。提高容量或改变已确认的顺序契约前，应测量快照完成、恢复时间、monitor 持有时间及 pending 内存。

#### 架构与职责评估

组件归属仍一致：RPC 拥有传输及调用完成；Router 拥有注册、权威桶、同步和转发；发现拥有实例退役；应用/Runtime 拥有业务放置和执行。以下剩余架构缺口不构成反转依赖、创建第二个 RpcNode、Router 业务 pending Map 或通用扩展框架的理由。

- **代际与发现集成需要运维契约。** RouterEngine.locate 选择无符号最大的 nodeEpoch，但 ServiceRouting.register 仅要求不同的非零 epoch，不要求递增。忽略目录 `game-router/target/router-architecture-review` 下的临时真实 TCP 探针，在 A 的 Node 1/epoch 10 断线后，令替代 Node 1/epoch 1 在 B 注册、绑定。两次操作成功；Router 同步后仍可见 epoch 10，路由调用返回 PEER_NOT_FOUND。精确执行 `A.removeNode(1,10)` 后 epoch 1 可见，新调用成功。这是现有确定性选择边界，不是契约违反，也不证明自动故障切换。部署必须提供精确退役/重放，并明确是否有序分配 epoch；数值冲突选择不能提供发现权威。同一 Router 上的新 epoch 也不能在退役/注销前替换其保留的冲突本地注册。Router 成员移除只使用 ID（`unregisterRouter(int)`），因此发现集成必须按自己维护的当前代际过滤迟到的 Router 下线事件，再移除成员。RouterEchoExample 没有提供这些集成机制。
- **Spring 托管 RPC 与路由 Runtime 装配尚未组合。** [RpcConfiguration](../../game-spring/src/main/java/cn/managame/spring/rpc/RpcConfiguration.java) 先运行 GameRpcConfigurer，[GameRpc](../../game-spring/src/main/java/cn/managame/spring/rpc/GameRpc.java) 随后用私有直连 RPC Handler 覆盖 Builder Handler。该 configurer 无法把 GameRouter/ServiceRouting 包在托管应用 Handler 外，RpcNode 也没有 build 后修改 Handler 的入口。[RpcHandlerContext](../../game-runtime/src/main/java/cn/managame/runtime/context/RpcHandlerContext.java) 还缺少来源附着 epoch；GameRpc.reply 使用原生直连回复，ServiceRouting.reply 则需要精确路由来源身份。可选 game-spring/应用适配需要 build 前组合、路由回复身份、协议解码、回调 Route 捕获/drain 和生命周期顺序。复用现有 RPC/Runtime 机制，不把关联复制到 Router，也不创建另一个 Node。该适配未实现，普通 Handler 组合及托管直连 RPC 测试不能证明它可用。
- **路由放置不等于业务执行隔离。** RT-BIND-03 已将独占放置归给应用。bind 成功、确定性胜者选择或进程内 Runtime Executor 都不能证明旧 owner 已停止修改玩家/房间状态。迁移集成需要由执行/存储拥有方检查的应用业务所有权代际，并在需要时明确排空/状态迁移顺序。来源 nodeEpoch 只限制 Node 附着关系，不是每个绑定的业务所有权代际。这不属于 Router，当前路由示例也没有提供端到端迁移/隔离验证。
- **控制/数据耦合及完整复制限制部署形态。** Router 的单 Slot 同时承载同步和业务 envelope，同一转发 monitor 同时处理状态与转发。即使传输和已提交桶仍可用，同步作废也会通过就绪条件阻止业务转发。独立 DATA_ERROR 准入消除了 ACK 队列依赖，但不提供网络带宽隔离。R 个 Router 的全连接有 R×(R−1)/2 条成对连接，每个本地变更最多产生 R−1 次控制发送，每个 Router 保存全局权威状态副本。改变拓扑前应测量小规模集群预算。有限同步流水线及缩小 monitor 是优先选项；若实测规模需要分离有序控制/数据传输路径或分片权威，则需要明确协议/一致性设计。本次评估未实现这些能力。

本次评估不改变运行行为，也不增加新的 MUST 要求。代际边界由临时 TCP 探针验证；Spring/Runtime 组合结论依据当前装配、Context 和回复源码核对。其他架构限制由契约推导，不是生产测量。

#### 重新审视后的建议与 Java 实施顺序

以下建议细化前述审查，仍待实施。记录它们不改变 Java API、错误映射或 Router Profile 字节。身份、迁移及控制面的语言无关推理归 Router 标准第 7 节。

**epoch 的必要性经过验证，而非预设。** 临时 TCP 架构探针还重启了来源 Node 1：旧 epoch 10、新 epoch 1 的业务 requestId 都为 2。旧附着的延迟回复被丢弃，新附着的回复成功完成调用。RpcNode.requestIds 属于实例且从 1 开始；pending 完成及物理连接身份不能提供跨重启的路由附着身份。ID 仍可复用时，保留 nodeEpoch/routerEpoch 的相等性保护。数值最大不能证明更新，建议从未来身份解析器中去掉。精确身份定位及不明确 ID 拒绝，需要永久回归及一致冲突语义后才改变行为。Node ID 永久唯一是另一种可能，但会改变部署分配/耗尽规则，不能在每次路由选择时直接重建共享 RPC Node，影响普通 RPC 生命周期。

**在应用接入实现迁移。** 使用应用自有的逐 key 接纳控制及操作/后续回调 ticket，在现有 Runtime Route 上改变业务阶段。异步完成保留 ticket 直到受保护工作结束，被阻止/拒绝的分派也须释放 ticket。冻结只作用于迁移对象。GameRuntime.shutdown/awaitTermination 覆盖整个 Runtime，不是可恢复的逐 key 控制，也不能在该 Route 上调用/等待。管理工作流通过回调等待逐 key 完成。后续回调及排队持久化携带捕获的所有权版本。冻结的 A 可在交接前完成已接纳工作，但不能接纳新工作或凭晚到回调重新取得所有权。

正常迁移顺序为持久准备、确认旧 unbind、条件提交 owner，再激活/bind B。成功 unbind 后检查 ServiceRouting.bindings：期望 key 须已移除，否则自动恢复可能重新声明它。超时不能确认 unbind，提交交接前先通过已有服务控制状态协调。权威记录保留 migrationId/检查点/版本，便于读取事务未知结果并幂等继续。提交前 B 仅准备，提交后发布失败时 A 保持冻结，B 按同一 migrationId 重试。保留的远端条目可暂时拒绝 B bind，这是可用性缺口，不是覆盖 RT-BIND-01 的许可。重要调用重试使用应用 operationId 和原子记录的结果。

所有权条件和受保护状态修改须在同一事务/条件写中，首版可使用应用原生数据库访问。[Data 标准](../ogbs/OGBS-Data-1.0.zh-CN.md) 将 Repository.update 定义为异步接纳，不是逐对象持久检查点或带所有权校验的事务；Runtime 排空也不证明持久化或外部送达。逐对象保存确认及 token 条件写是实施前提，不是当前 game-data 能力。排队旧版本写入不能在 flush 时借用新 owner 的 token，也不能用 GameData.close 保存单个迁移玩家。先支持正常迁移和整个代际的精确发现退役。若要求保留 Node 存活而强制退役单个 key，还需要绑定所有权版本/权威集成；仅新增 removeBinding(key,expectedNodeEpoch) 管理方法会遗漏 A→B→A 的 ABA 情况。

**重构同步而不扩大公开角色。** 权威/索引状态、Peer 同步和转发可用包私有辅助类表达，保留 GameRouter/ServiceRouting 公开边界。短状态 monitor 内捕获路由决策，或紧凑一致快照视图及 revision；锁外编码/notify/call，每条结果路径都释放私有 buffer/retain 引用。若采用数组复制，快照捕获仍是 O(条目数)，应限制并测量其临界区，不能声称流式编码消除了它。仅捕获成本超过实测预算时才考虑版本化/结构共享视图。有效索引保存全部候选来源，在明确契约调整前保留现有确定性规则。

每 Peer 发送器串行化写准入，只有一个排定的 drain 标记，使用有限 ACK 窗口，同时计入 Peer/进程字节预算。HELLO/BEGIN ACK 是阶段屏障；Node/绑定分块及后续连续增量有序流水发送，END 在此前快照成功后发送。窗口失败先以本地世代/Peer 身份限制全部回调再作废。按需生成分块；捕获视图、暂存、逻辑增量、编码及在途 buffer 都计入预算。本地修改前拒绝预算，与现有本地成功后远端溢出的语义不同，新准入限制须明确同步双层契约。限制每轮 drain，并为每 Peer 设置恢复退避，避免 ACK 回调占用 EventLoop 或持续重建快照。窗口按实测带宽/RTT 及总量限制选择，不给出吞吐默认保证。

严格的控制/业务传输分离属于后续 RPC 集成变更。RpcPeer.startSlot 与 RpcNode.send 将 routeKey 用作起始 Slot，随后尝试其他 Slot，不能靠 routeKey 约定隔离。RPC 需要严格 Slot 发送/准入及 Slot 可用性/绑定代际依据，连接、恢复、body 消费及 pending 完成仍归 RPC。在修改当前单 Slot 校验前，Router 双 Slot 职责、同步尝试隔离及通道丢失时的就绪行为须随协调的 Router Profile 版本说明。该方案不创建第二个 Node，也不允许按过期桶转发。先保留全连接，通过可配置成员数、总字节及同时快照数量明确部署边界。

后续必须验证：复用 ID 后的延迟回复/移除；A 冻结或交接后的回调/写入；未知提交/unbind 结果；丢 ACK 后期望绑定恢复；旧权威阻止 B bind；ticket 拒绝/释放及操作去重；窗口 ACK 失败/乱序、旧回调及暂存不可见；持续负载下字节有界；关闭/引用所有权；严格 Slot 失败不跨职责 fallback。现有 Router 37 项测试及临时探针不证明这些建议功能已经可用。

#### 继续评估容量与失败路径

剩余同步与进程容量风险建议按以下顺序评估；这是后续方案，不是生效要求或已实现功能：

1. 测量每个 Peer 的控制到达/排出速率、排队及在途字节数、ACK 延迟、快照/恢复耗时、按原因分类的重置次数、monitor 持有时间，以及已提交/暂存/索引的总内存。比较持续变更速率与实测排出能力，包含恢复流量。
2. 评估现有单 Slot 上的有限在途状态控制窗口。保持有序应用及连续 revision，条数和字节限制同时覆盖排队与在途工作。不能仅凭 END ACK 开启就绪，必须此前全部快照控制成功。窗口失败或被替代时，应先限制全部晚到回调，再启动新同步。需要同步调整本 Java 规范及 Wire Profile 当前的单在途规则，并补并发/失败回归；根据测量选择窗口，不预设未经验证的固定默认值。从已捕获的不可变视图按需生成快照分块，预留增量容量，保持快照先于增量。批量增量或服务绑定恢复可作为后续选项，需要明确 Wire 操作、版本/兼容性审查与测试；不能直接跳过携带 revision 的变更来合并。若到达速率仍超过排出能力，需要应用速率预算或明确准入策略；只增大 FIFO 仅延后溢出。
3. 缩小转发 monitor 范围：锁内捕获不可变路由决策/身份及所需 payload retain，锁外编码、发送，每条结果路径对应 release。保留关闭/准入边界、控制入队顺序及快照一致性，不能锁外遍历仍可变的表。有效 Node 定位索引和获胜绑定索引需要保存所有候选桶来源，确保移除获胜项后按现有确定性冲突规则显露正确的竞争项。已有 owner 到 key 的移除索引解决的是另一类扫描。
4. 定义进程总量及每 Peer 的字节预算，覆盖已提交状态、暂存、排队/在途控制和 fan-out，同时限制 Router 成员数及并发恢复数。配置化预算及拒绝原本会接受的工作会改变公开默认值/准入行为，需要明确设计调整，并同步双层规范与边界测试。发现移除依据需要权威的代际退役/检查点规则，或配合发现重放的运维滚动方案；超时/LRU 淘汰不能安全解决生命周期上限。

后续本地评估包含以下已解决偏差与剩余容量风险。忽略目录 `game-router/target/router-followup-review` 下的临时 TCP 探针提供初始本地证据，不是生产容量测试。错误返回阻塞与 Slot 重配偏差已由代码修复及永久回归替代，其余事项未实施。

- **Router Peer 重配后可能保留非法 READY 状态（已解决）。** 原实现在重配成双 Slot 后仍报告 READY；现在按当前 Slot 数限制就绪、单播和广播，维护作废非法同步并保留查询，恢复单 Slot 后重新交换快照。第 2 节及永久回归 `routerSlotReconfigurationInvalidatesReadinessAndRequiresNewSnapshot` 定义、验证该边界。
- **业务 Call pending 没有有限准入（已确认的现有 RPC 能力缺口）。** 真实本地 Router 将 10,000 个 Call 投递到只消费请求、不回复的服务。来源使用 60 秒调用超时；10,000 个调用全部留在其 RPC Peer pending Map，未发生准入失败。有界服务控制 FIFO 不限制这些业务调用，可写 TCP 也不表示响应能力。这确认的是准入机制缺失，不是内存溢出阈值或受支持吞吐。Node 级 Call 准入归 RPC 暂缓的 R-SEND-04；Router 应复用其完成语义，不另设业务 pending Map。参见 [RPC Java 规范](../ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)。 超时会移除 pending 并报告 TIMEOUT，晚到响应不会再次完成；这不表示永久泄漏。风险是超时前的并发积压，约为到达速率乘平均等待时间。
- **路由错误等待同步 ACK（已解决）。** 初始探针暂扣 VERIFY ACK，在目标服务断开后观察到 DATA_ERROR 的 PEER_NOT_FOUND 排在同步队列中，200 ms Call 超时。错误返回现经 Notify 独立发送；Wire Profile 已记录其表示与旧 Call 的接收兼容性。永久回归 `routedErrorDoesNotWaitForSynchronizationAcknowledgement` 覆盖修复，不保证不可用返回链路的交付。
- **持续变更可能超过串行同步能力（协议推导的容量风险）。** 只有一个确认控制在途时，理想排出速率最多约为每秒 `1 / RTT` 个控制。多个服务可以持续产生注册、bind 和 unbind；它们本地成功不等待远端排出。总到达速率超过排出速率时，8,192 个控制的队列最终溢出，作废流并重新启动完整快照。在最大快照规模下，如果捕获期间没有 ACK 排出，留给后续变更的排队空间约为 3,892 个。持续过载可能反复破坏快照进度。现有收敛以来源状态停止变化为条件，持续负载恢复预算未验证。服务恢复逐键确认 bind，也会在期望集合恢复完成前保持出站业务路由不可用。
- **每桶限制不构成进程资源预算（结构性、未实测的生产风险）。** Router 成员配置没有总量上限；每个 Peer 可以保留已提交桶、暂存另一桶并拥有编码控制队列。表还维护 owner 到 key 的索引。内存随来源桶数、并发恢复和 fan-out 增长，而不只受本地百万绑定限制。快照编码、冲突扫描和业务编码/fan-out 共用一个 monitor；这里阻塞会延迟共享 EventLoop 上的其他 RPC 连接。多个桶声称同一 key 时，`resolveKey` 可能产生 Router 桶数的平方级工作；服务整体查询扫描全部注册并重复定位 owner。选择索引或预算前，应测量字节数、monitor 持有时间和尾延迟。独立的十万对移除依据还是生命周期限制，因此发现事件变化也需要运维预算；用满后，新增移除会在清理其存活路由前被拒绝，这是第 2 节明确的设计边界。

<a id="rpc-integration"></a>

## 2. RPC/Router 集成审查

已复现的拓扑监听器/服务 monitor 锁循环通过移除 RPC 钩子、将服务发送及应用完成移出路由状态保护来解决。Router 在 Node 构造时作为普通 Handler 提供。协议拥有的注册校验处理多个 Slot 保持传输连续可用时的远端 Router 替换；应用拥有的路由 close 在 RPC 资源关闭前限制晚到状态修改。详见 [Router 审查修复](#router-java-review) 及永久回归测试。

RPC Node 只增加 Peer 可用性/数量只读查询；握手、发送、pending、超时和关闭机制保持原有实现。R-SEND-04 有限 pending 准入仍未实现。Router 独立服务控制 FIFO 已限制容量，但不能限制对可达慢 Peer 的普通/业务 RPC 调用。生产吞吐、内存和过载边界未验证；应优先按实际准入需求处理，不能把路由策略放入 RPC。

<a id="design-proposals"></a>

## 3. 身份、迁移与控制/数据耦合的建议方案（自 Router 规范移入）

以下是经过推敲的建议，不是已实现行为，也不替换前文已确认条款。RT-BIND-03 当前仍按数值 epoch 选择冲突胜者，转发仍要求双向就绪。后续实施须一起更新对应条款、Java 绑定、互通规则、示例及失败测试。

**身份。** nodeId 可复用时，保留代际身份。nodeEpoch 标识服务附着关系：同一附着重连保持不变，重启/重新选择产生不同 token。routerEpoch 标识 Router 生命周期；revision 排序该生命周期内的变化。这些 token 需要相等性检查，不需要时间排序。删除它们要求 Node 身份永不复用，或在转发、回复和发现事件中携带等价的注册/连接代际。连接身份不能单独识别跨中间 Router 返回的延迟回复，新 RPC Node 的调用 ID 序列也会重新开始。把附着身份移到其他字段不会消除这一需求。

建议调整的是去掉按 epoch 数值选胜者，而非直接删掉 token。对明确携带 `(nodeId,nodeEpoch)` 的回复/投递按精确附着定位；仅 nodeId 或绑定查询遇到不兼容代际、且没有权威退役决定时，记录冲突并拒绝路由为不可用。保留候选直到精确发现清理，不猜测哪个实例更新。这改变冲突行为，需要协调兼容性决策。Node 代际也不同于下面每个玩家/房间的业务所有权版本。

**正常业务迁移。** 在应用的权威事务存储中为每个业务 key 保存 owner 附着、所有权版本、migrationId、阶段及持久检查点。建议顺序：条件认领迁移意图，此时 A 仍为 owner；冻结 A 对该 key 的新接纳；排空已接纳工作及全部后续回调；确认持久检查点并准备 B，此时 B 不执行；确认 A 的 unbind，使服务期望集合也忘记该 key；条件提交 owner=B 并产生新版本；激活并发布 B 的绑定。所有权提交是权威切换点，路由发布最终一致且可滞后。即使来源持有旧路由，A 在交接后也停止执行。B 的 bind 可能遇到 A 的远端保留条目，需要按迁移策略等待/重试控制发布，不能覆盖。迁移本地完成要求 B 已激活且 bind 成功，不声称集群已经可见。

按 key 冻结/排空归应用业务生命周期，使用现有 Runtime Route 串行修改状态。跟踪已接纳操作及其异步后续直到结束；队列标记或 Handler 返回都不够。迁移单个玩家不能关闭整个 Runtime，Route 工作也不能等待自身排空。unbind 超时是结果不确定，不是提交接管的许可；先通过已有控制恢复/协调确认。所有权提交前，只有确认 A 仍拥有该版本并协调绑定后才能撤销迁移、恢复 A。提交后按同一 migrationId 重试激活/发布，不能因为回复或 bind ACK 丢失而复活 A。

**故障接管与副作用。** 权威事务改变 owner/版本，每次受保护的状态写入都在与副作用相同的原子操作中校验 owner/版本。旧实例即使停顿后恢复、错过发现通知，也不能在接管后提交。写入前查一次 owner、随后无条件写入不够。尽可能将所有权条件与业务状态放在同一事务存储中；外部协调记录本身不能限制另一个数据库。排队写入保留原授权版本，不能使用保存线程执行时的当前版本。重要外部副作用需要接收方校验或事务 outbox/幂等契约。未持久化内存不会因为存在版本限制而恢复。

首版建议支持正常逐 key 迁移及发现确认的整个代际退役。A 不可达、其绑定又无法被权威清理时，B 等待/报告迁移失败，不强行创建竞争绑定。若要求只强制接管一个 key、同时保留 A 的其他 key，需要补充每绑定退役/所有权版本集成；仅 owner Node/epoch 在 key 后来回到同一附着时存在 ABA 风险。当前 bind/unbind/removeNode API 没有提供该扩展。需要显式恢复的重要 Call 使用业务 operationId 及事务保存的结果；Router 不自动重试业务消息，Notify 保持现有丢失边界。

**控制/数据方案。** 首阶段保留小规模全连接及当前就绪安全边界。内部区分权威状态、每 Peer 同步和转发；缩短状态临界区，按捕获的身份在锁外编码/发送。同步使用条数与字节双重限制的在途窗口，从一致捕获的快照按需产生分块，预留增量预算，并限制控制注入速率。身份/BEGIN 阶段仍为确认屏障，END/就绪等待全部此前快照控制成功。失败世代限制晚到回调，只修复自身路由流。除每 Peer 队列外，还限制已提交/暂存/索引的总内存、成员数及并发快照。持续到达超过排出能力时，需要明确来源准入/速率限制或恢复退避；有界队列不能保证无限负载下收敛。

这一阶段降低耦合，但不提供共享 Slot 的物理带宽隔离，也不允许通过未就绪状态转发。若要求物理隔离，建议下一阶段在同一 RPC Node/Peer 上使用两个有明确职责的 Slot：有序状态控制、业务/错误流量。RPC 须提供不跨职责 fallback 的严格 Slot 准入；当前 routeKey 亲和允许 fallback，因此不够。控制连接替换也需要同步尝试的代际限制。移除当前单 Slot 约束前，须定义 Slot 故障/就绪、队列所有权及协议兼容性。若实测全局复制预算需要分片权威，那属于后续拓扑调整，不是顺手的内部重构。
