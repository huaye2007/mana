# OGBS RPC Specification 1.0

[English](OGBS-RPC-1.0.md) | **[简体中文](OGBS-RPC-1.0.zh-CN.md)**

文档类型：**标准规范（语言无关）**。组件 game-rpc。Java 参考实现已提供；生产容量与跨语言互操作尚未验证。

配套：[Java 25 开发规范](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)、[RPC Wire Profile](../rpc-wire.zh-CN.md)。
规范性依赖：[Network](OGBS-Network-1.0.zh-CN.md)、[Core](OGBS-Core-1.0.zh-CN.md)。

## 1. 范围

RPC 用于内部服务器节点的直接通信，提供 call、notify、reply、固定多连接 Slot、握手、心跳、重连与本地调用完成管理。

不解析业务 body，不提供业务协议注册、Runtime 调度、服务发现、Router、持久投递、自动业务重试或远端取消。成功收到响应不等于业务 exactly-once；超时不撤销远端执行。

## 2. 对象与标识

| 对象 / 字段 | 定义 |
| --- | --- |
| RpcNode | 本地 RPC 端点，显式启动，最终关闭 |
| nodeId | 非零 uint32 节点标识 |
| RpcPeer | 与本节点直接通信的一个远端关系 |
| ConnectionSlot | Peer 内固定逻辑连接位置 |
| requestId | Peer 内 uint32 调用标识；0 为 Notify |
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

**R-SEND-04** notify/reply 仅返回 ACCEPTED、PEER_NOT_FOUND、UNAVAILABLE。背压和无 READY 统一为 UNAVAILABLE；不排队等待连接。call 的对应运行期失败经统一本地失败通知交付。

例：routeKey 指向 slot2，但 Request 由 slot3 回退发送。正常 reply 优先 slot3；slot3 不可用后再从 routeKey 指向的 slot2 开始回退。来源 Slot 上的新连接可以承接回复，不保存原物理连接。

亲和性不保证跨连接全局业务顺序。业务排序和幂等由应用实现。

## 7. PendingCall 与完成

**R-CALL-01** PendingCall 按 Peer 管理，响应可经该 Peer 的任意 Slot 完成，不得跨 Peer 匹配。

**R-CALL-02** 编码成功后、首次网络发送前注册 PendingCall，防止极快响应丢失。注册后重新检查 Peer/Node 有效性。全部发送失败必须撤销并报告失败。

**R-CALL-03** 响应、超时、移除、关闭和发送失败争夺同一完成权，每次调用至多通知一次。交给统一响应处理器之前，必须移除 PendingCall 并取消超时；处理器异常不能重新完成或恢复调用。

**R-CALL-04** Peer 内 requestId 递增，uint32 回绕跳过 0；允许跳号，不编码 Slot、Node 或时间。碰到仍占用的 ID 必须同步失败，不能覆盖旧调用，也不扫描寻找其他 ID。部署需保证调用寿命远小于完整回绕周期；不能识别跨完整回绕周期的极端迟到响应。

**R-CALL-05** 未匹配的 Response 直接丢弃余下帧，不重复通知、不重新建调用；非零 requestId 必须可读。匹配后余下格式损坏时，必须以 PROTOCOL_ERROR 完成已认领调用并关闭连接，不能因已经移除而遗失通知。

**R-CALL-06** 接收侧不做业务去重，没有远端取消协议。Request 业务 body 的解码、响应错误码解释与业务 callback 调用都交给应用统一处理器。

## 8. 时间、生命周期与关闭

**R-TIME-01** call timeout 从网络 ACCEPTED 后开始，以单调时间机制计时。快速响应可以早于超时注册；注册者必须检测调用已结束并取消新建超时。调度/线程负载可延迟交付，不提供硬实时保证。

**R-TIME-02** 单连接断开不立即失败已 ACCEPTED 的 PendingCall；等待其他 Slot 的响应或 timeout。

**R-TIME-03** removePeer 先从当前拓扑移除，再终止会话、重连和未完成调用；未命中幂等。remove 后重新创建是独立生命周期，旧调用不迁移。

**R-TIME-04** Node 从 NEW 显式 start 到 RUNNING，最终进入 CLOSED；启动失败也终止实例。close 允许 NEW、幂等且形成同步关闭屏障，开始即拒绝新操作，返回前终止已接纳的 RPC 操作、在途调用、连接、监听和自有维护资源。并发 close 等待同一次清理。应用另行投递的业务任务不在屏障范围内。禁止从会导致等待自身的执行上下文同步关闭，具体 Java 约束见开发规范。

**R-TIME-05** add/remove/call/notify/reply 仅在 RUNNING 合法。调用开始时已经关闭是生命周期错误；与关闭/移除竞争的已接纳 call 可以收到 NODE_CLOSED/PEER_REMOVED，或由抢先完成的响应/超时结束。

## 9. 心跳与重连

**R-LIVE-01** READY 后 Write Idle 达阈值才发空 Heartbeat；合法完整入站帧刷新 Read Idle。正常业务流量替代心跳活性，不需要 Ping/Pong 或序号。

**R-LIVE-02** Read Idle 达阈值关闭连接；Heartbeat 无法被网络接纳（含背压）也关闭该连接。握手期间忽略 idle 事件，由独立握手超时负责。

**R-LIVE-03** addPeer 对空 Slot 立即首次连接。失败、握手失败或稳定连接断开后固定延迟重试，同 Slot 只维护一条恢复链，涵盖延迟、建连和握手；不同 Slot 独立。Peer removal/Node close 后旧任务自然失效。没有指数退避、抖动、最大重试或业务重发。

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
| 固定重连延迟 | 内部节点恢复行为简单确定 | 生产规模测试证明需控制重连风暴 |

Java 验证入口：[RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java)、[RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java)、[RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java)。实现细节、默认值与示例见 Java 开发规范。

已覆盖本地真实 TCP 与可控并发场景；尚未验证跨语言对接、生产容量、长时间 ID 回绕和公网部署。RPC→Runtime 自动接入、TLS/WS RPC Builder、服务发现不在本实现范围。
