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

必要参数为 null 抛 NullPointerException；非法 ID/服务/epoch/超时/回复 ID 抛 IllegalArgumentException；缺少注册、错误生命周期、重复 start 或 Router 多 Slot 冲突抛 IllegalStateException；服务队列/移除限制满时抛 RejectedExecutionException。地址/Slot 数归 RPC 校验。Router Profile v2 修改快照/增量字段，增加 VERIFY 和带 epoch 的注册 ACK；拒绝 v1，不支持混合版本回退。RPC Wire v1 framing、type、握手不变。路由参与方需一起升级，普通 RPC 参与方保持兼容。移除的工厂/钩子不提供兼容别名。

源码：[GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java)、[ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java)、[RouterEngine](../../game-router/src/main/java/cn/managame/router/node/RouterEngine.java)。验证：[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)、[RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java)、[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)、[RouterTableTest](../../game-router/src/test/java/cn/managame/router/node/RouterTableTest.java)。[RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 展示完整装配及资源关闭顺序。

使用 mvn -pl game-router -am test 和根 mvn clean verify 验证集成。本地 TCP 测试覆盖多连接、健康传输 ACK 丢失、注册前后精确移除、回调重入、关闭后延迟 Handler、FIFO 容量、同步版本缺口、路由身份及保留普通调用；不证明生产容量、持续过载或跨语言互通。

<a id="7-尚未修复的审查结论2026-10-07"></a>

## 7. 审查修复与剩余边界（2026-10-07）

RPC 的 Handler 修改、就绪和清理钩子已移除，由普通 Handler 装配、组件拥有的协议维护和应用显式关闭替代。已复现的回调锁循环、多 Slot Router 重启、缺失注册 ACK 恢复、已移除精确代际复活、关闭后晚到 Handler 修改状态均有代码调整及永久回归。服务 FIFO 已限制准入；Node 绑定移除使用 owner 索引，不再扫描全部绑定。

RPC 暂缓的有限 pending 准入仍未实现。转发 monitor 仍覆盖编码/发送、快照/冲突扫描；resolveKey 可能重复扫描 Router 来源桶。生产内存/吞吐及持续分区/溢出恢复未验证。移除限制有容量上限，且不跨 Router epoch 持久化，需要发现重放和容量规划。进一步索引、流式快照生成或可配置预算需要实测需求；不引入新的通用扩展框架。

同日后续发现的偏差已有实现修复及 [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) 永久回归。入站 DATA 使用与出站转发相同的就绪限制，恢复时保留已提交查询但拒绝业务投递。非瞬时 VERIFY 失败停止注册恢复、暴露错误，并保留期望绑定供显式重试；传输重连不能复活已停止的选择。测试覆盖 Notify/广播/Call/响应拒绝、普通 RPC 连续可用、重新同步后的投递、非法/非瞬时校验 ACK、排队回调完成、显式重试和瞬时错误/NOT_REGISTERED 恢复。这些调整落实 RT-SYNC-03 和 RT-LIFE-02，不替换其契约。临时评估探针已由这些维护中的验证入口替代。

修复验证：`mvn -pl game-router -am test` 通过，Router 共 37 个测试，两轮审查共新增十个回归场景。最新两项回归在修复前的错误返回、就绪实现上均失败。根 `mvn clean verify` 中 Router 和其他框架模块通过，但 game-demo 测试编译因现有 DemoRpcBootstrapTest 导入缺失的 GameRpcConfig 而失败。已确认改动前 HEAD 同样具有该过期导入且缺少对应源码。前一轮审查中，RouterEchoExample.run 使用清理重建后的类独立编译/执行，返回 `hello game-router`。这不代表根目录完整验证成功；无关的示例启动测试编译问题仍未解决。

容量还需要恢复延迟预算。在默认桶上限下，快照需要 391 个 Node 分块、3,907 个绑定分块及 HELLO/BEGIN/END，共 4,301 个确认控制，每个 Peer 只有一个在途。因此理想的串行 ACK 延迟项约为 `4,301 × RTT`（RTT 为 10 ms 时约 43 秒），尚未包含编码、排队和并发变化。服务恢复为每个期望键发送一次确认 bind，对应项为 `keyCount × RTT`。这些是从协议推导的估算，不是实测吞吐或恢复保证。提高容量或改变已确认的顺序契约前，应测量快照完成、恢复时间、monitor 持有时间及 pending 内存。

### 架构与职责评估

组件归属仍一致：RPC 拥有传输及调用完成；Router 拥有注册、权威桶、同步和转发；发现拥有实例退役；应用/Runtime 拥有业务放置和执行。以下剩余架构缺口不构成反转依赖、创建第二个 RpcNode、Router 业务 pending Map 或通用扩展框架的理由。

- **代际与发现集成需要运维契约。** RouterEngine.locate 选择无符号最大的 nodeEpoch，但 ServiceRouting.register 仅要求不同的非零 epoch，不要求递增。忽略目录 `game-router/target/router-architecture-review` 下的临时真实 TCP 探针，在 A 的 Node 1/epoch 10 断线后，令替代 Node 1/epoch 1 在 B 注册、绑定。两次操作成功；Router 同步后仍可见 epoch 10，路由调用返回 PEER_NOT_FOUND。精确执行 `A.removeNode(1,10)` 后 epoch 1 可见，新调用成功。这是现有确定性选择边界，不是契约违反，也不证明自动故障切换。部署必须提供精确退役/重放，并明确是否有序分配 epoch；数值冲突选择不能提供发现权威。同一 Router 上的新 epoch 也不能在退役/注销前替换其保留的冲突本地注册。Router 成员移除只使用 ID（`unregisterRouter(int)`），因此发现集成必须按自己维护的当前代际过滤迟到的 Router 下线事件，再移除成员。RouterEchoExample 没有提供这些集成机制。
- **Spring 托管 RPC 与路由 Runtime 装配尚未组合。** [RpcConfiguration](../../game-spring/src/main/java/cn/managame/spring/rpc/RpcConfiguration.java) 先运行 GameRpcConfigurer，[GameRpc](../../game-spring/src/main/java/cn/managame/spring/rpc/GameRpc.java) 随后用私有直连 RPC Handler 覆盖 Builder Handler。该 configurer 无法把 GameRouter/ServiceRouting 包在托管应用 Handler 外，RpcNode 也没有 build 后修改 Handler 的入口。[RpcHandlerContext](../../game-runtime/src/main/java/cn/managame/runtime/context/RpcHandlerContext.java) 还缺少来源附着 epoch；GameRpc.reply 使用原生直连回复，ServiceRouting.reply 则需要精确路由来源身份。可选 game-spring/应用适配需要 build 前组合、路由回复身份、协议解码、回调 Route 捕获/drain 和生命周期顺序。复用现有 RPC/Runtime 机制，不把关联复制到 Router，也不创建另一个 Node。该适配未实现，普通 Handler 组合及托管直连 RPC 测试不能证明它可用。
- **路由放置不等于业务执行隔离。** RT-BIND-03 已将独占放置归给应用。bind 成功、确定性胜者选择或进程内 Runtime Executor 都不能证明旧 owner 已停止修改玩家/房间状态。迁移集成需要由执行/存储拥有方检查的应用业务所有权代际，并在需要时明确排空/状态迁移顺序。来源 nodeEpoch 只限制 Node 附着关系，不是每个绑定的业务所有权代际。这不属于 Router，当前路由示例也没有提供端到端迁移/隔离验证。
- **控制/数据耦合及完整复制限制部署形态。** Router 的单 Slot 同时承载同步和业务 envelope，同一转发 monitor 同时处理状态与转发。即使传输和已提交桶仍可用，同步作废也会通过就绪条件阻止业务转发。独立 DATA_ERROR 准入消除了 ACK 队列依赖，但不提供网络带宽隔离。R 个 Router 的全连接有 R×(R−1)/2 条成对连接，每个本地变更最多产生 R−1 次控制发送，每个 Router 保存全局权威状态副本。改变拓扑前应测量小规模集群预算。有限同步流水线及缩小 monitor 是优先选项；若实测规模需要分离有序控制/数据传输路径或分片权威，则需要明确协议/一致性设计。本次评估未实现这些能力。

本次评估不改变运行行为，也不增加新的 MUST 要求。代际边界由临时 TCP 探针验证；Spring/Runtime 组合结论依据当前装配、Context 和回复源码核对。其他架构限制由契约推导，不是生产测量。

### 重新审视后的建议与 Java 实施顺序

以下建议细化前述审查，仍待实施。记录它们不改变 Java API、错误映射或 Router Profile 字节。身份、迁移及控制面的语言无关推理归 Router 标准第 7 节。

**epoch 的必要性经过验证，而非预设。** 临时 TCP 架构探针还重启了来源 Node 1：旧 epoch 10、新 epoch 1 的业务 requestId 都为 2。旧附着的延迟回复被丢弃，新附着的回复成功完成调用。RpcNode.requestIds 属于实例且从 1 开始；pending 完成及物理连接身份不能提供跨重启的路由附着身份。ID 仍可复用时，保留 nodeEpoch/routerEpoch 的相等性保护。数值最大不能证明更新，建议从未来身份解析器中去掉。精确身份定位及不明确 ID 拒绝，需要永久回归及一致冲突语义后才改变行为。Node ID 永久唯一是另一种可能，但会改变部署分配/耗尽规则，不能在每次路由选择时直接重建共享 RPC Node，影响普通 RPC 生命周期。

**在应用接入实现迁移。** 使用应用自有的逐 key 接纳控制及操作/后续回调 ticket，在现有 Runtime Route 上改变业务阶段。异步完成保留 ticket 直到受保护工作结束，被阻止/拒绝的分派也须释放 ticket。冻结只作用于迁移对象。GameRuntime.shutdown/awaitTermination 覆盖整个 Runtime，不是可恢复的逐 key 控制，也不能在该 Route 上调用/等待。管理工作流通过回调等待逐 key 完成。后续回调及排队持久化携带捕获的所有权版本。冻结的 A 可在交接前完成已接纳工作，但不能接纳新工作或凭晚到回调重新取得所有权。

正常迁移顺序为持久准备、确认旧 unbind、条件提交 owner，再激活/bind B。成功 unbind 后检查 ServiceRouting.bindings：期望 key 须已移除，否则自动恢复可能重新声明它。超时不能确认 unbind，提交交接前先通过已有服务控制状态协调。权威记录保留 migrationId/检查点/版本，便于读取事务未知结果并幂等继续。提交前 B 仅准备，提交后发布失败时 A 保持冻结，B 按同一 migrationId 重试。保留的远端条目可暂时拒绝 B bind，这是可用性缺口，不是覆盖 RT-BIND-01 的许可。重要调用重试使用应用 operationId 和原子记录的结果。

所有权条件和受保护状态修改须在同一事务/条件写中，首版可使用应用原生数据库访问。[Data 标准](OGBS-Data-1.0.zh-CN.md) 将 Repository.update 定义为异步接纳，不是逐对象持久检查点或带所有权校验的事务；Runtime 排空也不证明持久化或外部送达。逐对象保存确认及 token 条件写是实施前提，不是当前 game-data 能力。排队旧版本写入不能在 flush 时借用新 owner 的 token，也不能用 GameData.close 保存单个迁移玩家。先支持正常迁移和整个代际的精确发现退役。若要求保留 Node 存活而强制退役单个 key，还需要绑定所有权版本/权威集成；仅新增 removeBinding(key,expectedNodeEpoch) 管理方法会遗漏 A→B→A 的 ABA 情况。

**重构同步而不扩大公开角色。** 权威/索引状态、Peer 同步和转发可用包私有辅助类表达，保留 GameRouter/ServiceRouting 公开边界。短状态 monitor 内捕获路由决策，或紧凑一致快照视图及 revision；锁外编码/notify/call，每条结果路径都释放私有 buffer/retain 引用。若采用数组复制，快照捕获仍是 O(条目数)，应限制并测量其临界区，不能声称流式编码消除了它。仅捕获成本超过实测预算时才考虑版本化/结构共享视图。有效索引保存全部候选来源，在明确契约调整前保留现有确定性规则。

每 Peer 发送器串行化写准入，只有一个排定的 drain 标记，使用有限 ACK 窗口，同时计入 Peer/进程字节预算。HELLO/BEGIN ACK 是阶段屏障；Node/绑定分块及后续连续增量有序流水发送，END 在此前快照成功后发送。窗口失败先以本地世代/Peer 身份限制全部回调再作废。按需生成分块；捕获视图、暂存、逻辑增量、编码及在途 buffer 都计入预算。本地修改前拒绝预算，与现有本地成功后远端溢出的语义不同，新准入限制须明确同步双层契约。限制每轮 drain，并为每 Peer 设置恢复退避，避免 ACK 回调占用 EventLoop 或持续重建快照。窗口按实测带宽/RTT 及总量限制选择，不给出吞吐默认保证。

严格的控制/业务传输分离属于后续 RPC 集成变更。RpcPeer.startSlot 与 RpcNode.send 将 routeKey 用作起始 Slot，随后尝试其他 Slot，不能靠 routeKey 约定隔离。RPC 需要严格 Slot 发送/准入及 Slot 可用性/绑定代际依据，连接、恢复、body 消费及 pending 完成仍归 RPC。在修改当前单 Slot 校验前，Router 双 Slot 职责、同步尝试隔离及通道丢失时的就绪行为须随协调的 Router Profile 版本说明。该方案不创建第二个 Node，也不允许按过期桶转发。先保留全连接，通过可配置成员数、总字节及同时快照数量明确部署边界。

后续必须验证：复用 ID 后的延迟回复/移除；A 冻结或交接后的回调/写入；未知提交/unbind 结果；丢 ACK 后期望绑定恢复；旧权威阻止 B bind；ticket 拒绝/释放及操作去重；窗口 ACK 失败/乱序、旧回调及暂存不可见；持续负载下字节有界；关闭/引用所有权；严格 Slot 失败不跨职责 fallback。现有 Router 37 项测试及临时探针不证明这些建议功能已经可用。

### 继续评估容量与失败路径

剩余同步与进程容量风险建议按以下顺序评估；这是后续方案，不是生效要求或已实现功能：

1. 测量每个 Peer 的控制到达/排出速率、排队及在途字节数、ACK 延迟、快照/恢复耗时、按原因分类的重置次数、monitor 持有时间，以及已提交/暂存/索引的总内存。比较持续变更速率与实测排出能力，包含恢复流量。
2. 评估现有单 Slot 上的有限在途状态控制窗口。保持有序应用及连续 revision，条数和字节限制同时覆盖排队与在途工作。不能仅凭 END ACK 开启就绪，必须此前全部快照控制成功。窗口失败或被替代时，应先限制全部晚到回调，再启动新同步。需要同步调整本 Java 规范及 Wire Profile 当前的单在途规则，并补并发/失败回归；根据测量选择窗口，不预设未经验证的固定默认值。从已捕获的不可变视图按需生成快照分块，预留增量容量，保持快照先于增量。批量增量或服务绑定恢复可作为后续选项，需要明确 Wire 操作、版本/兼容性审查与测试；不能直接跳过携带 revision 的变更来合并。若到达速率仍超过排出能力，需要应用速率预算或明确准入策略；只增大 FIFO 仅延后溢出。
3. 缩小转发 monitor 范围：锁内捕获不可变路由决策/身份及所需 payload retain，锁外编码、发送，每条结果路径对应 release。保留关闭/准入边界、控制入队顺序及快照一致性，不能锁外遍历仍可变的表。有效 Node 定位索引和获胜绑定索引需要保存所有候选桶来源，确保移除获胜项后按现有确定性冲突规则显露正确的竞争项。已有 owner 到 key 的移除索引解决的是另一类扫描。
4. 定义进程总量及每 Peer 的字节预算，覆盖已提交状态、暂存、排队/在途控制和 fan-out，同时限制 Router 成员数及并发恢复数。配置化预算及拒绝原本会接受的工作会改变公开默认值/准入行为，需要明确设计调整，并同步双层规范与边界测试。发现移除依据需要权威的代际退役/检查点规则，或配合发现重放的运维滚动方案；超时/LRU 淘汰不能安全解决生命周期上限。

后续本地评估包含以下已解决偏差与剩余容量风险。忽略目录 `game-router/target/router-followup-review` 下的临时 TCP 探针提供初始本地证据，不是生产容量测试。错误返回阻塞与 Slot 重配偏差已由代码修复及永久回归替代，其余事项未实施。

- **Router Peer 重配后可能保留非法 READY 状态（已解决）。** 原实现在重配成双 Slot 后仍报告 READY；现在按当前 Slot 数限制就绪、单播和广播，维护作废非法同步并保留查询，恢复单 Slot 后重新交换快照。第 2 节及永久回归 `routerSlotReconfigurationInvalidatesReadinessAndRequiresNewSnapshot` 定义、验证该边界。
- **业务 Call pending 没有有限准入（已确认的现有 RPC 能力缺口）。** 真实本地 Router 将 10,000 个 Call 投递到只消费请求、不回复的服务。来源使用 60 秒调用超时；10,000 个调用全部留在其 RPC Peer pending Map，未发生准入失败。有界服务控制 FIFO 不限制这些业务调用，可写 TCP 也不表示响应能力。这确认的是准入机制缺失，不是内存溢出阈值或受支持吞吐。Node 级 Call 准入归 RPC 暂缓的 R-SEND-04；Router 应复用其完成语义，不另设业务 pending Map。参见 [RPC Java 规范](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)。 超时会移除 pending 并报告 TIMEOUT，晚到响应不会再次完成；这不表示永久泄漏。风险是超时前的并发积压，约为到达速率乘平均等待时间。
- **路由错误等待同步 ACK（已解决）。** 初始探针暂扣 VERIFY ACK，在目标服务断开后观察到 DATA_ERROR 的 PEER_NOT_FOUND 排在同步队列中，200 ms Call 超时。错误返回现经 Notify 独立发送；Wire Profile 已记录其表示与旧 Call 的接收兼容性。永久回归 `routedErrorDoesNotWaitForSynchronizationAcknowledgement` 覆盖修复，不保证不可用返回链路的交付。
- **持续变更可能超过串行同步能力（协议推导的容量风险）。** 只有一个确认控制在途时，理想排出速率最多约为每秒 `1 / RTT` 个控制。多个服务可以持续产生注册、bind 和 unbind；它们本地成功不等待远端排出。总到达速率超过排出速率时，8,192 个控制的队列最终溢出，作废流并重新启动完整快照。在最大快照规模下，如果捕获期间没有 ACK 排出，留给后续变更的排队空间约为 3,892 个。持续过载可能反复破坏快照进度。现有收敛以来源状态停止变化为条件，持续负载恢复预算未验证。服务恢复逐键确认 bind，也会在期望集合恢复完成前保持出站业务路由不可用。
- **每桶限制不构成进程资源预算（结构性、未实测的生产风险）。** Router 成员配置没有总量上限；每个 Peer 可以保留已提交桶、暂存另一桶并拥有编码控制队列。表还维护 owner 到 key 的索引。内存随来源桶数、并发恢复和 fan-out 增长，而不只受本地百万绑定限制。快照编码、冲突扫描和业务编码/fan-out 共用一个 monitor；这里阻塞会延迟共享 EventLoop 上的其他 RPC 连接。多个桶声称同一 key 时，`resolveKey` 可能产生 Router 桶数的平方级工作；服务整体查询扫描全部注册并重复定位 owner。选择索引或预算前，应测量字节数、monitor 持有时间和尾延迟。独立的十万对移除依据还是生命周期限制，因此发现事件变化也需要运维预算；用满后，新增移除会在清理其存活路由前被拒绝，这是第 2 节明确的设计边界。
