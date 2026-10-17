# Router 转发与结构审查记录（2026-10-13）

[English](2026-10-13-router-forwarding-review.md) | **[简体中文](2026-10-13-router-forwarding-review.zh-CN.md)**

> 非规范记录。现行契约见 [Router 规范](../ogbs/OGBS-Router-1.0.zh-CN.md)、[Router Java 规范](../ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) 和 [Wire Profile](../rpc-wire.zh-CN.md#router-profile-v4)。本文保留本次审查的问题、测量与决定。
>
> 当天稍后 Router 改为无状态中继（第 4 节），第 1–3 节讨论的 RoutingThread、RouterSync、origin 字段和 Router 间一跳随之删除；只改写路由头的转发方式和写缓冲建议保持不变。
>
> 2026-10-17 被取代：第 4 节的设计——每个服务连接每个 Router，因此 Router 之间互不通信——已被两级网格替代，服务只附着一个 Router，各 Router 之间复制 Node 位置。Node 位置复制的规模由 Node 数量决定，它不是第 4 节删掉的那套业务键绑定复制，后者依旧不复存在。第 1–3 节仍是第 4 节之前设计的记录。见 [2026-10-17 Router 网格改造](2026-10-17-router-mesh-redesign.zh-CN.md)。

## 1. 问题：RoutingThread 能否删除？

不能。RoutingThread 是两个角色控制状态无锁的唯一机制：GameRouter 的本地桶、发现移除限制和复制状态，ServiceRouting 的注册与控制队列，都只在这个线程上修改。评估过的替代方案均被否决：

| 替代方案 | 代价 |
| --- | --- |
| 用锁代替单一所有者线程 | Netty 线程与控制工作竞争；捕获或应用大快照时阻塞它们（即之前 ServiceRouting 的问题） |
| 在 Netty EventLoop 上执行控制工作 | 最多 1,000,000 个绑定的快照会卡住该 EventLoop 上的所有连接；RpcNode 也不暴露 EventLoop |
| 在每个角色中内联执行器 | 同样的 `call/post/schedule/every/close` 代码写两遍 |

该类保持 48 行。1 ms 的 `schedule` 重试也保留：RPC 没有可写性回调，若改为在 20 ms tick 上重试，经 64 KiB 写缓冲排出的快照约被限制为每 20 ms 3 帧。

## 2. 发现与修改

1. **每过一个 Router 都对内层 RPC 帧完整解码再重新编码。** 两 Router 路由 notify 负载下的 JFR 显示，Router 热点是 RouterWire.data、RpcWire.decodeRequest 和 RpcEncoder.encode；广播的每个接收者也各重编码一次。现在 Router 只解析 44 字节路由头及内层调用 ID 和 routeKey，写入新路由头后传递内层帧：不超过 2 KiB 时复制，更大时引用。单跳微基准（含外层 RPC 编码；JDK 21、2 vCPU 容器，±10%）：

   | 内层 body | 解码 + 重编码 | 只改写路由头 |
   | --- | --- | --- |
   | 64 B | 470–520 ns | 280–350 ns |
   | 1 KiB | 640–720 ns | 440–540 ns |
   | 8 KiB | 1,100–1,300 ns | 740–870 ns |

   小帧也一律引用（不复制）时，64 B 为 370–450 ns，因此小帧继续复制。端到端回环测试（单 JVM、两 Router、两服务）中差异未超出噪声：那里瓶颈是发送方、回环 TCP 和共享 EventLoop。节省在 Router 受 CPU 限制或扇出广播时才有意义。
2. **没有 origin Router 的响应会经副本索引定位调用方**，违反 RT-DATA-02（“never through a newly resolved binding or a replica lookup”）。已删除该回退及不带 origin 的 `RoutedRequest` 构造器；Router 丢弃此类响应。
3. **GameRouter（659 行）承担四项职责**：API 与生命周期、本地权威、Router 间复制和转发。复制已移入包级 RouterSync（会话、快照、增量、副本）；GameRouter 保留权威、服务控制、转发和公开 API（336 行）。`GameRouter.forServiceNode` 返回的是 ServiceRouting，因此两个工厂都改为公开构造器。`RouteIndex.resolve` 直接返回定位到的 Node，每条动态消息少一次查找。
4. **突发下路由 Notify 丢失是写缓冲配置问题，不是缺陷。** Router 从不缓存业务数据，两个 Router 之间的全部流量走同一个 Slot。经两个 Router、在途 2,000 条消息时：

   | Router 写缓冲（low / high） | 64 B Notify | 1 KiB Notify |
   | --- | --- | --- |
   | Netty 默认 32 / 64 KiB | 50 k 中丢失 1.7–1.8 k，10–15 k/s | — |
   | 512 KiB / 1 MiB | 无丢失，120–200 k/s | 50 k 中丢失 0.9–1.4 k |
   | 2 / 4 MiB | 无丢失，120–185 k/s | 无丢失，100–135 k/s |

   高水位必须覆盖在途字节量。Java 规范已写明这一点，RouterEchoExample 配置为 512 KiB / 1 MiB。

## 3. 未修改

- 用每个 Link 一个类代替 RouterSync：共享会话计数、快照来源和推送调度都得传进每个 Link。
- Router 缓存被拒绝的数据：会把可见的丢弃换成无界内存和隐藏延迟；按在途字节量调整写缓冲即可消除测得的丢失。
- 校验深度：Router 仍校验 envelope 以及内层帧的长度、类型和固定字段；Metadata 与 body 只由接收服务校验一次。

验证：game-router 47 个测试连续六次运行通过；game-spring 17 个、game-demo 31 个通过。基准测试为临时程序，不属于构建。

## 4. 决定：Router 不保存状态

随后用户要求 game-router 只做转发、不保存其他状态。三个选择确定了设计：业务键由发送方解析（删除 bind/unbind 和动态寻址）；每个服务连接所有 Router，因此 Router 之间互不通信；Router 只能保存已连接 Node 对自身的声明（nodeEpoch、serviceId），连接断开即删除。

删除的内容：Router 间会话、快照、增量和副本；绑定表和路由索引；服务注册、校验与恢复；发现移除限制；RoutedError；origin 字段；RoutingThread 及 Router 端所有定时器；RouterErrorCodes 以及 Core 错误码 5001–5004（保持退役）。game-router 主代码从约 1,530 行降到约 550 行，Router 角色只持有一个声明 ConcurrentHashMap，并且只在 Netty 线程上运行。

替代机制：服务在每条新连接上及每秒发送一次无确认的 ATTACH 声明自己；每条路由消息也同时声明其发送方；由于 RPC pending 调用属于单个 Peer，调用的响应经投递该请求的 Router 返回。每次发送按目标与 routeKey 选择一个已连接 Router。Router Profile v4 只有三个操作（Attach、Detach、Data），Data envelope 为 35 字节。

接受的代价（也列为 Java 已知限制）：未察觉的 Router 重启后最多一秒不可达；目标未连接所选 Router 时不会改经其他 Router 重试；同一键只在已连接 Router 集合稳定期间保持顺序；连接数为服务数 × Router 数。

两 Router、两服务均连接两者的回环测试（单 JVM、2 vCPU、4 MiB 写缓冲、routeKey 分散到两个 Router）测得：64 B 和 1 KiB Notify 105–155 k/s 且无丢失，窗口化调用 47–61 k/s；现在每条路由消息只经过一个 Router，而此前跨 Router 的流量要经过两个。

验证：game-router 17 个测试连续六次运行通过；game-core 7 个、game-rpc 36 个、game-spring 17 个、game-demo 31 个通过。

## 5. 无状态 Router 的复查

对无状态设计的第二轮审查发现以下问题，均已在当天修复。

| 发现 | 修复 |
| --- | --- |
| 发送方自己的连接一恢复就使用该 Router，而此时其他 Node 尚未重连并在其上声明。探针：两个 Router、每 2 ms 一次调用，其中一个 Router 重启；每次重启约有 12–16 个调用在发送方重连后约 0.1 s 内以 PEER_NOT_FOUND 失败 | 看到 Router 连接满一个声明周期（1 s）后才使用它；Router 以 PEER_NOT_FOUND/UNAVAILABLE 回答的调用证明未投递，会经其他每个已连接 Router 各重发一次。修复后同一探针：不再出现 PEER_NOT_FOUND，只有经正在关闭的 Router 在途的那一个调用以本地 UNAVAILABLE 失败 |
| 只连接了部分 Router 的目标，经其他 Router 的调用全部失败 | 由重发覆盖 |
| Attach 与 Detach 使用 routeKey 0，RPC 把它们分散到多个 Slot，Detach 可能超越 Attach，留下残留条目，继续收到会被服务拒收的投递 | 两者都使用 routeKey 1；服务收到自己不使用的 Router 的投递时再回一条 Detach |
| 丢弃（目标未附着、发送被拒、响应找不到调用方）完全静默 | `GameRouter.stats()` 统计已转发消息及每类丢弃 |
| 内层帧上限（4 MiB − 64）使 Data 请求最多可超过 4 MiB RPC 帧 3 字节 | 上限改为 4 MiB − 67 |
| close 后 HANDLER_ERROR 回复失败时，业务 Handler 的异常被掩盖 | 回复失败作为 suppressed 附加 |
| 规范称声明随连接消失；RPC 不通知断线，条目实际在向它发送失败时才删除 | 措辞改为说明延迟删除 |

广播仍遍历全部附着条目；在有部署实测到大量 Node 加频繁广播的开销之前保持不变。

每个新测试（连接不完整时重发、Router 重启后的预热、向不再使用的 Router 再次撤回）在去掉对应修复时失败，加上修复后通过。game-router 21 个测试连续六次运行通过；game-spring 17 个、game-demo 31 个通过。

