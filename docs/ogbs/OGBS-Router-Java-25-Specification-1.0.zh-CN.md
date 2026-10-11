# OGBS Router Java 25 开发规范 1.0

[English](OGBS-Router-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)**

对应标准：[Router 规范](OGBS-Router-1.0.zh-CN.md)。字节：[Router Profile v3](../rpc-wire.zh-CN.md#router-profile-v3)。状态：已实现并具有本地 TCP 契约与恢复测试；生产容量和跨语言互通未验证。

## 1. 模块、所有权与公共边界

`cn.managame:game-router:1.0.0-SNAPSHOT` 使用 JDK 25，依赖 game-rpc，传递获得 Network/Core/Netty。RPC 不反向依赖 Router。每进程只有一个应用拥有的 RpcNode。GameRouter 和 ServiceRouting 是实现现有 RpcHandler、AutoCloseable 的 final 具体类。先构造路由角色，通过 RpcNodeBuilder.handler 设置，再在 rpc.start() 前调用 routing.start(rpc)。start 只关联该 Node 一次并启动路由维护，不启动网络。应用必须将该角色作为 Node 的 Handler；start 不检查或替换 RPC 的私有 Handler。空参数抛 NullPointerException；重复 start 或关闭后 start 抛 IllegalStateException。

| 公共类型 | 职责 |
| --- | --- |
| node.GameRouter | 角色装配、转发 Router 成员、路由查询和普通 RpcHandler 分派 |
| node.ServiceRouting | 服务注册、绑定、寻址、回复和普通 RpcHandler 分派 |
| call.RouterHandler、call.RoutedRequest | 借用业务投递及业务/注册回调 |
| route.BindingKey、RouteBinding、NodeRegistration | 经校验的不可变值 |
| error.RouterErrorCodes | Core 数值错误分配 |

GameRouter.forRouterNode(long,RpcHandler) 创建转发角色；GameRouter.forServiceNode(int,RouterHandler,RpcHandler) 创建服务角色。最后一个参数接收无关的直连 RPC；同时实现两个业务接口的 Handler 可以传入服务工厂的两个 Handler 参数。ServiceRouting 构造器保持 package-private；RouterEngine、RouterTable、RouteIndex、RouterWire、RoutingRpcHandler 均为包级内部实现。私有回调身份区分路由完成与普通 RPC 回调。不需要额外接口/实现拆分、Handler 修改 API、连接监听器或 RPC 清理监听器。

遵循 [组件职责边界](OGBS-Router-1.0.zh-CN.md#1-职责与组合)。新增公共类型和扩展点需要具体调用方/集成需求；只有缺少可复用 RPC 能力时才修改 RPC。

## 2. Router 成员与统一 RPC 连接 API

```java
GameRouter router = GameRouter.forRouterNode(routerEpoch, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(101).bindAddress(address).handler(router).build();
router.start(rpc);
router.registerRouter(102); // 远端 Router 声明 101
rpc.start();
rpc.addPeer(102, otherRouterAddress, 1);
// 应用管理上下文；通常在 finally 中：
router.close();
rpc.close();
```

成员变更要求已调用 routing.start、ID 非零且非自身、角色未关闭。registerRouter 幂等；拒绝已有本地服务 ID 或多 Slot Peer。两端显式声明成员。缺失/断开的 Peer 只保留路由成员配置，维护等待但不创建连接；已连接的单 Slot Peer 在两个方向各开启一个会话；对已连接的 Router 声明成员时也会立即发送 SYNC。来自未声明 Peer 的同步帧被忽略（之后声明会请求会话），普通 RPC Peer 不会自动升级成 Router。

所有连接使用 rpc.addPeer(id,address,slots)。Router 对只有一个连接发起方，并且恰好一个 Slot，同步流依赖其帧顺序；服务可以多 Slot。在成员声明后配置多 Slot Peer 可以建立传输，但路由保持不可用，直到应用重新配置 RPC。unregisterRouter 从索引删除该 Router 的桶并丢弃发往它的同步流，不移除 RPC Peer。地址/连接数变更沿用 RPC 规则。

同步要求恰好一个 Slot。来自其他 Slot 数 Peer 的同步帧被拒绝，维护任务重置出站会话并把副本标记为损坏，最后提交的副本仍可查询。业务数据不受此状态控制。应用恢复为单 Slot 后，两个方向重新开启会话。Router 不修改共享 RPC Peer，直连 RPC 仍可使用。[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 中的 `routerSlotReconfigurationStopsSynchronizationButKeepsReplicas` 覆盖重配为两个 Slot、保留副本、不再更新以及恢复单 Slot 后重新同步。

removeNode(nodeId,nodeEpoch) 即使注册尚未到达，也记录发现移除的精确代际。仅匹配当前本地代际才删除并发布权威移除；旧事件不能移除新注册。随后同一对身份 REGISTER 返回 NOT_REGISTERED。每个 Router 对象生命周期保留最多 100,000 对不同移除身份，继续新增在修改前抛 RejectedExecutionException；重复移除幂等。限制不持久化，也不作为发现权威复制：Router 重启后发现需要重放当前移除，并向相关拥有方提供事件。新实例使用新 epoch。容量需要运维规划；不静默淘汰，也不实现服务发现提供方。

isRouterReady(id) 只反映入站方向：RPC Peer 以恰好一个 Slot 连接，且本 Router 持有该 Router 桶的已提交、无缺口副本。对方持有的本 Router 副本由对方报告。恢复中的副本仍可查询。查询返回不可变副本/Optional，结果是快照而非投递保证；resolve 要求正 serviceId，接受任意 long key。所有查询读取无锁 RouteIndex，从不等待同步或服务控制。

业务数据从不受就绪状态控制（RT-SYNC-05）。接受来自任何已声明 Router 的 DATA；目标 Router 按精确 Node/epoch 检查自身注册，响应和路由错误发往 envelope 中记录的 origin Router。[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 中的 `responsesAndRoutedErrorsReturnToTheOriginRouterWithoutItsReplica` 让一个 Router 收不到另一方的同步流，验证跨越它的路由回复与路由 PEER_NOT_FOUND；`streamGapKeepsReplicaQueryableAndResynchronizes` 验证缺口恢复期间副本仍可查询。

## 3. 服务注册和回调

```java
ServiceRouting routing = GameRouter.forServiceNode(MATCH, routedHandler, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(1).bindAddress(address).handler(routing).build();
routing.start(rpc);
routing.register(101, nodeEpoch, error -> reportRegistration(error));
rpc.start();
rpc.addPeer(101, routerAddress, 3);
// 注册成功后：routing.bind(playerId, callback)。
// 应用关闭：routing.close(); rpc.close()。
```

公开操作为 register(int,long,RpcCallback<Integer>)、isRegistered()、bindings()、bind(long,RpcCallback<Integer>)、unbind(long,RpcCallback<Integer>)、unregister(RpcCallback<Integer>)、unregisterAfterLoss()。serviceId 固定且为正。register 要求 routing.start、非零非自身 Router ID、非零 epoch 和非空 callback。新选择要求不同 epoch。已有选择仅在尝试失败、没有进行中的注册/恢复且初始回调已完成时，允许以相同 Router/epoch 显式重试；其他选择需先清除或迁移。选择不创建 Peer。

已连接时立即发 REGISTER，否则路由维护 tick 等待传输；等待没有独立连接期限。每个发出的控制使用 RPC 配置的超时。成功 ACK 带 routerEpoch，随后逐项恢复已接受的期望键。初始 callback 在恢复结束后返回首次尝试的结果，只调用一次；首次瞬时失败可以先报告失败，后台恢复随后再成功。RouterHandler.onRegistration 另行报告完成的注册/恢复尝试，有初始 callback 时排在它之后；不报告每个 Slot 变化或成功的周期校验。

服务最多一个在途控制和 8,192 个排队控制。bind/unbind/unregister 按 FIFO 准入；满队列在接纳前抛 RejectedExecutionException，不调用被拒绝操作的 callback。恢复从期望集合逐次生成一个控制，不把所有键一次性塞入队列。成功 bind/unbind 在 callback 前更新期望集合。超时不证明未执行，也不更新已接受期望状态。恢复在首次失败停止，可留下部分远端绑定，报告错误并保持未就绪，直到协调成功。

选中关系仍有效时，注册对 UNAVAILABLE、PEER_NOT_FOUND、PEER_REMOVED、TIMEOUT 重试。远端注册拒绝、非法 ACK 和其他非瞬时错误停止自动重试；调用方可检查后显式重试相同失败选择。校验或绑定控制的 NOT_REGISTERED 先触发重新注册。发现已移除的代际即使重连仍被 Router 拒绝。业务调用不重试。

非瞬时 VERIFY 失败使注册失效，以该错误完成排队控制一次，并通过 RouterHandler.onRegistration 报告该错误一次；回调在状态转换后、服务 monitor 外执行。期望绑定保持不变，已完成的初始注册 callback 不再调用。已停止的选择跨已观察到的传输丢失/重连仍保持停止。以同一 Router/epoch 显式 register 开启新尝试，在成功完成前恢复期望键；已结束的失败选择也可通过现有 unregister 操作清除。瞬时 VERIFY 失败和 NOT_REGISTERED 保留自动恢复。`nontransientVerificationFailureStopsRetriesAndPreservesExplicitRetry` 覆盖缺失、截断、零 epoch、尾随字节 ACK 和远端非瞬时拒绝；`recoverableVerificationFailureStillRestoresBindingsWithoutResettingPeer` 覆盖超时和注册缺失。

维护 tick 为 100ms；距上次校验至少一秒后，空闲已注册服务发送 VERIFY，携带最后确认的 routerEpoch 和自身 nodeEpoch。替代 Router 或缺失注册触发重新注册及期望键恢复，即使多个 Slot 让 Peer 始终可用。协议状态完好的短暂重连不一定再产生注册事件。tick 观察到传输丢失时，使服务协议状态失效，用 UNAVAILABLE 限制过期控制，同时保留期望键及未完成的初始 callback。generation 忽略晚到响应；底层 pending 和超时仍归 RPC。

控制 callback 返回零或正框架错误；校验在准入前拒绝，每个已接纳 callback 恰好完成一次。清除丢失选择返回 PEER_REMOVED，路由关闭返回 NODE_CLOSED。callback RuntimeException 独立记录，不阻塞 FIFO。回调在服务 monitor 外执行，可能早于发起方法返回，必须快速/非阻塞，状态转换后可重入。isRegistered 还要求恢复完成且传输当前可用；它是可能在下一次校验前过时的本地协议认知，不是发现存在性。

unregister 也接受健康 Peer 上已结束的失败选择，要求没有进行中的注册/恢复及初始 callback。DETACH 确认 NOT_REGISTERED 表示该精确代际已不存在，按成功完成本地清理，不移除其他代际。这样可清除并重新选择，不重置共享 Peer；无传输时使用 unregisterAfterLoss。正常迁移等待 unregister 成功：旧注册/绑定和期望键在 callback 前清除；已接纳控制完成期间拒绝新工作。unregisterAfterLoss 要求已有选择且没有可用 Slot，清除选择但保留期望键，由应用明确选择新 Router/epoch。两者不删除 RPC Peer，也不取消普通/业务 pending。旧 Peer 恢复不能恢复已清除选择。不提供自动 Router 选择器。

## 4. 业务寻址、完成与 body 所有权

ServiceRouting 直接提供 notifyNode、notifyBinding、broadcast、callNode、callBinding、reply。callNode/callBinding 有默认及显式 timeoutMillis 重载，使用 RpcCallback<T>。默认使用所提供 RpcNode 的 callTimeout（通常 5 秒）；显式超时须能表示为正纳秒。出站 requestId 须为零，由原 RpcNode 分配 ID。Notify/broadcast/reply 返回 RpcSendStatus；广播只允许 Notify。

玩家之间寻址时，接收玩家所属服务先调用 routing.bind(playerId,callback)。发送服务可用 routing.notifyBinding(PLAYER_SERVICE,targetPlayerId,request)，需要响应时用 callBinding，不必自行查询 nodeId/地址。目标玩家应明确放入 RpcRequest.businessId（配合应用的玩家 businessIdType）或业务 payload；RoutedRequest 不单独暴露绑定键。设置 routeKey=targetPlayerId 可保留 RPC 亲和，但不自动实现玩家串行执行。onRoutedRequest 由应用分派到本地玩家/会话，必要时再通过其客户端连接投递。入口认证发送玩家，sourceNodeId 只表示发送服务身份。Notify ACCEPTED 只表示首跳接纳，不表示玩家收到；缺少绑定时 Notify 被丢弃，Call 返回 ROUTE_NOT_FOUND。不宣称内置聊天/会话/离线消息 API。

RouterHandler.onRoutedRequest 接收精确 sourceNodeId/sourceNodeEpoch、调用方所在 Router 的 originRouterId 以及借用的内层 RpcRequest。reply 接收该 RoutedRequest 及匹配的非零 RpcResponse ID，经 origin Router 指向原来源注册，保留 error/Metadata/body。延迟回复可保存不可变身份、origin 与 ID（三参数 RoutedRequest 构造器的 origin 为 0，此时本地 Router 在索引中定位调用方）；延迟 body 须 retain/copy 并对应 release。普通直连流量仍经原 RpcHandler。

原 Node 拥有一个外层 RPC pending 调用。中间 Router 用 Notify envelope 转发，并在内层保留原 ID，不创建业务 pending Map。响应 envelope 发往 origin Router，由它向调用方发送原生 RpcResponse；RPC 删除 pending，私有适配器解包，携原 command/callback 调用 RouterHandler.onResponse。应用解码业务响应并调用 RpcCallback。远端错误进入 onResponse；本地超时、移除、关闭和立即失败进入 onFail。第 3 节的控制回调已经携带错误码，按直接完成契约执行。

动态缺失返回 ROUTE_NOT_FOUND；物理缺失返回 RPC PEER_NOT_FOUND；下一跳不可用返回 RPC UNAVAILABLE。业务 Handler RuntimeException 尝试返回路由 HANDLER_ERROR。返回链路失败仍可超时。已匹配原生响应的内层数据非法时，携原 command/callback 以 onFail(PROTOCOL_ERROR) 完成。合法 onResponse 后的应用异常只记录，不二次完成。

Router 间 DATA_ERROR 是发往 origin Router 的独立 rpc.notify，不进入同步流，不创建中间 pending，也没有确认。私有编码 body 无论成功、拒绝或发送异常均释放。发送异常只记录，不影响同步，也不重试错误；准入失败时仍由原调用的既有超时结束。接收方只接受来自已声明 Router 的 DATA_ERROR，且仅当原目标的精确 Node/epoch 仍在本地注册时投递原生错误响应。传输表示见 Wire Profile。

| 边界 | 所有权 |
| --- | --- |
| 编码前空/非法参数或注册不可用 | 调用方保留原 body |
| 通过校验的路由发送/回复 | 消费原 body 一个引用，编码/传输失败亦然 |
| 入站路由请求/响应 | 回调期间借用，不能直接 release |
| 转发/广播副本 | 每次编码 retain 一个内层 body 引用，由编码消费 |
| Router 同步流 | 路由拥有；发送时从捕获的值编码，由 RPC 释放 |

路由 envelope 的 Request.requestId 为零，经现有 rpc.notify 发送；原业务 ID 保留在内层。原生返回响应经 rpc.reply(target,0,0,response) 发送，保留 ID，优先 Slot 0，失败按普通无亲和规则回退。中间 Router 不创建业务 PendingCall，也不需要新的 RPC 发送原语。请求用内层 routeKey 亲和；业务身份/Metadata 不变。ACCEPTED 只表示首跳本地准入，不证明端到端执行；不增加业务重试或远端取消。

## 5. 同步、线程和边界

RouterEngine 只有一把小锁 `authority`，保护本地桶、发现移除限制，以及本地变更进入各 Peer 同步流的顺序。服务控制在该锁内更新本地桶、把变更镜像到 RouteIndex，并向每个活动同步流队列追加一个操作；编码和发送在释放锁后进行，且先回复服务控制，再推送同步流。每个副本有自己的锁，只由该 Peer 的同步流写入。RouteIndex 用 ConcurrentHashMap 把 Node ID 和绑定键映射到小型不可变候选数组，每个来源桶一个候选；写入方原子替换单个键的数组，读取方从不阻塞。route、resolve、serviceNodes 和 isRouterReady 不获取共享锁，因此捕获或应用大快照期间转发和查询照常进行。[RouterSyncTest](../../game-router/src/test/java/cn/managame/router/node/RouterSyncTest.java) 中的 `lookupsNeverBlockWhileTheAuthorityIsBusy` 在另一线程持有 authority 锁，验证 resolve 与 serviceNodes 仍能完成。

每个 Peer 每个方向的出站流是一个 FIFO，包含一个快照、增量操作和心跳标记。快照在 `authority` 内捕获为数组，发送时按每块最多 1,024 条惰性编码；连续操作合并为一个最多 1,024 个操作的 DELTA 帧。单个推送者用 rpc.notify 发送队首，Slot 接受后才消费；被拒绝的帧 1 ms 后重试，背压只会让流暂停，不会产生缺口。每个流最多积压 100,000 个操作，再多则以快照重启会话。

接收方在副本锁内应用每一帧。BEGIN 开始不可见暂存；NODES/BINDINGS 填充暂存；END 校验条数后，只把新旧桶的差异发布到索引，重新同步期间未变化的条目不会消失。DELTA 直接更新已提交桶和索引。缺口或非法帧把副本标记为损坏并发送 SYNC（每 100 ms 最多一次）。若 SYNC 指明的是旧会话且当前会话开始不足一秒，权威方忽略它，因此一串请求只导致一次重启。

每个角色有一个 daemon 维护线程，每 20 ms 运行一次。对每个已声明 Router：单 Slot 连接消失时重置出站会话并标记副本损坏；活动副本 3 s 无任何消息或暂存快照停滞 1 s 时标记损坏；未就绪时每秒重发 SYNC；Slot 可用且没有会话时开启会话；1 s 无流量时排入 STATE 心跳；然后推送。服务角色保留 100 ms tick 用于注册校验。这些间隔不是就绪租约或恢复期限。

默认值：每个桶 100,000 个 Node、1,000,000 个绑定；每块 1,024 条，或每个 DELTA 1,024 个操作；每个同步流 100,000 个待发送操作；服务 8,192 个排队控制加一个在途；本地移除身份限制 100,000 对。内层帧最多 4 MiB 减 80 字节，也受 Node 外层帧上限约束。这些组件 V1 默认值没有配置 API。

服务发送在 monitor 内捕获已校验身份，编码和发送在 monitor 外执行。RPC 立即失败时应用 Handler 不持有该 monitor，允许的 Peer 管理不会再形成原拓扑/服务锁循环。路由 close 前已准入发送仍可随后执行，沿用捕获身份；close 不取消业务执行。应用回调从不在 Router 的 `authority` 或副本锁下分派。

应用在管理上下文调用 routing.close，再调用 rpc.close。路由 close 幂等，限制新状态修改，清除表/期望键/缓冲，停止维护并将剩余服务控制完成一次。已捕获完成结果或已分派的应用回调仍可执行完；路由 close 不是 RPC 的 worker/资源屏障。即使接收 Handler 在路由 close 前已被 RPC 准入，也不能在 close 后重新创建 Router 状态。RPC close 仍是网络/pending 完成屏障。启动失败也必须关闭路由角色。路由 close 不宣告远端服务下线，该事件归发现所有。

## 6. 异常、兼容性与验证

必要参数为 null 抛 NullPointerException；非法 ID/服务/epoch/超时/回复 ID 抛 IllegalArgumentException；缺少注册、错误生命周期、重复 start 或 Router 多 Slot 冲突抛 IllegalStateException；服务队列/移除限制满时抛 RejectedExecutionException。地址/Slot 数归 RPC 校验。Router Profile v3 用会话流（SYNC/BEGIN/NODES/BINDINGS/END/DELTA/STATE）取代 Router 间带确认的控制，删除 Router 间 VERIFY，并在数据 envelope 中加入 origin Router；拒绝 v1 和 v2，不支持混合版本回退。服务控制（REGISTER/BIND/UNBIND/DETACH/VERIFY）字段与 v2 相同。RPC Wire framing、type、握手不变。路由参与方需一起升级，普通 RPC 参与方保持兼容。移除的工厂/钩子不提供兼容别名。

源码：[GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java)、[ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java)、[RouterEngine](../../game-router/src/main/java/cn/managame/router/node/RouterEngine.java)。验证：[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)、[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java)、[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)、[RouterTableTest](../../game-router/src/test/java/cn/managame/router/node/RouterTableTest.java)、[RouterSyncTest](../../game-router/src/test/java/cn/managame/router/node/RouterSyncTest.java)。[RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 展示完整装配及资源关闭顺序。

使用 mvn -pl game-router -am test 和根 mvn clean verify 验证集成。本地 TCP 测试覆盖多连接、健康传输 ACK 丢失、注册前后精确移除、回调重入、关闭后延迟 Handler、FIFO 容量、同步流缺口、废弃会话、随机丢帧、变更期间反复断线、四 Router 全连接及重启、路由身份及保留普通调用；不证明生产容量、持续过载或跨语言互通。

<a id="7-known-limits"></a>

## 7. 已知限制

本节只列当前仍成立的边界。2026-10-07 审查的过程与证据保留在[审查记录](../reviews/2026-10-07-router-rpc-review.zh-CN.md)中，该记录不是规范条款。

- **调用准入**：业务 Call 的 pending 没有有限准入，依赖 RPC R-SEND-04（未实现）。
- **代际与发现**：`locate` 选择数值最大的 nodeEpoch。精确退役、Router 重启后的事件重放、成员移除的代际过滤由发现集成负责，示例未提供。
- **迁移隔离**：bind 成功不证明旧 owner 已停止执行；业务所有权代际与条件写归应用（RT-BIND-03）。
- **恢复成本**：每次恢复都发送完整桶，没有增量追赶。捕获快照时在 `authority` 内把桶复制为数组，耗时与桶大小成正比；这段时间会延迟本地服务控制，但不影响查询和转发。变更速率持续高于排出速率时会反复重启会话。
- **资源预算**：成员数、已提交/暂存桶和同步流队列没有进程总量上限；快照期间副本同时持有已提交表和暂存表。
- **多 Slot Router Peer**：同步要求恰好一个 Slot。配置为多 Slot 的 Router Peer 保留最后副本，但在重新配置前不再收到更新。
- **Spring 组合**：托管 RPC 通过 `GameRpcConfigurer.decorate/attach/detach` 组合 GameRouter/ServiceRouting（见 [Spring Java 规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)）。路由请求进入 Runtime 及精确路由回复身份（来源 nodeEpoch、origin Router）仍由应用在 RouterHandler 中实现。
- 生产吞吐、内存及持续分区恢复均未验证。
