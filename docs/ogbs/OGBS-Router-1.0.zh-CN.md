# OGBS Router 规范 1.0

[English](OGBS-Router-1.0.md) | **[简体中文](OGBS-Router-1.0.zh-CN.md)**

文档类型：标准规范（语言无关）。组件 game-router。配套：[Java 25 开发规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。依赖：[RPC](OGBS-RPC-1.0.zh-CN.md)、[Core](OGBS-Core-1.0.zh-CN.md)。字节布局：[RPC Wire Profile 中的 Router Profile](../rpc-wire.zh-CN.md#router-profile-v5)。状态：已有 Java 参考实现与本地 TCP 契约测试。生产容量与跨语言互操作尚未验证。

Router 之间组成网格，为附着在自己身上的服务 Node 转发消息。一个服务 Node 只连接**一个** Router 并在那里宣告自己；每个 Router 把"哪些 Node 附着在我这里"告知其他 Router。发往某个确切 Node 的消息先到发送方自己的 Router，由它或者直接投递给附着在自己身上的 Node，或者一次性交给该 Node 所附着的那个 Router。把业务键（例如玩家）解析到 Node 仍然属于发送方。未决限制见 [Java 已知限制](OGBS-Router-Java-25-Specification-1.0.zh-CN.md#7-已知限制)。

## 1. 职责与组合

**RT-SCOPE-01** Router 必须建立在 RPC 的发送、接收、直连 Peer、Slot、调用 ID、待完成调用、超时与回调之上。一个进程只有一个 RPC Node；安装路由不得创建第二个 Node 或独立传输。应用仍可用同一个 Node 做普通直连 RPC。Router 只负责转发；RPC 的启动、关闭与 Peer 配置归应用，连接一个 Router 与连接任何其他 Node 使用同一个 RPC Peer 操作。声明一个服务或一个 Router 使用哪些 Router，不得创建或删除 RPC Peer：选择服务连接哪个 Router，以及该 Router 消失后把它重连到别处，都是应用的决定。

**RT-SCOPE-02** V1 通过两级网格提供精确 Node 寻址与服务广播：服务 Node 附着到一个 Router，Router 之间互联。Router 不解析业务键，不保存绑定或注册，不实现发现或订阅，不存储或重试消息，不持有玩家状态，也不提供 Runtime 串行执行。Router 之间唯一复制的东西是"哪个 Node 附着在哪里"，其规模由 Node 数量决定，永远不由业务键数量决定。一条路由消息在目标附着于发送方的 Router 时**过一个 Router，否则过两个**；它永远不会经第三个 Router 中继。RPC 的成功或超时都不证明业务恰好执行一次。

| 所有者 | 职责 |
| --- | --- |
| RPC | 连接与 Slot、消息传输、调用 ID、待完成调用、超时与完成回调 |
| Router | 投递给附着在自己身上的 Node；其余一次性交给该 Node 所附着的 Router；并把自己身上的附着情况告知其他 Router |
| 发送方服务 | 把业务键（玩家、房间）解析为 Node ID |
| 外部发现 | 存在哪些服务实例与 Router，以及它们的地址 |
| 应用 | 组件装配、启停、为每个服务选择 Router 并在需要时重连到别处、连接 Router 网格 |

设计理由：让每个服务连接每个 Router 需要 `服务数 × Router 数` 条连接，而且每个 Router 本来也要为每个 Node 保存一条宣告。让一个服务只附着一个 Router，连接数变为 `服务数 + Router 对数`，并把扇出搬进网格——在那里它按 Router 对付一次，而不是按服务付一次。代价是目标在另一个 Router 上时多一跳，以及一张被复制的位置表；这张表的规模由 Node 数量决定，这正是它负担得起的原因，也正是早先那套无界的业务键绑定复制不会回来的原因。玩家住在哪里的权威仍留在做这个决定的业务代码手里。

## 2. Node、附着与网格

**RT-NODE-01** Node 身份是 `(nodeId, nodeEpoch, serviceId)`。nodeId 是非零 uint32，nodeEpoch 是非零 uint64，serviceId 是正 int32。Router ID 与服务 Node ID 共享一个命名空间。每个 Node 只服务一个 service。nodeEpoch 区分进程化身：重启后的进程使用新值，该值必须不同但不要求更大。

**RT-NODE-02** 附着绑定在连接上。一个服务 Node **同一时刻最多附着一个 Router**：它所连接的那个。它在看到连接后立即在那里宣告身份，并周期性重复宣告，它发出的每条路由消息也同时宣告它。Router 只在该 Node 连接存续期间保留其最新一次宣告；新化身替换旧化身。宣告在其连接关闭或该 Node 以相同 epoch 撤销时失效；只有在发送失败时才得知连接已关闭的实现，可以在那时丢弃它。服务收到来自它并未使用的 Router 的投递时会再次撤销，因此被较早宣告盖过的撤销仍然生效。宣告不是注册：它不被确认、不被持久化，也不说明服务是否存在。重启后的 Router 从各 Node 的下一次宣告重新学到自己身上的 Node，从其他 Router 的下一次位置刷新重新学到其余 Node。

**RT-MESH-01** 每个 Router 都与它要借道的其他每个 Router 相连，每一对之间恰好一条连接：**由 ID 较大的 Router 发起**，于是 ID 较小的那个从入站握手学到对端。Router 必须知道网格中各 Router 的 ID，并且必须把来自任何其他 Peer 的位置消息判为非法：不是已声明 Router 的 Node 不能注入位置。Router ID 与它们的地址一样属于应用配置。Router 不得把从另一个 Router 收到的消息再转给第三个 Router，这使网格无需跳数即无环。

**RT-STATE-01** Router 只能保存两样东西，且二者规模都由 Node 数量决定：连接到它的服务 Node 的宣告，以及其他 Router 上报的位置。它不得保存业务键绑定表、按键的路由缓存、注册、待完成调用、序号或任何消息。自己连接上的宣告是权威的，对同一个 nodeId 必须胜过上报的位置——直连的 Node 就在它自己说的地方。

**RT-STATE-02** Router 周期性地把自己身上的附着情况以**完整集合**上报给每个已连接的 Router；某个 Node 附着时立即上报一条新增，自己某条宣告被丢弃时立即上报一条删除。完整集合替换该 Router 先前上报的全部内容，因此未经删除就消失的 Node 会在下一次刷新时被忘掉；删除仅在 nodeId 与 nodeEpoch 都匹配时生效。上报不被确认、不带序号、永不重试：下一次周期性完整集合会修复任何丢失。上报来的位置仅在与上报它的那个 Router 的连接存续期间可用；该连接消失后，它上报的一切都必须停止可用，于是一条断开的网格链路会让其背后的 Node 变为不可达，而不是看似可寻址却投不到。因此 Node 附着或迁移后的收敛时间上界为一个刷新周期，而立即新增与立即删除让常见情形快于这个上界。

**RT-STATE-03** Router 从另一个 Router 收到的路由消息，必须同时把该消息的发送方定位在那个 Router 上，正如服务的路由消息会在其附着处宣告自己一样。正是这条规则让第二跳自给自足：请求的响应需要调用方的位置，而承载该位置的上报虽走同一条连接却不一定走同一个 Slot，没有这条规则时，第一次调用的响应可能落进一个要等下一次刷新才被填上的空洞。以这种方式学到的位置在其他所有方面都是一条普通的上报位置，而附着在本地的 Node 永远不会被它顶替。

## 3. 寻址

**RT-ADDR-01** 一条路由消息以确切 nodeId 为目标，可选携带确切 nodeEpoch，由投递方 Router 校验。动态寻址由发送方在发送前解析；Router 从不把键映射到 Node。玩家示例：拥有玩家 B 的服务在业务状态里记录 B 在 Node 12；发送方查出来并发往 Node 12，把 B 的 ID 放进业务身份或载荷。若 B 已迁移，消息去哪里由发送方的查询决定；Router 对已迁移的玩家不作任何保证。

**RT-ADDR-02** 服务 Node 把每条路由消息都发往它所连接的那一个 Router；没有逐条消息的 Router 选择，也没有发送方预热。因此在该连接与目标位置都不变的前提下，同一 routeKey 发往同一目标的消息保持顺序；二者任一发生变化（包括目标迁移到另一个 Router）都不保证跨变化的顺序。

**RT-ADDR-03** Router 在看不到目标任何位置时以 PEER_NOT_FOUND 回答 Call，而发送方必须上报该错误而不是重发：只有一条连接就没有第二条路可试，而且该 Router 已经查过它所能看到的整个网格。因此 PEER_NOT_FOUND 的含义是"在这个 Router 能触及的范围内哪里都没有附着"，其中包括目标位于一条已断开的网格链路之后。本地失败的 Call（例如连接丢失）可能已经投递，不得重发。

## 4. 转发与调用完成

**RT-DATA-01** Router 仅在一条路由消息的源 Node 就是它直连的那个 RPC Peer、或该消息来自网格中一个已声明 Router 时接受它。随后它解析目标：附着在自己身上的 Node 直接投递，并寻址到那个确切化身；另一个 Router 上报过的 Node 一次性交给那个 Router，由它投递；其余情形按 RT-DATA-03 失败。从 Router 收到的消息只会被本地投递。Metadata、body、业务身份、command、routeKey 与调用 ID 跨两跳都保持不变；内层 RPC 帧除调用 ID 外逐字节透传。服务只接受来自它正在使用的那个 Router、且目标为自己 ID 与 epoch 的路由消息。

**RT-DATA-02** 路由调用的待完成状态与超时归调用方的 RPC Node；没有任何 Router 创建调用或保存关联。调用方的 Router 把调用方的外层调用 ID 放进转发出去的请求。目标把响应回给投递该请求的那个 Router，寻址到调用方的确切化身，而该响应就像任何其他消息一样按调用方的 nodeId 路由：调用方附着在这里就立即投递，否则一次性交给调用方所附着的那个 Router。由于服务 Node 只附着一个 Router，这次解析是唯一的，且必然抵达持有调用方待完成调用的那个 Router，由它用原生 RPC 响应完成该调用。没有任何消息携带"来自哪个 Router"。目标化身已不再附着、或调用方的位置已消失的响应被丢弃。调用方自己的 Router 或它到该 Router 的连接丢失时，RPC 会让其待完成调用失败；此后迟到的响应已无可完成。

**RT-DATA-03** 目标没有可用位置的 Call 立即以 RPC PEER_NOT_FOUND 失败；被拒绝的发送——无论发往已附着的 Node 还是发往下一个 Router——以 UNAVAILABLE 失败；目标的路由处理器抛出异常返回 HANDLER_ERROR。Notify 无人等待，上述情形下被丢弃。Router 从不缓冲或重试业务消息：下一跳写缓冲区满时拒收该消息，而网格连接的写缓冲区必须覆盖两个 Router 之间在途的字节数。ACCEPTED 只表示第一跳准入；Router 不提供端到端回执。Router 应当对转发的消息、交给另一个 Router 的消息以及每一类丢弃（无位置、发送被拒、响应找不到调用方）计数，使静默丢失可观测。远端错误（包括 Router 自己产生的）进入响应处理器；本地 RPC 失败进入失败处理器。

## 5. 广播

**RT-BCAST-01** 广播只能是 Notify。发送方把一条消息发给自己的 Router。该 Router 向附着在自己身上的该 service 的每个 Node 各投一份（发送方本身若服务该 service 也包含在内），各自寻址到该 Node 的确切化身；并把这次广播一次性交给每一个已连接的其他 Router，各自只投递给附着在自己身上的 Node。因此从 Router 收到的广播绝不会再次转出。覆盖范围是其 Router 可从发送方的 Router 触及的该 service 的全部 Node；位于已断开网格链路之后的 Node 收不到。没有订阅表、聚合结果、重放或投递保证，也没有去重：一个同时附着两个 Router 的 Node（RT-NODE-02 禁止这种情况）会收到两份。

## 6. 生命周期与所有权

**RT-LIFE-01** 新增一个 Router 不需要状态迁移：应用把它接入网格，把该由它承载的服务重连到它，各 Router 在一个刷新周期内交换完位置。移除一个 Router 是应用的决定：把附着其上的服务重连到别处，并断开网格连接。Router 崩溃会以 RPC 的连接丢失错误让经由它在途的调用失败；附着其上的服务在应用把它们重连到另一个 Router、且该 Router 的位置传播开之前不可达。重启后的服务使用新 epoch，因此寻址到旧化身的流量被拒或被丢。

**RT-LIFE-02** 装配与关闭顺序归应用：先关路由，再关 RPC。关闭服务角色会停止其宣告，并从它正在使用的 Router 撤销该 Node；关闭 Router 角色会停止转发、忘掉全部宣告与上报来的位置，并在仍有可能时向网格上报自己这些附着的删除。路由不创建端点或业务执行器；服务角色的周期性宣告与 Router 角色的周期性位置刷新是它唯一的后台工作。已接纳的工作没有取消手段，超时后执行结果不确定。完成通过回调交付，不提供返回 future 的 API。

## 7. 已确认取舍与验证

| 决定 | 理由 | 何时重新考虑 |
| --- | --- | --- |
| 服务 Node 只附着一个 Router | 连接数由 `服务数 × Router 数` 变为 `服务数 + Router 对数`；每个发送方只有一条路径，于是按键顺序不再需要 Router 选择规则 | 服务必须在其 Router 丢失时无需应用重连即可存活 |
| Router 之间复制位置，而不是业务键绑定 | 该表规模由 Node 数量决定，完整刷新只是一个小帧；绑定复制是无界的，不会回来 | Node 数量触及单次刷新的帧上限或条目上限 |
| 周期性完整刷新 + 立即新增/删除 | 丢失自行修复，无需确认、序号或重试；常见情形不必等一个周期 | 收敛时间必须小于一个刷新周期 |
| 最多两跳，绝不经第三个 Router | 无需跳数即无环，也无需任何路由决策；Router 仍然只是转发器 | 预期会出现两端都活、而它们之间链路断开的情况 |
| 上报来的位置在其 Router 连接断开时不可用 | 断开的网格链路会报 PEER_NOT_FOUND，而不是静默丢进黑洞 | 接受多跳中继，连同它的环路预防与乱序代价 |
| 响应按调用方 nodeId 路由，不设 origin 字段 | 调用方只附着一个 Router，所以普通解析本来就会抵达持有其待完成调用的那个 Router | 调用方可以同时附着在多处 |
| 业务键由发送方解析 | 放置权归业务；Router 永远不会跟随一条过期绑定 | 大量发送方需要一个共享键目录，且其保证业务存储无法提供 |
| 由 ID 较大的 Router 发起网格连接 | 每对一条连接，无需协商，也没有重复连接竞态 | RPC 自身具备重复连接的消解机制 |
| 在既有 Node 上用普通 Handler | 身份、连接体系与调用完成方都只有一套；装配留在 RPC 之外 | 缺少某个具体且可复用的 RPC 能力 |

Java 机制、默认值与包归属见 [Java 规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。[RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java) 覆盖真实 TCP 的 call/notify/response 字段、大 body、目标缺失、两跳转发、位置刷新与删除、跨网格的广播扇出、按键顺序、Router 丢失与重启、网格链路断开、化身校验、处理器异常、畸形响应、伪造源、来自未声明 Peer 的位置以及生命周期；[RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java) 覆盖字节布局。[可运行示例](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 为每个被模拟的进程装配一个 RPC Node。本地测试不等于生产规模、网格安全或跨语言认证。发现、持久化、远程取消与 Runtime 适配不在 V1 范围内。
