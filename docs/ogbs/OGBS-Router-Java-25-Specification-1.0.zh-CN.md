# OGBS Router Java 25 开发规范 1.0

[English](OGBS-Router-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)**

标准：[Router 规范](OGBS-Router-1.0.zh-CN.md)。线协议：[Router Profile v5](../rpc-wire.zh-CN.md#router-profile-v5)。状态：已实现，具有本地 TCP 契约测试；生产容量与跨语言互通未验证。

## 1. 模块、所有权与公开边界

`cn.managame:game-router:1.0.0-SNAPSHOT` 需要 JDK 25 与 game-rpc，并传递依赖 Network/Core/Netty。RPC 从不依赖 Router。一个进程只有一个由应用持有的 RpcNode。两个角色都是实现既有 RpcHandler 与 AutoCloseable 的 final 具体类：构造角色，传给 RpcNodeBuilder.handler，在 `rpc.start()` 之前调用 `role.start(rpc)`，并在关闭 Node 之前关闭角色。start 只关联一次 Node，不启动任何网络。null 参数抛 NullPointerException；重复 start 或 close 之后 start 抛 IllegalStateException。

| 公开类型 | 职责 |
| --- | --- |
| node.GameRouter | Router 角色：网格声明、附着、位置上报与转发 |
| node.ServiceRouting | 服务角色：Router 声明、宣告、发送、回复与投递 |
| call.RouterHandler、call.RoutedRequest | 借用式路由投递与路由调用完成 |

`new GameRouter(RpcHandler directHandler)` 创建 Router；`new ServiceRouting(int serviceId, long nodeEpoch, RouterHandler handler, RpcHandler directHandler)` 创建服务。direct handler 接收所有非 Router Profile 的请求，以及所有非路由调用的响应与失败。同时实现两个接口的 handler 可以填在服务角色的两个位置上。RouterWire 是包私有编解码器。没有其他接口/实现对、Handler 变更 API、连接监听器或 RPC 清理钩子。**全部 RPC Peer 归应用所有**，包括网格连接与服务到其 Router 的那一条连接：两个角色都从不调用 addPeer 或 removePeer。遵循[组件职责边界](OGBS-Router-1.0.zh-CN.md#1-职责与组合)。

## 2. Router 角色

```java
GameRouter router = new GameRouter(directHandler);
RpcNode rpc = RpcNode.builder().nodeId(102).bindAddress(address).handler(router)
        .writeBufferWaterMark(512 * 1024, 1024 * 1024).build();
router.start(rpc);
rpc.start();
router.addRouter(101);                       // 声明网格中的其他 Router
rpc.addPeer(101, addressOf101, 2);           // 本 Node ID 更大，由它开这一对的连接
// 应用管理上下文，通常在 finally 中：
router.close();
rpc.close();
```

`addRouter(id)` 声明网格中的另一个 Router，`removeRouter(id)` 停止使用它并忘掉它上报的一切；两者都要求已 start 且角色未关闭（否则 IllegalStateException），并拒绝 0 与本 Node 自身 ID（IllegalArgumentException）。只有已声明的 Router 才可以在这里上报位置，也只有它的上报被信任：来自任何其他 Peer 的 Locations 或 Unlocate 被拒绝，承载它的 Call 以 HANDLER_ERROR 失败。应用为每一对开一条连接，由 ID 较大的一方发起，于是较小的一方从入站握手学到对端。

Router 保存两张表，规模都由 Node 数量决定：

- **附着表**：每个在已连接 Peer 上宣告过的服务 Node 一条，保存该 Node 的 epoch 与 service，另有一个按 service 的索引，使广播与 `serviceNodes` 只访问该 service 的 Node。一次宣告（ATTACH，或该 Node 发出的任何路由消息）新增或替换条目；相同 epoch 的 DETACH 删除它。RPC 不报告断连，因此已关闭连接的条目在向它发送失败或容量耗尽时才被丢弃。
- **位置表**：另一个 Router 上报的每个 Node 一条，保存该 Node 的 epoch、service 与上报方 Router。本地附着永远胜出：某个 Node 在本地附着会删掉它的位置条目，而针对本地已附着 Node 的上报被忽略。位置仅在上报方 Router 的连接存续期间可用，因此一条断开的网格链路会让其背后的 Node 报 PEER_NOT_FOUND，而不是静默消失。来自某个 Router 的 Data 帧同时把它自己的发送方定位在那里（RT-STATE-03），这正是让刚建好的网格上第一次调用也能得到响应的原因。

`isAttached(nodeId)` 报告 Peer 仍连接的附着；`isReachable(nodeId)` 报告附着或可用位置；`serviceNodes(serviceId)` 返回**附着在本地**的该 service 的 Node 的不可变快照。`stats()` 返回 `GameRouter.Stats(forwarded, passed, targetNotFound, sendRefused, responsesDropped)`，自构造起单调递增：投递给本地已附着 Node 的消息（每份广播副本与每条响应各计一次）、交给另一个 Router 的消息、没有可用位置的消息、RPC 拒收的下一跳消息，以及调用方已离开或不可达的响应。close 幂等，停止转发（此后的 Router 请求以 HANDLER_ERROR 失败），在连接仍可用时向网格上报自己这些附着的删除，并忘掉两张表。

转发按一个固定顺序解析目标：本地已附着、某个 Router 上报过、失败。来自 Router 的帧只会被本地投递，绝不再次转出，这使网格无需跳数即无环。只有调用方自己的 Router 会把它的入站调用 ID 盖进转发出去的请求；第二跳沿用它收到的 ID。最多保存 100,000 条附着：满时先丢弃已断连 Peer 的条目；若仍然满，则拒绝该宣告并记录日志（承载它的 Call 以 HANDLER_ERROR 失败）。

## 3. 服务角色

```java
ServiceRouting routing = new ServiceRouting(MATCH, nodeEpoch, routedHandler, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(1).bindAddress(address).handler(routing).build();
routing.start(rpc);
rpc.start();
for (Router r : candidates) routing.addRouter(r.id());   // 本 Node 可以使用的全部 Router
rpc.addPeer(chosen.id(), chosen.address(), 2);           // 应用只连接其中一个
// ... routing.callNode(targetNodeId, request, callback);
// 应用关闭：routing.close(); rpc.close()。
```

serviceId 为正，nodeEpoch 非零；两者对该对象固定，重启后的进程使用新的 nodeEpoch。`addRouter(id)` 声明本 Node 可以使用的一个 Router，`removeRouter(id)` 从它撤销本 Node；两者都不创建、删除或重配 RPC Peer。两者都要求已 start 且角色未关闭，否则 IllegalStateException；非法 ID 抛 IllegalArgumentException。

本角色只附着**一个** Router：已连接的已声明 Router 中 ID 最小的那个。因此它是哪一个取决于应用开了哪条 Peer；迁移一个服务就是关掉一条 Peer 再开另一条，路由在一个检查周期内跟上。切换时先从原 Router 撤销，因此该 Node 绝不会同时被宣告在两处。`removeRouter` 会立即停止发往该 Router，而不是等到下一个检查周期。

start 在一个名为 `service-routing` 的守护线程上开始宣告。它每 100 ms 重新计算当前 Router，并在该连接对它而言是新的、或上一次被接纳的宣告已过一秒时，以 Notify 发送 ATTACH（epoch、service）；被拒绝的 ATTACH 在下一次检查重试。没有确认，也没有需要恢复的东西：重启后的 Router 会在一秒内学到本 Node。每个控制操作都用同一个 routeKey，因此 ATTACH 与 DETACH 走同一个 Slot 并保持顺序；removeRouter 在宣告线程上发送 DETACH，因此已在进行中的宣告不会跟在它之后。来自未声明 Router 的投递，或 close 之后到达的投递，会被拒绝并回一条 DETACH，这也修复了盖过撤销的宣告。close 停止该线程、等待进行中的检查完成、向当前 Router 发送 DETACH，并且幂等；此后除 close 以外的每个操作都抛 IllegalStateException。

## 4. 发送、完成与 body 所有权

ServiceRouting 提供 `notifyNode(nodeId, request)`、`broadcast(serviceId, request)`、`callNode(nodeId, request, callback)`、`callNode(nodeId, request, timeoutMillis, callback)` 与 `reply(routedRequest, response)`。出站 requestId 必须为零；nodeId 非零；serviceId 为正；timeoutMillis 为正且在纳秒范围内；否则在接管 body 之前抛 IllegalArgumentException。默认超时取 RpcNode 的 callTimeout（通常 5 秒）。发送在调用方线程上执行，只进入 RPC。

每次发送都发往本 Node 附着的那一个 Router。没有 Router 选择、没有哈希、也没有预热，因此同一键发往同一目标保持一条路径及其 RPC Slot 亲和性，直到该连接或目标位置发生变化。没有已声明 Router 时发送抛 IllegalStateException；一个都没连接时使用第一个已声明 Router，由 RPC 报告 UNAVAILABLE（Notify 状态或 onFail）。Notify 的 ACCEPTED 只表示被那条 Router 连接接纳，不代表已投递，也不说明第二跳的情况。

Router 仅在它所能触及的网格中看不到目标任何位置时才以 PEER_NOT_FOUND 回答 Call，因此服务**上报该错误而不是重发**：只有一条连接就没有第二条路，重发等于向同一个 Router 再问一遍同样的问题。此情形下的 Notify 被丢弃并计数。本地失败的调用（onFail）可能已经投递，同样不重发。

RouterHandler.onRoutedRequest 收到一个 RoutedRequest：源 nodeId 与 nodeEpoch、借用的内层 RpcRequest，以及 routerId——投递它的那个 Router，也就是本 Node 附着的 Router，不一定是调用方的那个。投递只接受来自已声明 Router、且目标为本 Node ID 与 epoch 的消息；其余在到达 handler 之前即被拒绝。reply 经 routerId 把响应发回，由它像任何其他消息一样按调用方 nodeId 路由，寻址到确切的调用化身；它要求请求 ID 非零且与响应 ID 相同。RoutedRequest 不可变且要求 routerId 非零；保存它以便稍后回复，而在回调之后仍要使用的 body 需 retain/copy 并在之后释放。onRoutedRequest 抛出的 RuntimeException 会在为 Call 尝试一次 HANDLER_ERROR 回复之后重新抛给 RPC。

路由调用的完成通过 RouterHandler 使用原始 callback。`onResponse(targetNodeId, command, response, callback)` 收到目标解包后的响应及其自身错误与 Metadata（目标 handler 抛异常时为 HANDLER_ERROR），或者某个 Router 返回的正数错误：没有可用位置时 PEER_NOT_FOUND，Router 无法发往目标或下一个 Router 时 UNAVAILABLE，它判定消息非法时 HANDLER_ERROR。`onFail(targetNodeId, command, error, callback)` 收到本地失败：TIMEOUT、连接丢失的 UNAVAILABLE、NODE_CLOSED，以及响应畸形或并非来自该化身目标时的 PROTOCOL_ERROR。普通直连流量走 direct handler。

| 边界 | 所有权 |
| --- | --- |
| 参数非法或角色不可用，编码之前 | 调用方保留 body |
| 已接纳的发送、回复或调用 | 消耗一个 body 引用，编码与传输失败也算；成功返回后 RPC 仍引用大 body |
| 路由投递与路由响应 | 回调期间借用；handler 绝不释放 |
| Router 中继 | 内层帧不解码：每份副本获得新的头部，并在调用方的 Router 处写入调用方的调用 ID；内层帧不超过 2 KiB 时复制，更大时从收到的帧引用并由 RPC 释放 |
| 向多个 Router 上报位置 | 每次发送各取一个 retained duplicate，原始引用只释放一次 |

## 5. 线程与边界

数据路径不加锁：转发只读那两张无锁表。附着表的变更（新宣告或 epoch/service 改变、DETACH、发送失败后的删除、容量清理、close）在一把短锁内同时更新表与按 service 的索引，位置表的变更使用同一把锁；重复的相同宣告或相同上报直接返回，不取锁。Router 在收到消息的那个 Netty 线程上转发，并运行一个名为 `game-router-locations` 的守护线程：它每 100 ms 向连接是新的 Router 上报本地附着的完整集合，对其余 Router 每秒上报一次，并在某个 Router 的连接消失后忘掉它上报的内容。服务在调用方线程上发送，在 Netty 线程上运行路由回调，并在一个守护线程上宣告自己。

无配置 API 的默认值：宣告与位置检查每 100 ms 一次，二者都每秒重复；每个 Router 最多 100,000 条附着；每次位置上报最多 65,535 条，这是 uint16 计数而非帧上限，因此附着数超过它的 Router 会记录溢出而不是上报一个被截断的集合；内层帧最大为 RpcNode 的 `maxFrameSize()` 减 67 字节，使带 Router 头的外层 RPC 帧符合该 Node 的帧上限；该上限只由 RpcNodeBuilder.maxFrameSize 配置一次，Router 不保留自己的常量。路由头偏移取自 RpcWire 公布的帧布局常量。

Router 从不缓冲业务数据：下一跳的所有 Slot 都高于写缓冲高水位时会拒收路由消息，于是 Notify 被丢弃、Call 失败。所有服务的流量都经由 Router，而一对 Router 之间的全部跨 Router 流量共用一条网格连接，因此请按预期在途字节数为 Router Node、以及发送量大的服务配置 `RpcNodeBuilder.writeBufferWaterMark`，例如 512 KiB / 1 MiB 而不是 Netty 的 32 KiB / 64 KiB；测量数据见 [2026-10-13 审查记录](../reviews/2026-10-13-router-forwarding-review.zh-CN.md)，那是在早先设计上测得的，但单连接行为相同。

## 6. 异常、兼容与验证

必需输入为 null 抛 NullPointerException。非法 ID、service、epoch、超时或回复 ID 抛 IllegalArgumentException。start 之前、close 之后或没有已声明 Router 时使用抛 IllegalStateException。Router Profile v5 完全取代 v1–v4：Data envelope 与 v4 相同，但 Data 现在也在 Router 之间流动，新增 Locations 与 Unlocate，且服务只附着一个 Router 而不是全部。其他版本被拒绝，没有混合版本回退。RPC Wire framing、类型与握手不变。路由参与方需一起升级；普通 RPC 参与方保持兼容。

源码：[GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java)、[ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java)、[RouterWire](../../game-router/src/main/java/cn/managame/router/node/RouterWire.java)。验证：[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)、[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)。[RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 演示两 Router 网格上的两跳回显与资源顺序。集成验证运行 `mvn -pl game-router -am test` 与根目录 `mvn clean verify`。

<a id="7-known-limits"></a>

## 7. 已知限制

本节只列出仍然成立的边界。审查过程与证据保留在 [2026-10-07](../reviews/2026-10-07-router-rpc-review.zh-CN.md) 与 [2026-10-13](../reviews/2026-10-13-router-forwarding-review.zh-CN.md) 的非规范记录中。

- **收敛间隙**：Node 附着后会立即被上报给网格，但在途丢失的上报只能由下一次完整刷新修复，因此一条位置最多可能过期一秒。发往位置尚未到达的目标的 Notify 被丢弃并计入 targetNotFound。
- **故障转移归应用**：Router 消失后，附着其上的服务在应用向另一个 Router 开 Peer、且该 Router 的位置传播开之前不可达。两个角色都不选择也不改变 Peer。
- **网格链路断开会隔离一对**：两个 Router 都活着但它们之间的连接消失时，其背后的 Node 报 PEER_NOT_FOUND。没有经第三个 Router 的中继，没有跳数，也没有路径选择。
- **迟到的宣告**：来自旧化身的在途消息可以重新宣告它，直到当前化身的下一次宣告或其连接关闭；在 close 或 removeRouter 之前刚发出的那条，可以重新宣告该 Node，直到它从那个 Router 收到下一次投递并以 DETACH 作答。
- **顺序**：同一键发往同一目标仅在发送方连接与目标位置都不变期间保持顺序；目标迁移到另一个 Router 时不保证跨迁移的顺序。
- **两跳**：目标在另一个 Router 上时要多付一跳和第二个 Router 的 CPU。跨 Router 流量占多数的部署会在多数消息上付这笔开销；换来的是连接数。
- **调用准入**：路由 Call 以一条发往其 Router 的 RPC 待完成调用登记在服务 Node 上，因此 RPC R-SEND-04 的 Peer 级上限（RpcNodeBuilder.maxPendingCalls）适用；任何 Router 都不缓冲业务消息，也不保存调用状态。
- **连接数**：`服务数 + Router 对数`，各自带所配置的 Slot。
- **网格信任**：已声明的 Router 在它上报的位置上是被信任的，而 RPC 握手认证的是集群成员身份而非具体身份，因此一个被配置为 Router 的集群成员可以上报任意位置。按节点分发密钥不在 V1 范围内。
- **Spring 组合**：受管 RPC 通过 `GameRpcConfigurer.decorate/attach/detach` 组合这两个角色（[Spring Java 规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)）；把路由请求分发进 Runtime 以及保存 RoutedRequest 以便回复，仍属应用代码。
- 生产吞吐、内存与分区行为未验证。
