# OGBS RPC Specification 1.0

[English](OGBS-RPC-1.0.md) | **[简体中文](OGBS-RPC-1.0.zh-CN.md)**

文档类型：**标准规范（语言无关）**。组件 game-rpc。Java 实现已提供，但当前源码在 Peer 重建关联、超时通知隔离和有限调用接纳方面存在已确认偏差，另有恢复竞态。详见 Java 规范 9.1。生产容量与跨语言互操作尚未验证。

配套：[Java 25 开发规范](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)、[RPC Wire Profile](../rpc-wire.zh-CN.md)。
规范性依赖：[Network](OGBS-Network-1.0.zh-CN.md)、[Core](OGBS-Core-1.0.zh-CN.md)。

## 1. 范围

RPC 用于内部服务器节点的直接通信，提供 call、notify、reply、固定多连接 Slot、握手、心跳、重连与本地调用完成管理。

部署基线是长期运行的游戏服务器服务：RPC 端点随服务启动，正常运行期间持续存活，在维护或服务退出时关闭。普通传输故障及远端重启通过连接恢复处理，本地端点继续运行。反复替换本地端点、生命周期热切换和独立 RPC drain 协议不属于当前基线。

不解析业务 body，不提供业务协议注册、Runtime 调度、服务发现、Router、持久投递、自动业务重试或远端取消。成功收到响应不等于业务 exactly-once；超时不撤销远端执行。

## 2. 对象与标识

| 对象 / 字段 | 定义 |
| --- | --- |
| RpcNode | 本地 RPC 端点，显式启动，最终关闭 |
| nodeId | 非零 uint32 节点标识 |
| RpcPeer | 与本节点直接通信的一个远端关系 |
| ConnectionSlot | Peer 内固定逻辑连接位置 |
| requestId | 在 Peer 内匹配的 uint32 调用标识；0 为 Notify |
| routeKey | uint64 亲和性值；0 无亲和性；不是 Runtime Route |
| businessIdType / businessId | uint8 / uint64 业务身份；type=0 未指定 |
| PendingCall | 一次本地 call 的关联 ID、完成通知及超时 |

**R-PEER-01** 同一 remoteNodeId 最多一个当前 Peer；ID 不得为 0 或自身。addPeer 的地址与 slotCount 相同则幂等，不同则拒绝，必须先 remove。注册不保证已经有连接。

**R-PEER-02** slotCount 在一个 Peer 生命周期中固定为 1..255。每个 Slot 仅有空或一个 READY 连接；改变数量必须开始新 Peer 生命周期。

**R-PEER-03** addPeer 创建主动 Peer，持有目标地址并维护空 Slot。入站合法握手可以自动创建被动 Peer。被动 Peer 经 addPeer 原地升级，保留现有连接、计数器和 PendingCall；不替换对象。双方都主动维护时重复连接仍遵守首个合法绑定获胜，没有 Node ID 大小仲裁。

**R-PEER-04** 主动 Peer 不自动删除。被动 Peer 在无连接且无 PendingCall 时自动删除；清理与同 ID 新握手绑定必须串行仲裁，不能删除刚建立的会话。remove 不是禁止接入，后续合法入站可以创建新 Peer。旧 Peer 的异步结果不得复活或影响新 Peer。

例：被动 Peer 全断但仍有 PendingCall，保留到响应、超时或移除完成；因此重连后可以经另一个 Slot 返回响应。若已完全空闲，则可清理。

## 3. 消息

Request 含 command、requestId、routeKey、businessIdType、businessId、Metadata 和不透明 body。command 非零；不要求正的有符号表示。requestId=0 为 Notify，否则为 Call。Notify 不建立 PendingCall，也不允许 Response。

Response 含非零 requestId、errorCode、Metadata、body，不重复 command 或 routeKey。响应解释所需 command 从本地 PendingCall 得到。errorCode 使用 [Core 错误码空间](OGBS-Core-1.0.zh-CN.md#4-frameworkerrorcode)，没有高位标记。

Handshake 交换 magic/version/nodeId/slotId/slotCount；Heartbeat 无 payload。布局唯一来源为 [Wire Profile](../rpc-wire.zh-CN.md)。

**R-MSG-01** 消息通过参数与生命周期校验后由发送端组件接管其可释放 body；未发送、编码失败、Peer 不存在等路径也必须回收。参数或生命周期校验失败不接管。入站 body 在接收回调期间借用；异步使用必须按实现提供的所有权机制延长寿命。Metadata 按 Core 只读约定共享。

## 4. 建立、握手与 READY

**R-HS-01** 网络建立仅表示传输可用。主动端先发送选定 Slot 的 Handshake；被动端创建/复用 Peer，回送本地 ID 和相同 Slot 信息。

**R-HS-02** 验证 magic/version、非零且非自身的远端 ID、slotCount、slotId 范围；主动端额外验证预期 Peer/Slot。已存在 Peer 的 slotCount 不匹配必须拒绝新连接，不修改旧 Peer。

**R-HS-03** 只有本地握手已被网络接纳、远端握手已验证且 Slot 绑定成功后才 READY。被动端必须先接纳握手回复再发布 Slot，保证后续业务帧不会先于握手发送。无需额外 ACK。

**R-HS-04** 重复握手、READY 前 Request/Response/Heartbeat、Slot 冲突和握手超时均结束候选连接。超时和握手完成必须竞争同一完成权。

**R-HS-05** 节点 ID 交换不是认证。V1 内部网络信任边界由部署保证，不能把被动 Peer 自动创建当作授权。

## 5. Slot 并发

**R-SLOT-01** 绑定以“空 → 候选连接”原子竞争，首个成功者获胜；重复连接不得踢掉现有连接。

**R-SLOT-02** 解绑必须校验具体连接身份；旧连接晚到的断开不能清除替代连接。

**R-SLOT-03** 绑定需检查节点仍运行、Peer 仍为当前对象、候选仍有效。关闭/移除竞争使候选失效时，不能遗留新绑定或新 Peer。

Slot 是传输细节；来源 Slot 仅作为 reply 提示，不写入业务 body、Metadata 或 Runtime Route。

## 6. 发送选择

**R-SEND-01** call/notify 的 routeKey 非零时起点为 unsigned64(routeKey) mod slotCount；为零时使用 Peer 内 round-robin。reply 先尝试 Request 实际到达的来源 Slot；失败后使用调用方传回的 routeKey（或 round-robin）选起点，环形扫描并跳过已试过的来源 Slot。requestId 不参与路由。

**R-SEND-02** 环形扫描最多一圈，跳过空、非 READY、inactive、不可写连接。未接纳的同一帧可以交给下一候选；首个 ACCEPTED 后立即停止。

**R-SEND-03** ACCEPTED 只表示本地网络接纳，不保证交付/执行。接纳后即使发生异步写失败、断线或超时，也不得补发该业务帧。重连只恢复通道。

**R-SEND-04** notify/reply 只返回 ACCEPTED、PEER_NOT_FOUND、UNAVAILABLE。背压、无 READY 连接均归为 UNAVAILABLE，不排队等待。call 另有语言绑定配置的有限 Node 级接纳上限，覆盖编码、在途调用及尚未返回的完成通知。容量耗尽时释放已接管 body，立即交付本地 UNAVAILABLE，不编码、不发送、不等待、不重试。此上限不拒绝 notify/reply。

例：routeKey 指向 slot2，但 Request 由 slot3 回退发送。正常 reply 优先 slot3；slot3 不可用后再从 routeKey 指向的 slot2 开始回退。来源 Slot 上的新连接可以承接回复，不保存原物理连接。

亲和性不保证跨连接全局业务顺序。业务排序和幂等由应用实现。

## 7. PendingCall 与完成

**R-CALL-01** PendingCall 按 Peer 管理，响应可经该 Peer 的任意 Slot 完成，不得跨 Peer 匹配。

**R-CALL-02** 编码成功后、首次网络发送前注册 PendingCall，防止极快响应丢失。注册后重新检查 Peer/Node 有效性。全部发送失败必须撤销并报告失败。

**R-CALL-03** 响应、超时、移除、关闭和发送失败争夺同一完成权，每次调用至多通知一次。交给统一响应处理器之前，必须移除 PendingCall 并取消超时；处理器异常不能重新完成或恢复调用。接纳额度保持到对应完成处理器返回，异常返回同样释放；同步编码、碰撞、写入异常也释放额度。超时业务通知必须在共享维护定时器之外执行，避免单条慢通知阻塞其他调用/握手期限及重连任务。不同调用的通知不保证全局顺序。

**R-CALL-04** requestId 递增，uint32 回绕跳过 0；允许跳号，不编码 Slot、Node 或时间。同一本地 Node 生命周期内，显式删除或被动 Peer 回收不得重置分配，并在同远端 Peer 重建时立即复用旧调用 ID。匹配仍在 Peer 内完成；语言绑定可在 Node 范围分配 ID。碰到仍占用的 ID 必须同步失败，不能覆盖旧调用，也不扫描寻找其他 ID。部署需保证未完成远端回复的寿命远小于完整分配回绕周期；不能识别跨完整回绕周期的极端迟到响应。Wire v1 没有 Node 代际字段，不保证在替换/重启本地 Node 实例后拒绝保存的旧业务回复；此保护需要应用代际校验或另行版本化协议。

**R-CALL-05** 未匹配的 Response 直接丢弃余下帧，不重复通知、不重新建调用；非零 requestId 必须可读。匹配后余下格式损坏时，必须以 PROTOCOL_ERROR 完成已认领调用并关闭连接，不能因已经移除而遗失通知。

**R-CALL-06** 接收侧不做业务去重，没有远端取消协议。Request 业务 body 的解码、响应错误码解释与业务 callback 调用都交给应用统一处理器。

## 8. 时间、生命周期与关闭

维护流程由应用编排：停止新的业务接纳，按应用策略完成或终止业务工作，再关闭 RPC。异常进程退出可能使 close 根本无法执行；同步 close 的保证适用于该操作实际执行并返回的情况。RPC 不能保证进程被突然终止后继续交付完成回调或执行资源清理。

设计理由：启动与最终关闭是低频管理路径。保持资源归属及现有关闭保证清晰，将长期运行中的调用关联、超时和连接恢复放在优先位置。没有测量到维护影响时，一次性全局清理扫描较慢属于低优先级优化。此次部署说明不改变 R-TIME-04，也不允许正常运行中错配响应、丢失恢复或泄漏资源。

**R-TIME-01** call timeout 从网络 ACCEPTED 后开始，以单调时间机制计时。快速响应可以早于超时注册；注册者必须检测调用已结束并取消新建超时。调度/线程负载可延迟交付，不提供硬实时保证。

**R-TIME-02** 单连接断开不立即失败已 ACCEPTED 的 PendingCall；等待其他 Slot 的响应或 timeout。

**R-TIME-03** removePeer 先从当前拓扑移除，再终止会话、重连和未完成调用；未命中幂等。remove 后重新创建是独立生命周期，旧调用不迁移。

示例：A 调用 B 的 command 101；删除/重建后，以后续 ID 调用 command 202。B 仍可经替换连接回复保存的 command 101，但 A 丢弃未匹配的旧 ID，仅由对应新回复完成 command 202。详见 [Java 当前源码审阅](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#91-审阅确认的缺陷与规模风险)。

**R-TIME-04** Node 从 NEW 显式 start 到 RUNNING，最终进入 CLOSED；启动失败也终止实例。close 允许 NEW、幂等且形成同步关闭屏障，开始即拒绝新操作，返回前终止已接纳的 RPC 操作、在途调用、连接、监听、自有维护资源及自有完成通知。并发 close 等待同一次清理。应用另行投递的业务任务不在屏障范围内。禁止从会导致等待自身的执行上下文同步关闭，具体 Java 约束见开发规范。

**R-TIME-05** add/remove/call/notify/reply 仅在 RUNNING 合法。调用开始时已经关闭是生命周期错误；与关闭/移除竞争的已接纳 call 可以收到 NODE_CLOSED/PEER_REMOVED，或由抢先完成的响应/超时结束。

## 9. 心跳与重连

**R-LIVE-01** READY 后 Write Idle 达阈值才发空 Heartbeat；合法完整入站帧刷新 Read Idle。正常业务流量替代心跳活性，不需要 Ping/Pong 或序号。

**R-LIVE-02** Read Idle 达阈值关闭连接；Heartbeat 无法被网络接纳（含背压）也关闭该连接。握手期间忽略 idle 事件，由独立握手超时负责。

**R-LIVE-03** addPeer 对空 Slot 立即首次连接。失败、握手失败或稳定连接断开后，等待正基础延迟与每次重新均匀抽取的 [0, 配置抖动] 附加延迟之和再重试。默认值与时间粒度由语言绑定定义；抖动为 0 保留固定延迟行为。同 Slot 只维护一条恢复链，涵盖延迟、建连和握手；不同 Slot 独立。Peer removal/Node close 后旧任务自然失效。没有指数退避、最大重试或业务重发。抖动分散尝试，但不提供全局建连速率限制。

Java 实现状态：后续审阅已复现恢复停止与解绑竞争，主动 Peer 可能留下空 Slot 且没有持续恢复链。这仍是实现缺陷，不是 R-LIVE-03 的例外。详见 [当前源码审阅](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#91-审阅确认的缺陷与规模风险)。

## 10. 错误边界

| 事件 | 行为 |
| --- | --- |
| 参数/生命周期错误 | 同步拒绝，body 仍属调用方 |
| 编码或 ID 冲突 | 同步异常，已接管的 body/frame 回收 |
| call 无 Peer/不可用/超时/移除/关闭 | 本地 onFail |
| 任意合法远端 errorCode | 统一 onResponse，由应用解释 |
| onRequest 抛普通业务运行异常 | 诊断；Call 尝试空 HANDLER_ERROR Response；Notify 不回复；连接保留 |
| onResponse/onFail 抛普通运行异常 | 诊断，至此结束，不再通知、不关闭连接 |
| malformed Wire/错误握手 | 关闭当前连接；已认领损坏响应的调用先失败 |
| 未知非零 command/业务 body 无法解码 | 由应用处理器决定 |

自动 HANDLER_ERROR 响应不包含异常文本、堆栈或 Metadata。框架错误编号以 Core 为唯一来源。

## 11. 已确认取舍与验证

| 取舍 | 理由 | 重新评估条件 |
| --- | --- | --- |
| 固定 Header 与单纯 Slot 亲和性 | 易于跨语言互操作，失败路径短 | 测量证明 header 成本显著 |
| Peer 级完成与无业务重试 | 支持跨 Slot 回复，避免重复执行 | 新的明确幂等/可靠交付协议 |
| 被动自动创建/回收 | 单边配置可双向通信 | 有独立节点授权或拓扑需求 |
| 统一应用处理器 | RPC 不承担业务 codec/Runtime | 专用外围接入模块需求 |
| 基础重连延迟与有界抖动 | 分散同时恢复，保留可配置最小延迟；零抖动恢复固定延迟 | 测量证明需要全局建连接纳或指数退避 |

Java 验证入口：[RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java)、[RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java)、[RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java)。实现细节、默认值与示例见 Java 开发规范。

保留的测试描述本地真实 TCP 与可控并发覆盖，但当前 RPC 套件无法编译。当前诊断复现及契约偏差记录于 Java 规范 9.1，历史通过结果不代表当前源码已验证；尚未验证跨语言对接、生产容量、长时间 ID 回绕和公网部署。RPC→Runtime 自动接入、TLS/WS RPC Builder、服务发现不在本实现范围。
