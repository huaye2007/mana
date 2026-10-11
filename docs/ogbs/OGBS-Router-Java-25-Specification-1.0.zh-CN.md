# OGBS Router Java 25 开发规范 1.0

[English](OGBS-Router-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)**

对应标准：[Router 规范](OGBS-Router-1.0.zh-CN.md)。字节：[Router Profile v2](../rpc-wire.zh-CN.md#router-profile-v2)。状态：已实现并具有本地 TCP 契约与恢复测试；生产容量和跨语言互通未验证。

## 1. 模块、所有权与公共边界

`cn.managame:game-router:1.0.0-SNAPSHOT` 使用 JDK 25，依赖 game-rpc，传递获得 Network/Core/Netty。RPC 不反向依赖 Router。每进程只有一个应用拥有的 RpcNode。GameRouter 和 ServiceRouting 是实现现有 RpcHandler、AutoCloseable 的 final 具体类。先构造路由角色，通过 RpcNodeBuilder.handler 设置，再在 rpc.start() 前调用 routing.start(rpc)。start 只关联该 Node 一次并启动路由维护，不启动网络。应用必须将该角色作为 Node 的 Handler；start 不检查或替换 RPC 的私有 Handler。空参数抛 NullPointerException；重复 start 或关闭后 start 抛 IllegalStateException。

| 公共类型 | 职责 |
| --- | --- |
| node.GameRouter | 角色装配、转发 Router 成员、路由查询和普通 RpcHandler 分派 |
| node.ServiceRouting | 服务注册、绑定、寻址、回复和普通 RpcHandler 分派 |
| call.RouterHandler、call.RoutedRequest | 借用业务投递及业务/注册回调 |
| route.BindingKey、RouteBinding、NodeRegistration | 经校验的不可变值 |
| error.RouterErrorCodes | Core 数值错误分配 |

GameRouter.forRouterNode(long,RpcHandler) 创建转发角色；GameRouter.forServiceNode(int,RouterHandler,RpcHandler) 创建服务角色。最后一个参数接收无关的直连 RPC；同时实现两个业务接口的 Handler 可以传入服务工厂的两个 Handler 参数。ServiceRouting 构造器保持 package-private；RouterEngine、RouterTable、RouterWire、RoutingRpcHandler 均为包级内部实现。私有回调身份区分路由完成与普通 RPC 回调。不需要额外接口/实现拆分、Handler 修改 API、连接监听器或 RPC 清理监听器。

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

成员变更要求已调用 routing.start、ID 非零且非自身、角色未关闭。registerRouter 幂等；拒绝已有本地服务 ID 或多 Slot Peer。两端显式声明成员。缺失/断开的 Peer 只保留路由成员配置，维护等待但不创建连接；已连接的单 Slot Peer 开始快照同步。未声明的协议身份被拒绝，普通 RPC Peer 不会自动升级成 Router。

所有连接使用 rpc.addPeer(id,address,slots)。Router 对只有一个连接发起方，并且恰好一个 Slot，以保持控制顺序；服务可以多 Slot。在成员声明后配置多 Slot Peer 可以建立传输，但路由保持不可用，直到应用重新配置 RPC。unregisterRouter 删除可见桶与排队控制，不移除 RPC Peer。地址/连接数变更沿用 RPC 规则。

就绪查询及所有业务转发（包括广播）在维护 tick 前也检查当前 Slot 数。维护观察到非单 Slot 配置时作废同步、释放排队控制，并保留已提交查询。恢复为单 Slot 后，双向新快照完成才重新就绪。非法配置上的同步消息不能提交桶。Router 不修改共享 RPC Peer，直连 RPC 仍可使用。`routerSlotReconfigurationInvalidatesReadinessAndRequiresNewSnapshot` 在 [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 覆盖就绪 Peer 重配、Notify/广播/Call 拒绝、保留查询及恢复。

removeNode(nodeId,nodeEpoch) 即使注册尚未到达，也记录发现移除的精确代际。仅匹配当前本地代际才删除并发布权威移除；旧事件不能移除新注册。随后同一对身份 REGISTER 返回 NOT_REGISTERED。每个 Router 对象生命周期保留最多 100,000 对不同移除身份，继续新增在修改前抛 RejectedExecutionException；重复移除幂等。限制不持久化，也不作为发现权威复制：Router 重启后发现需要重放当前移除，并向相关拥有方提供事件。新实例使用新 epoch。容量需要运维规划；不静默淘汰，也不实现服务发现提供方。

isRouterReady 要求 RPC 当前可用、当前恰好一个 Slot、入站状态已提交、出站快照 END 已确认。恢复期间最后提交状态仍可查询，但不能替代转发就绪。查询返回不可变副本/Optional，结果是快照而非投递保证；resolve 要求正 serviceId，接受任意 long key。

RouterEngine 在 monitor 内按同一就绪条件准入远端 DATA，包括 Notify、广播、Call 和响应转发。恢复时保留的桶仍可查询；被拒绝的 Call 尝试通过独立错误返回路径返回 UNAVAILABLE，其他业务消息丢弃且不重放。普通 RPC 和协议控制不受影响。[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 中的 `retainedRouterBucketDoesNotAdmitDataUntilSynchronizationRecovers` 固定真实恢复窗口，验证这些边界及重新同步后的成功转发。

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

RouterHandler.onRoutedRequest 接收精确 sourceNodeId/sourceNodeEpoch 和借用的内层 RpcRequest。reply 接收该 RoutedRequest 及匹配的非零 RpcResponse ID，指向原来源注册，保留 error/Metadata/body。延迟回复可保存不可变身份/ID；延迟 body 须 retain/copy 并对应 release。普通直连流量仍经原 RpcHandler。

原 Node 拥有一个外层 RPC pending 调用。中间 Router 用 Notify envelope 转发，并在内层保留原 ID，不创建业务 pending Map。最终 Router 经原 Router Peer 转发原生 RpcResponse；RPC 删除 pending，私有适配器解包，携原 command/callback 调用 RouterHandler.onResponse。应用解码业务响应并调用 RpcCallback。远端错误进入 onResponse；本地超时、移除、关闭和立即失败进入 onFail。第 3 节的控制回调已经携带错误码，按直接完成契约执行。

动态缺失返回 ROUTE_NOT_FOUND；物理缺失返回 RPC PEER_NOT_FOUND；下一跳不可用返回 RPC UNAVAILABLE。业务 Handler RuntimeException 尝试返回路由 HANDLER_ERROR。返回链路失败仍可超时。已匹配原生响应的内层数据非法时，携原 command/callback 以 onFail(PROTOCOL_ERROR) 完成。合法 onResponse 后的应用异常只记录，不二次完成。

Router 间 DATA_ERROR 经 rpc.notify 独立发送，不进入同步 FIFO，不创建中间 pending，也不等待 VERIFY/快照/增量 ACK。私有编码 body 无论成功、拒绝或发送异常均释放。发送异常只记录，不作废同步或重试错误；准入失败时仍由原调用的既有超时结束。接收方按来源 Router epoch 和原目标的精确 Node/epoch 校验，随后投递原生错误响应。`routedErrorDoesNotWaitForSynchronizationAcknowledgement` 暂扣 VERIFY ACK 后断开目标服务，验证 PEER_NOT_FOUND 先于业务超时返回、同步仍就绪且回调不重复。Wire Profile 定义传输表示及旧 Call 形式的接收兼容性。

| 边界 | 所有权 |
| --- | --- |
| 编码前空/非法参数或注册不可用 | 调用方保留原 body |
| 通过校验的路由发送/回复 | 消费原 body 一个引用，编码/传输失败亦然 |
| 入站路由请求/响应 | 回调期间借用，不能直接 release |
| 转发/广播副本 | 每次编码 retain 一个内层 body 引用，由编码消费 |
| 排队 Router 同步控制 | 路由拥有，发送、作废或关闭时释放 |

路由 envelope 的 Request.requestId 为零，经现有 rpc.notify 发送；原业务 ID 保留在内层。原生返回响应经 rpc.reply(target,0,0,response) 发送，保留 ID，优先 Slot 0，失败按普通无亲和规则回退。中间 Router 不创建业务 PendingCall，也不需要新的 RPC 发送原语。请求用内层 routeKey 亲和；业务身份/Metadata 不变。ACCEPTED 只表示首跳本地准入，不证明端到端执行；不增加业务重试或远端取消。

## 5. 同步、线程和边界

RouterEngine 用单 monitor 串行化状态、快照/增量入队及转发；完整捕获快照后才允许后续本地变化。入站暂存直到条数/owner 和 END 校验通过才可见。Profile v2 带快照 revision、连续增量 revision 及 VERIFY。版本缺口、远端 Router epoch 变化、未完成同步或控制失败只重置路由同步，保留最后提交的桶，再交换新快照。即使 tick 漏掉短暂断线，版本校验仍会发现遗漏增量并收敛；不提供重放日志。

每个路由角色拥有一个由 start 启动的 daemon ScheduledExecutorService，仅一个每 100ms 的 fixed-delay 维护任务。转发角色维护已声明 Peer，空闲时距上次至少一秒发 VERIFY；服务角色仅维护选中 Router。调度延迟和在途 RPC 超时可以延长恢复；一秒不是就绪租约或恢复期限。控制失败在后续 tick 重试，避免同步失败递归。VERIFY 共享现有 FIFO，不与在途控制重叠；持续控制可延后空闲校验，但 NOT_REGISTERED/版本缺口响应直接触发恢复。不使用 Node 传输或拓扑锁内回调。

默认值：每个权威桶 100,000 个 Node、1,000,000 个绑定；每块 256 条；每个 Router Peer 8,192 个排队控制加一个在途；服务 8,192 个排队控制加一个在途；本地移除身份限制 100,000 对。内层帧最多 4 MiB 减 80 字节，也受 Node 外层帧上限约束。这些组件 V1 默认值没有配置 API。

服务发送在 monitor 内捕获已校验身份，编码和发送在 monitor 外执行。RPC 立即失败时应用 Handler 不持有该 monitor，允许的 Peer 管理不会再形成原拓扑/服务锁循环。路由 close 前已准入发送仍可随后执行，沿用捕获身份；close 不取消业务执行。Router 同步回调是内部回调，转发 monitor 下不分派应用回调。

应用在管理上下文调用 routing.close，再调用 rpc.close。路由 close 幂等，限制新状态修改，清除表/期望键/缓冲，停止维护并将剩余服务控制完成一次。已捕获完成结果或已分派的应用回调仍可执行完；路由 close 不是 RPC 的 worker/资源屏障。即使接收 Handler 在路由 close 前已被 RPC 准入，也不能在 close 后重新创建 Router 状态。RPC close 仍是网络/pending 完成屏障。启动失败也必须关闭路由角色。路由 close 不宣告远端服务下线，该事件归发现所有。

## 6. 异常、兼容性与验证

必要参数为 null 抛 NullPointerException；非法 ID/服务/epoch/超时/回复 ID 抛 IllegalArgumentException；缺少注册、错误生命周期、重复 start 或 Router 多 Slot 冲突抛 IllegalStateException；服务队列/移除限制满时抛 RejectedExecutionException。地址/Slot 数归 RPC 校验。Router Profile v2 修改快照/增量字段，增加 VERIFY 和带 epoch 的注册 ACK；拒绝 v1，不支持混合版本回退。RPC Wire framing、type、握手不变。路由参与方需一起升级，普通 RPC 参与方保持兼容。移除的工厂/钩子不提供兼容别名。

源码：[GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java)、[ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java)、[RouterEngine](../../game-router/src/main/java/cn/managame/router/node/RouterEngine.java)。验证：[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)、[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java)、[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)、[RouterTableTest](../../game-router/src/test/java/cn/managame/router/node/RouterTableTest.java)。[RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 展示完整装配及资源关闭顺序。

使用 mvn -pl game-router -am test 和根 mvn clean verify 验证集成。本地 TCP 测试覆盖多连接、健康传输 ACK 丢失、注册前后精确移除、回调重入、关闭后延迟 Handler、FIFO 容量、同步版本缺口、路由身份及保留普通调用；不证明生产容量、持续过载或跨语言互通。

<a id="7-known-limits"></a>

## 7. 已知限制

本节只列当前仍成立的边界。2026-10-07 审查的过程、证据、探针结果和后续方案保留在[审查记录](../reviews/2026-10-07-router-rpc-review.zh-CN.md)中，该记录不是规范条款。

- **调用准入**：业务 Call 的 pending 没有有限准入，依赖 RPC R-SEND-04（未实现）。
- **代际与发现**：`locate` 选择数值最大的 nodeEpoch。精确退役、Router 重启后的事件重放、成员移除的代际过滤由发现集成负责，示例未提供。
- **迁移隔离**：bind 成功不证明旧 owner 已停止执行；业务所有权代际与条件写归应用（RT-BIND-03）。
- **同步容量**：每个 Peer 只有一个确认控制在途，恢复耗时与 RTT 成正比；持续变更超过排出速率会溢出并重做快照。
- **资源预算**：成员数、已提交/暂存桶和编码队列没有进程总量上限；转发 monitor 覆盖编码/发送及快照/冲突扫描。
- **Spring 组合**：托管 RPC 通过 `GameRpcConfigurer.decorate/attach/detach` 组合 GameRouter/ServiceRouting（见 [Spring Java 规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)）。路由请求进入 Runtime 及精确路由回复身份（来源 nodeEpoch）仍由应用在 RouterHandler 中实现。
- 生产吞吐、内存及持续分区恢复均未验证。
