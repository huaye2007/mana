# Router 两级网格改造（2026-10-17）

[English](2026-10-17-router-mesh-redesign.md) | **[简体中文](2026-10-17-router-mesh-redesign.zh-CN.md)**

> 非规范记录。现行契约是 [Router 规范](../ogbs/OGBS-Router-1.0.zh-CN.md)、
> [Router Java 规范](../ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) 与
> [Router Profile v5](../rpc-wire.zh-CN.md#router-profile-v5)。本页记录设计为何改变、它不是什么、用户做出的
> 决定、实现过程中发现的一处竞态，以及验证了什么、没验证什么。

## 1. 改了什么

服务 Node 不再连接每个 Router。它只附着**一个** Router；各 Router 组成网格，互相告知哪些 Node 附着在自己身上。
目标附着在发送方的 Router 上时直接投递，否则**一次性**交给上报该目标的那个 Router。

| | 之前（2026-10-13） | 之后 |
| --- | --- | --- |
| 服务 → Router 连接 | 每个服务连每个 Router，`S × R` | 各一条，连同网格为 `S + R(R−1)/2` |
| Router ↔ Router | 无 | 全网格，每对由 ID 较大的一方发起 |
| Router 状态 | 活连接上的宣告 | 宣告**加上**其他 Router 上报的 Node 位置 |
| 每条消息的跳数 | 恒定一跳 | 目标与发送方同 Router 时一跳，否则两跳，绝不三跳 |
| 发送方的 Router 选择 | 按目标与 routeKey 哈希，带一秒预热 | 没有：只有一条连接 |
| 未投递的 Call | 经另一个 Router 重发 | 上报；没有第二条路 |
| 广播覆盖 | 所选那一个 Router 上的节点 | 其 Router 可达的该 service 全部节点 |
| 故障转移 | 发送方自动改选另一个 Router | 由应用迁移该服务的 Peer |

## 2. 它不是什么

2026-10-13 的记录删掉过一套 Router 间设计，所以第一个问题是这次是否把它捡了回来。并没有，而差别恰恰就是让旧
设计昂贵的那一点：

- **当时删掉、现在依然没有**：**业务键绑定**的复制，连同上限百万条绑定的快照、服务注册与验证、发现移除栅栏、
  绑定表与路由索引、RoutedError、RoutingThread 以及 Router 侧的各个定时器。Router 仍然从不把键映射到 Node；
  玩家或房间仍由发送方自行解析。
- **现在新增**：只复制 **Node 位置**——每个 Node 一条，保存它的 epoch、service 与上报方 Router。该表规模由 Node
  数量决定，因此一次完整刷新只是一个小帧（每条 16 字节），而不是一次承载业务状态的分块快照。

这个规模上界就是全部论据。它解释了为什么周期性完整刷新负担得起、为什么不需要序号、确认或带恢复的增量，也解释
了为什么旧设计的成本不会连同它的形状一起回来。

## 3. 用户做出的决定

有两个问题会实质改变工作量，在实现之前交给了用户：

1. **服务那一条连接归谁管？** → *归应用。* `RT-SCOPE-01` 保留了"声明 Router 不得创建或删除 RPC Peer"这条规则，
   因此两个角色都不调用 addPeer。服务声明它可以使用的 Router，并附着到已连接的已声明 Router 中 ID 最小的那个；
   迁移一个服务（包括它的 Router 挂掉之后）就是迁移它的 Peer。代价是每个应用都要自己实现故障转移；好处是
   game-router 依然不碰任何连接。
2. **两个 Router 都活着但它们之间链路断开？** → *该对不可达。* 严格一跳上限：不经第三个 Router 中继，没有跳数，
   没有路径选择。位置在其上报方 Router 的连接断开期间不可用，因此断链背后的 Node 报 PEER_NOT_FOUND，而不是
   消失进一个黑洞。

## 4. 响应回程不需要 origin 字段

2026-10-13 之前的设计在每个帧里携带发起 Router，而本设计的初稿也假定它必须回来：调用方的待完成调用属于调用方
自己的 Router，所以在两跳之外产生的响应得想办法找回去。

它并不需要这个字段。每个 Router 本来就知道每个 Node 在哪里，而服务 Node 恰好只附着一个 Router，因此解析**调用
方**的 nodeId 是唯一的，且结果正是持有该待完成调用的那个 Router。于是响应就是一条按目标路由的普通消息，和其他
任何消息一样，转发代码只有一条解析路径而不是两条。这比先前两套设计都更简单。

## 5. 实现中发现的一处竞态，以及 RT-STATE-03

把两跳调用端到端接起来时暴露出一个真实的顺序问题。控制流量（Attach、Detach、Locations、Unlocate）用 routeKey 1，
而路由 Data 用内层消息的 routeKey，所以二者走同一条连接但**不走同一个 Slot**。第一次调用的时序是：

1. node 1 附着到 Router A；A 把该位置上报给 Router B。
2. node 1 调用 node 2；A 把请求交给 B。
3. B 投递给 node 2，node 2 回复给 B。
4. B 必须按 node 1 的 nodeId 路由该响应——这需要第 1 步的那条上报。

第 1 步与第 2 步抵达 B 的顺序不确定。B→node 2→B 的往返通常能盖住这个间隙，但并无保证，而失效表现是响应被丢弃、
调用莫名超时。

修法复用了框架本来就有的机制。服务的路由消息会在它附着处宣告自己（RT-NODE-02）；同一条规则现在上移一层：
**Router 从另一个 Router 收到的 Data 帧，同时把它自己的发送方定位在那个 Router 上**（RT-STATE-03）。于是需要
答案的那个请求本身就携带了那个答案所需的位置，竞态无从发生。代价是每个转发帧多一次 map 检查，换来的是不再依赖
刷新时序。

[RouterIntegrationTest.aForwardedFrameLocatesItsOwnSenderWithoutAnyLocationReport](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)
对它做了确定性测试：用一个裸 RpcNode 充当网格中的 Router，它根本不发送任何 Locations，因此唯一能让它那个 Node
变为可达的东西就是那个转发帧。

## 6. 什么变简单了，什么变复杂了

**变简单——ServiceRouting。** 只有一条连接就没有 Router 选择，于是哈希、一秒预热、按 Router 的 `Link` 状态以及
整条重发路径都没了。重发路径曾要求每个在途调用保留自己编码后的帧以便原样再发一次；那份保留及其引用计数记账也
随之消失。`Call` 现在是一个三字段 record。

**变复杂——GameRouter。** 多了第二张表、上报协议、周期性刷新线程，以及数据路径上的双目标解析。它也因此获得了
Router 角色历史上第一个后台线程：一个名为 `game-router-locations` 的守护线程。

**顺带变好的语义。** PEER_NOT_FOUND 以前是有歧义的——它的含义是"不在你问的那个 Router 上"，这正是发送方必须
重发以消歧的原因。现在它的含义是"在这个 Router 能触及的网格里哪里都没有"，这是一个答案而不是一个暗示。按键顺序
也不再依赖一个稳定的 Router 选择集合，只依赖发送方自己的连接以及目标不迁移。

## 7. 接受的代价

- **跨 Router 流量两跳。** 2026-10-13 的测量指出无状态设计把它减半了："每条路由消息现在只过一个 Router，而跨
  Router 流量以前要过两个"。R 个 Router 时大约 `(R−1)/R` 的流量是跨 Router 的，因此多数消息都要付第二跳和第二个
  Router 的 CPU。换来的是连接数。
- **故障转移归应用。** Router 挂掉后，附着其上的服务在应用向别处开 Peer、且该 Router 的位置传播开之前不可达。
- **网格链路断开会隔离一对**（§3 第 2 条决定）。
- **在途丢失的上报最多需要一个刷新周期收敛**；附着与撤销是立即上报的，因此常见情形更快。
- **网格信任。** 已声明的 Router 在它上报的位置上是被信任的，而 RPC 握手认证的是集群成员身份而非具体身份，因此
  一个被配置为 Router 的集群成员可以上报任意位置。性质与先前设计相同，影响面更大。
- **每次上报 65,535 条**，即 uint16 计数上限。附着数超过它的 Router 会记录溢出，而不是上报一个被截断的集合。

## 8. 工作树里改了什么

契约：Router 标准新增 RT-MESH-01、RT-STATE-02、RT-STATE-03，并改写 RT-SCOPE-02、RT-NODE-02、RT-STATE-01、
RT-ADDR-02、RT-ADDR-03、RT-DATA-01、RT-DATA-02、RT-DATA-03、RT-BCAST-01 与 RT-LIFE-01，取舍表整体重写；
Router Profile v5 新增 Locations（op 4）与 Unlocate（op 5），Data envelope 与 v4 逐字节相同，并允许 Data 在
Router 之间流动。两层规范、两种语言一起更新，Router Java 规范、模块 README、架构概览、OGBS 索引与项目 README
同样更新。

代码：`RouterWire`（v5、上报编解码）、`GameRouter`（位置表、网格声明、刷新线程、双目标解析、广播扇出、五字段
`Stats`）、`ServiceRouting`（单 Router、无选择、无预热、无重发）。`RouterEchoExample` 现在装配一个两 Router
网格、两侧各一个服务，于是示例本身就在走两跳。

## 9. 验证状态

从仓库根目录执行 `mvn clean verify -Pquality` 通过：十个模块，每个被检查模块 0 条 Checkstyle 违规，363 个测试，
0 失败，2 个跳过（受环境变量门控的数据库测试）。game-router 为 24 个测试，由 21 增加，并在完整构建之前连续三次
通过。

新增或重写的测试覆盖：保留全部 RPC 字段的两跳转发、§5 的转发帧定位规则、跨网格的广播扇出、跨两跳的按键顺序、
网格链路断开时报 PEER_NOT_FOUND、Node 离开后被整个网格忘掉、应用把服务迁到另一个 Router，以及来自未声明 Peer
的位置上报被拒绝。被删掉的测试正是那些断言本设计不具备的行为的：Router 选择把键铺到多个 Router、重连 Router 的
预热，以及把调用经另一个 Router 重发。

**未测量。** 本记录中没有任何新的吞吐、延迟或内存数字。§7 的两跳代价是基于 2026-10-13 测量数据的推理，而那些
数据取自先前的设计；带进 Java 规范的写缓冲建议同样出自那份记录，描述的是未曾改变的单连接行为。生产容量、真实
多 Router 部署、分区行为与跨语言互操作仍未验证。
