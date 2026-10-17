# OGBS Router Java 25 开发规范 1.0

[English](OGBS-Router-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)**

标准：[Router 规范](OGBS-Router-1.0.zh-CN.md)。线协议：[Router Profile v4](../rpc-wire.zh-CN.md#router-profile-v4)。状态：已实现，具有本地 TCP 契约测试；生产容量与跨语言互通未验证。

## 1. 模块、所有权与公开边界

`cn.managame:game-router:1.0.0-SNAPSHOT` 要求 JDK 25 和 game-rpc，并传递依赖 Network/Core/Netty。RPC 从不依赖 Router。每个进程只有一个由应用拥有的 RpcNode。两个角色都是实现现有 RpcHandler 和 AutoCloseable 的 final 具体类：创建角色，传给 RpcNodeBuilder.handler，在 `rpc.start()` 之前调用 `role.start(rpc)`，并先关闭角色再关闭 Node。start 只关联一次 Node，从不启动网络。参数为 null 抛 NullPointerException；重复 start 或关闭后 start 抛 IllegalStateException。

| 公开类型 | 职责 |
| --- | --- |
| node.GameRouter | Router 角色：在连接到它的 Node 之间中继路由消息 |
| node.ServiceRouting | 服务角色：声明 Router、发送声明、发送、回复和接收投递 |
| call.RouterHandler、call.RoutedRequest | 借用的路由投递及路由调用完成 |

`new GameRouter(RpcHandler directHandler)` 创建 Router；`new ServiceRouting(int serviceId, long nodeEpoch, RouterHandler handler, RpcHandler directHandler)` 创建服务。直连 Handler 接收所有非 Router Profile 的请求，以及所有不属于路由调用的响应和失败。同时实现两个接口的 Handler 可以传入服务的两个位置。RouterWire 是包级内部编解码。不存在其他接口/实现拆分、Handler 修改 API、连接监听器或 RPC 清理钩子。遵循[组件职责边界](OGBS-Router-1.0.zh-CN.md#1-职责与组合)。

## 2. Router 角色

```java
GameRouter router = new GameRouter(directHandler);
RpcNode rpc = RpcNode.builder().nodeId(101).bindAddress(address).handler(router)
        .writeBufferWaterMark(512 * 1024, 1024 * 1024).build();
router.start(rpc);
rpc.start();
// 应用管理上下文中，通常放在 finally：
router.close();
rpc.close();
```

Router 不需要成员、Peer 列表或注册：由服务连接它。它为每个在已连接 Peer 上声明过的 Node 保存一个条目，记录该 Node 的 epoch 和服务。声明（ATTACH，或该 Node 发出的任何路由消息）新增或替换条目；epoch 相同的 DETACH 删除条目。RPC 不通知断线，因此已关闭连接的条目在向它发送失败或容量用尽时才删除。`isAttached(nodeId)` 报告 Peer 仍连接的条目；`serviceNodes(serviceId)` 返回某服务此类 Node 的不可变快照。Router 在附着表之外维护按服务的 Node 索引，广播和 `serviceNodes` 只访问该服务的 Node，代价与该服务的 Node 数成正比，而不是与全部附着 Node 成正比。`stats()` 返回 `GameRouter.Stats(forwarded, targetNotFound, sendRefused, responsesDropped)`，即构造以来单调递增的计数：RPC 已接纳发往下一跳的消息（含每份广播副本和响应）、目标未附着的请求、发往已附着 Node 但被 RPC 拒绝的消息，以及调用方已离开或无法送达的响应。close 幂等，停止中继（之后的 Router 请求以 HANDLER_ERROR 失败）并忘记所有条目。

所有处理都在收到消息的 Netty 线程上完成；Router 没有线程、定时器或锁。最多保存 100,000 个条目：满时先删除 Peer 已断开的条目；仍然满则拒绝该声明并记录日志（携带该声明的 Call 以 HANDLER_ERROR 失败）。

## 3. 服务角色

```java
ServiceRouting routing = new ServiceRouting(MATCH, nodeEpoch, routedHandler, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(1).bindAddress(address).handler(routing).build();
routing.start(rpc);
rpc.start();
for (Router r : routers) { routing.addRouter(r.id()); rpc.addPeer(r.id(), r.address(), 2); }
// ... routing.callNode(targetNodeId, request, callback);
// 应用关闭：routing.close(); rpc.close().
```

serviceId 为正，nodeEpoch 非零；二者在对象生命周期内固定，重启的进程使用新的 nodeEpoch。`addRouter(id)` 声明一个 Router（非零、不是本 Node；重复声明被忽略），`removeRouter(id)` 从该 Router 撤回本 Node；两者都不创建、删除或修改 RPC Peer。两者都要求已 start 且未关闭，否则抛 IllegalStateException；非法 ID 抛 IllegalArgumentException。

start 在一个名为 `service-routing` 的 daemon 线程上开始发送声明。它每 100 ms 检查每个已声明 Router：连接对它而言是新的，或上次被接受的声明已满一秒时，以 Notify 发送 ATTACH（epoch、服务）；被拒绝的 ATTACH 在下次检查时重试。没有确认，也没有需要恢复的东西：重启的 Router 在一秒内得知本 Node。ATTACH 与 DETACH 使用同一个 routeKey，因此经同一个 Slot 按序到达；removeRouter 在声明线程上发送 DETACH，因此已在进行中的声明不会出现在撤回之后。来自未声明 Router 的投递，或 close 之后到达的投递，会被拒绝并再回一条 DETACH，这也修复了超越撤回而先到的声明。close 停止该线程、等待进行中的检查结束、向每个已连接的已声明 Router 发送 DETACH，并且幂等；之后除 close 外的所有操作都抛 IllegalStateException。

## 4. 发送、完成与 body 所有权

ServiceRouting 提供 `notifyNode(nodeId, request)`、`broadcast(serviceId, request)`、`callNode(nodeId, request, callback)`、`callNode(nodeId, request, timeoutMillis, callback)` 和 `reply(routedRequest, response)`。出站 requestId 必须为零；nodeId 非零；serviceId 为正；timeoutMillis 为正且在纳秒范围内；否则在触碰 body 前抛 IllegalArgumentException。默认超时为 RpcNode 的 callTimeout（通常 5 秒）。发送在调用线程上执行，只进入 RPC。

每次发送按目标（Node，广播时为服务）与 routeKey 的哈希选择一个可用的已声明 Router，因此在可用集合不变期间，同一键发往同一目标始终经过同一 Router 及其 RPC Slot 亲和。声明线程看到某 Router 当前连接满一秒后，该 Router 才算可用，以便它重启后其他 Node 有时间在其上声明自己；没有可用 Router 时使用任一已连接 Router。没有已声明 Router 时发送抛 IllegalStateException；都未连接时使用第一个已声明 Router，由 RPC 报告 UNAVAILABLE（Notify 状态或 onFail）。Notify 的 ACCEPTED 表示该 Router 连接接纳，而非投递成功。

Router 只在没有投递时才以 PEER_NOT_FOUND 或 UNAVAILABLE 回答 Call，因此服务会把这样的调用原样经另一个尚未为它尝试过的已连接 Router 重发；RPC 调用 ID 改变，业务身份不变，每次尝试都有完整超时。只有所有已连接 Router 都试过后，Handler 才会看到该错误。Notify 没有这样的回答，从不重发；本地失败（onFail）的调用也不重发，因为它可能已经投递。重发的调用可能超过同一键之后的消息。

RouterHandler.onRoutedRequest 接收 RoutedRequest：来源 nodeId 与 nodeEpoch、借用的内层 RpcRequest，以及投递它的 Router routerId。只接受已声明 Router 的投递，且必须是本 Node 的 ID 与 epoch；其他投递被拒绝，不会到达 Handler。reply 经 routerId 把响应送回精确的调用方代际；要求请求 ID 非零且与响应 ID 相同。RoutedRequest 不可变，要求 routerId 非零；稍后回复时保存它，回调结束后仍使用的 body 须 retain/copy 并对应 release。onRoutedRequest 抛出 RuntimeException 时，若为 Call 先尝试回复 HANDLER_ERROR，再把异常抛给 RPC。

路由调用完成经 RouterHandler 使用原 callback。`onResponse(targetNodeId, command, response, callback)` 接收目标解包后的响应及其自身的错误与 Metadata（目标 Handler 抛异常时为 HANDLER_ERROR），或最后尝试的 Router 返回的正错误：目标未在该 Router 附着时为 PEER_NOT_FOUND，Router 无法发送给目标时为 UNAVAILABLE，Router 判定消息非法时为 HANDLER_ERROR。`onFail(targetNodeId, command, error, callback)` 接收本地失败：TIMEOUT、连接丢失时的 UNAVAILABLE、NODE_CLOSED，以及响应非法或并非来自本代际调用目标时的 PROTOCOL_ERROR。普通直连流量经直连 Handler。

| 边界 | 所有权 |
| --- | --- |
| 参数非法或角色不可用，编码前 | 调用方保留 body |
| 已接受的发送、回复或调用 | 消费一个 body 引用，包括编码和传输失败；成功返回后 RPC 仍引用大 body。调用另外保留其编码帧直到完成，以备重发 |
| 路由投递与路由响应 | 回调期间借用；Handler 不得释放 |
| Router 中继 | 内层帧不解码：每个副本换新的路由头，若为 Call 还写入调用方调用 ID；不超过 2 KiB 的内层帧复制，更大的引用收到的帧，由 RPC 释放 |

## 5. 线程与边界

数据路径不使用锁：转发只做无锁查表。附着表的变化（新声明或代际/服务变化、DETACH、发送失败后的删除、容量清理、close）在一个短锁内同时更新附着表与按服务索引；重复的相同声明直接返回，不取锁。Router 在接收消息的 Netty 线程上完成全部工作。服务在调用线程上发送，在 Netty 线程上执行路由回调；它自己唯一的线程负责发送声明。默认值（无配置 API）：每 100 ms 检查一次声明、每秒重复声明；看到 Router 连接一秒后该 Router 可用；每个 Router 100,000 个附着；内层帧最多为所在 RpcNode 的 `maxFrameSize()` 减 67 字节，使带 Router 头的外层 RPC 帧恰好不超过 Node 的帧上限；帧上限只在 RpcNodeBuilder.maxFrameSize 一处配置，Router 不另设常量。路由头的偏移取自 RpcWire 公开的帧布局常量。

Router 从不缓存业务数据：下一跳所有 Slot 都超过写缓冲高水位时拒绝路由消息，Notify 被丢弃，Call 失败。所有服务的流量都经过 Router，因此 Router Node（以及大量发送的服务）应按在途字节量配置 `RpcNodeBuilder.writeBufferWaterMark`，例如 512 KiB / 1 MiB，而不是 Netty 默认的 32 KiB / 64 KiB；测量数据见 [2026-10-13 审查记录](../reviews/2026-10-13-router-forwarding-review.zh-CN.md)。

## 6. 异常、兼容与验证

必要参数为 null 抛 NullPointerException；非法 ID、服务、epoch、超时或回复 ID 抛 IllegalArgumentException；start 前、close 后或没有已声明 Router 时使用抛 IllegalStateException。Router Profile v4 完全取代 v1–v3：不再有 Router 同步、注册、绑定或路由错误操作，拒绝其他版本，不支持混合版本回退。RPC Wire framing、type 与握手不变。路由参与方需一起升级，普通 RPC 参与方保持兼容。

源码：[GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java)、[ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java)、[RouterWire](../../game-router/src/main/java/cn/managame/router/node/RouterWire.java)。验证：[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)、[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)。[RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 展示装配与资源顺序。使用 `mvn -pl game-router -am test` 和根目录 `mvn clean verify` 验证集成。

<a id="7-known-limits"></a>

## 7. 已知限制

本节只列当前仍成立的边界。审查过程与证据保留在 [2026-10-07](../reviews/2026-10-07-router-rpc-review.zh-CN.md) 和 [2026-10-13](../reviews/2026-10-13-router-forwarding-review.zh-CN.md) 的非规范记录中。

- **声明间隙**：错过一次快速 Router 重启的服务，在该 Router 上最多一秒内不可知。发送方在这一秒内避开新连接的 Router，并重发 Router 未能投递的调用，但经尚未有目标声明的 Router 发送的 Notify 会被丢弃。
- **迟到的声明**：旧代际仍在途的消息可能重新声明它，直到当前代际下一次声明或其连接关闭；close 或 removeRouter 前刚发出的消息可能重新声明本 Node，直到该 Router 下一次向它投递，届时它以 DETACH 回答。
- **连接不完整**：目标未连接所选 Router 时，调用经其他 Router 重发；Notify 被丢弃（计入 targetNotFound）。
- **顺序**：同一键发往同一目标只在可用 Router 集合不变且该键没有调用被重发期间保持顺序。
- **调用准入**：路由 Call 在服务 Node 上登记为发往所选 Router 的 RPC 待完成调用，因此受 RPC R-SEND-04 的每 Peer 上限（RpcNodeBuilder.maxPendingCalls）约束；Router 自身不缓存业务消息。
- **连接数**：服务数 × Router 数条连接，每条按配置的 Slot 数。
- **Spring 组合**：托管 RPC 通过 `GameRpcConfigurer.decorate/attach/detach` 组合两个角色（[Spring Java 规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)）；把路由请求分派进 Runtime 以及为回复保存 RoutedRequest 仍由应用实现。
- 生产吞吐、内存与分区行为未验证。
