# OGBS Game RPC Specification

**Version:** Draft 1.6  
**Revision date:** 2026-09-12  
**Status:** 实现标准草案，尚未冻结

本文定义语言无关的消息格式与 RPC 行为。MUST / MUST NOT 表示必须遵守，SHOULD 表示建议。各语言可以使用不同的类、容器和线程机制。Java API 与资源管理细节见 [Java 实现文档](java/Java%20Implementation%20Specification.md)，项目入口见 [README](../README.md)。

本次修订移除 Core 的 Router、服务发现路由查询、自动转发与自动解包职责。本稿新增 PING/PONG 控制帧，原有消息布局不变；旧规范的内置 Router 行为不再适用。文档版本不进入 Wire。

## 1. 职责边界

game-rpc 负责已登记节点之间的 RPC 通信：

- 管理 NodeId → RpcPeer 映射及相邻节点的物理连接数组。
- 统一启动 TCP 监听和客户端，管理身份/Slot 握手、物理连接心跳、重连和关闭。
- 完整消息编解码、Call、Send、显式回复、请求响应匹配和超时通知。
- 连接选择、背压、消息大小及待完成调用等资源限额。
- Metadata 字节传输和诊断。

Core 不认识 Router 服务，不存储下一跳、业务服务位置或转发表，不查询业务路由，不自动封装、转发或解包业务信封。业务协议对象、序列化、业务分发、压缩、加密、签名、服务发现和跨节点转发由使用方决定。Core 不恢复业务线程、Actor、协程或执行上下文。

发送 API 的 targetNodeId MUST 指向已登记的直接相邻 Peer。节点未知、已移除或没有可用连接时快速失败，不隐式创建目标 Peer，不等待服务发现，不尝试通过其他节点发送。收到任何消息也不能根据其业务头声明自动创建 Peer。

routeKey 只用于在这个目标 Peer 的连接数组中选择 Slot，与 Router 或下一跳查询无关。

## 2. Node、Peer 与 Connection

一个 RpcNode 包含本地 nodeId、NodeId → RpcPeer 映射和节点自有运行资源。所有物理连接都可双向发送和接收，使用同一 RPC 消息接收入口。

一个 RpcPeer 代表一个登记的相邻节点，包含固定长度 connections[] 和该目标节点的 pendingCalls。 建连地址、重连任务和连接就绪回调属于连接管理状态，不要求放在逻辑 Peer 中；仅接受连接的节点无需分配主动重连数据。Peer 不持有另一个 Peer 作为路由，不借用其他节点的连接数组。节点可以因为主动 connect 或合法的入站握手创建 Peer；登记不等于所有连接已就绪。

入站握手新建的 Peer 在任何 Slot 成功前属于临时登记；所有候选都失败且没有主动连接配置时，必须回滚登记并归还 Peer 名额。只要任意一个 Slot 曾完成握手，之后普通断线或其他 Slot 握手失败均保留该 Peer。首次 Slot 成功前不登记 PendingCall，Call 快速返回 UNAVAILABLE。 准入检查和握手编码不应持有节点级连接管理锁；候选先预留身份，外部处理完成后、提交握手消息前以及发布就绪前，重新检查期限与所属生命周期。已移除或失效的候选不得重新发布为就绪连接。

Connection 是物理连接。connectionId 是不透明字符串，每次物理重连都获得新 ID，不编码 nodeId、Slot 或 requestId。RPC 不要求在网络 Connection 实现中增加请求计数器、PendingCall、业务消息或回复状态。可以通过网络提供的属性机制关联 RPC 握手身份。 握手临时状态与已验证连接身份应有明确生命周期：握手完成后释放计时任务及尝试状态，保留所属 Peer 实例和 Slot 用于后续收发、重连隔离。标准不要求额外的 Binding 包装对象。

| 事件 | 行为 |
| --- | --- |
| 普通断连/重连 | 保留 Peer 与 PendingCall；新连接填回原 Slot，不自动重发请求 |
| 显式移除 Peer | 旧 Peer 拒绝新调用，未完成调用以 UNAVAILABLE 结束，取消建连/握手/心跳/重连任务并清理连接 |
| 再次加入 | 后续 connect 或合法入站握手创建新 Peer；旧回调不得复活旧实例或清理新实例 |
| RpcNode 关闭 | 拒绝新调用，结束所有 PendingCall，清理自有连接、监听、客户端及计时资源 |

解码完成后，分发准入前必须重新确认物理连接和 Peer 实例仍有效；解码期间发生移除的帧不得进入业务分发或触发协议错误响应。已通过分发准入的处理可以继续，移除不强制中止业务代码。响应关联使用该接收连接所属的 Peer 实例，不能切换到同 nodeId 的新生命周期。

同步关闭应在修改状态前检查调用线程能否等待自有运行资源；不得在时间轮任务中停止同一个时间轮，也不得持有生命周期锁等待时间轮线程退出。回调线程切换仍由使用方负责。

关闭或移除时，单个连接关闭失败不得跳过其余连接和未完成调用的收尾；节点关闭还须继续释放自有监听、客户端和计时资源。清理异常在完成收尾后报告。连接就绪回调的等待者必须有数量上限，达到上限明确拒绝新登记，不允许离线节点积累无限等待者。

普通断连不等于移除；持续禁止某 nodeId 接入由准入规则负责。移除不是永久黑名单。共享资源的关闭由其所有者决定。Core 不提供隐式优雅排空或本机消息直通路径，本机调用也使用 TCP。

## 3. 固定连接数组

Slot 只是 connections[] 的下标，没有主从关系。数组长度在这个 Peer 生命周期中固定，断线不能压缩数组。两个节点对可以使用不同的连接数量；同一个节点对两端的数量必须匹配。

默认选择规则：

- 非零 routeKey 在完整固定数组上做稳定 hash；不能先过滤坏连接再 hash。
- 命中的 Slot 未就绪或断开返回 UNAVAILABLE，不可写返回 OVERLOADED，不向其他 Slot 扩散。
- routeKey=0 在可用、可写的连接间轮询。
- 可配置自定义选择器，但只能返回这个 Peer 的已验证连接，不能绕过可写状态和生命周期检查。

在相同连接上的提交顺序遵循网络实现契约；不保证跨连接顺序，也不保证多个并发调用线程之间的业务先后顺序。任何发送结果不确定的请求都不能由 Core 自动重放。

## 4. 消息模型

消息对象只包含协议数据，Wire 的类型标记属于 Codec，不要求对象持有 msgType 字段。不存在固定 flags 字段，可选业务标识使用 Metadata。

| 消息 | 数据 |
| --- | --- |
| Request | command、requestId、routeKey、businessId、businessIdType、Metadata、原始 body |
| Response | requestId、errorCode、Metadata、成功 body 或错误参数字符串数组 |
| RouteMessage | sourceNodeId、targetNodeId、原始 inner 字节 |
| Handshake | 控制类型、sourceNodeId、targetNodeId、slotIndex、connectionCount |
| Heartbeat | PING/PONG 类型、sequence |

Request 不保存 Node、Connection、逻辑来源节点、响应类型、回复标记、回调或执行上下文。相邻来源从接收连接的握手身份获得。Response 必须保留，不能用裸业务对象替代。

RouteMessage 仅是可由业务使用的原始信封格式。Core 只编解码其外层，原样交付接收入口；不检查声明来源是否可信，不比较其目标与本机，不读取 inner 类型、Metadata 或 body，不自动完成其中的 Response，也不决定下一跳。信封地址、内层格式和业务安全检查由使用方解释。它不产生新的 Core PendingCall 或隐式节点。

请求和信封交付同一消息入口。外层 Response 由 Core 按相邻 Peer 和 requestId 匹配结果，握手和心跳由 Core 消费。不存在自动生成的“已解包转发请求”对象。

## 5. Call、Send 与显式回复

Call 先查找目标 Peer，登记 PendingCall、设置截止时间，然后选择该 Peer 的连接并发送 requestId>0 的 Request。PendingCall 只保存关联结果所需的状态，不保存请求 body、Metadata、业务类型或请求对象。

Send 通知使用 requestId=0，不登记 PendingCall，不推进请求计数器。显式完整消息发送也只发送调用者提供的消息，不自动登记调用或改写其 requestId、地址及其他头字段。

Handler 返回不自动回复；业务自行决定何时提交完整 Response，并填写原请求的 requestId。因此可以先发送通知，再回复，再发送其他通知。Handler 抛异常只报告诊断，不擅自构造业务响应。重复回复由调用者避免；Core 的单次结果完成保证不等于入站请求去重。

显式回复和自动协议错误响应均沿接收 Connection 提交。自动错误响应不能重新根据错误帧的 routeKey 选择 Slot。原连接断开后，可以定位同一 Peer 生命周期、同一 Slot 的已握手替换连接；不可用则失败，不因背压换槽。旧 Peer 移除后，旧 Connection 的身份不能用于新 Peer 的回复。

调用者显式发送 RouteMessage 时，其 inner 中即使编码了 Request/Response，Core 也不为其建立端到端关联。跨节点业务转发和结果关联不属于本标准的自动能力。

## 6. RequestId 与完成竞争

requestId 为正 int32；0 永久表示 Send。不存在独立 Request Sequence、Slot 序号数组、Epoch 或编码到 requestId 内的节点/连接信息。

同一个 RpcNode 的所有 Peer 共享原子计数器。初始为 0，第一次 Call 分配 1；达到 2147483647 后下一候选从 1 开始，不能溢出到负数。若候选已被当前目标 Peer 的 PendingCall 占用，继续寻找，不覆盖旧调用。达到配置的 pending 上限时有界拒绝。 对同一 Peer 的生命周期检查、容量检查、编号冲突检查及登记必须是一个原子操作，与 Peer 退役互斥；调用方不应依赖外部持锁约定完成登记。

Peer 移除/重建、物理重连均不重置节点计数器；PendingCall 仍归属具体目标 Peer。未发生完整编号回绕时，Peer 重建不会重用旧调用 ID。跨节点双方各自分配，不协商计数器。

响应只能根据已验证相邻节点找到 Peer，再按 requestId 匹配该 Peer 的 PendingCall。不能用信封声明来源匹配其他 Peer 的调用。未知、迟到或重复响应丢弃并记录诊断。

单次调用状态由所属调用管理方持有；不反向引用 Runtime/Node/Peer，不自行注册或删除 Pending，不决定线程调度及诊断。连接管理与编解码将事件交给调用管理方统一处理。编号分配保持节点级作用域，Pending 表按目标 Peer 分属，单次调用状态只需保存请求关联、期限、完成状态与回调。

响应、超时、发送失败、移除和关闭竞争唯一完成权。成功竞争者删除相同 PendingCall 实例并取消计时任务，随后通知一次。删除不能仅按可复用的 ID 执行；用户回调抛异常不能导致再次完成或影响其他调用。 网络提交不得持有调用完成锁，以免同步回入的消息或结果在发送锁内调用业务。同步回入仍按原消息顺序直接通知，不需要内部回调线程池。

跨 RpcNode 运行实例重启、同一 nodeId 复用以及完整编号回绕后的旧消息隔离仍依赖部署。本标准没有永久去重或跨重启唯一 ID 保证。

## 7. 超时

默认 Call 超时必须明确配置，可由单次调用选项覆盖。Duration 必须有限且大于零，不能在换算或计算截止时间时溢出。

截止时间来自单调时钟，从 Call 进入开始计时；不能在编码或连接选择后重新开始。登记后必须在执行可扩展 Codec/选择器前安装超时任务。最终网络提交前以及响应完成前都重新检查 deadline，计时任务延迟不能使过期调用得到成功或补发请求。 发送准入与完成互斥：超时或移除先发生则拒绝发送；通过准入后属于在途写入，后续超时或移除不保证撤回该写入。结果完成权仍只有一次。

超时先释放 PendingCall，再在检测到超时的线程直接通知结果。RPC 不使用内部线程池调度业务回调；由业务层在回调中投递自己的执行线程。结果、连接和诊断回调应及时返回，不在网络或计时线程中执行阻塞业务。移除和关闭先清理状态与资源，再在调用线程、生命周期锁外通知。超时不意味着远端取消执行，也不能触发自动重发。

## 8. 默认 Wire

所有整数均为有符号、大端、固定宽度，无填充。框架消息中没有协议版本字段。

| 字段 | 宽度及约束 |
| --- | --- |
| nodeId、sourceNodeId、targetNodeId | int32，允许完整有符号范围 |
| command | 有符号 int32，必须非零；正负均可，业务可约定负数用于服间协议 |
| requestId | int32，Request ≥0，Response >0 |
| errorCode | int32，0 成功，正数错误，负数非法 |
| routeKey、businessId | int64，允许负数 |
| businessIdType | int8，0～127；0 表示 NONE 且 businessId 必须为 0 |
| msgType | int8，仅存在于 Wire |
| Metadata Key | int16，1～32767 |
| Metadata Value 长度 | int8，0～127 |
| Frame/Metadata 总长度、Slot、连接数量 | int32，按下述边界检查 |

不能用无符号转换把非法负数解释为正数。businessIdType=1 保留为 ROLE，2～31 为 Core 保留，32～127 为项目范围；Core 不查询业务身份。

TCP 消息为 `[frameLength:int32][Frame]`。frameLength 只包含 Frame，不含自己的 4 字节，必须 >0 且不超过接收限额。一个 TCP 读取可以包含半帧或多帧，必须先完整分帧，再调用 RPC Codec。

### 8.1 Request：msgType=1

| Frame 偏移 | 字段 | 字节数 |
| --- | --- | --- |
| 0 | msgType=1 | 1 |
| 1 | command | 4 |
| 5 | requestId | 4 |
| 9 | routeKey | 8 |
| 17 | businessId | 8 |
| 25 | businessIdType | 1 |
| 26 | metadataLength | 4 |
| 30 | Metadata | metadataLength |
| 30 + metadataLength | body | Frame 剩余字节 |

固定头为 30 字节，body 可以为空，不额外编码 bodyLength。requestId=0 为通知，不应回复。

### 8.2 Response：msgType=2

| Frame 偏移 | 字段 | 字节数 |
| --- | --- | --- |
| 0 | msgType=2 | 1 |
| 1 | requestId | 4 |
| 5 | errorCode | 4 |
| 9 | metadataLength | 4 |
| 13 | Metadata | metadataLength |
| 13 + metadataLength | body | Frame 剩余字节 |

固定头为 13 字节。不重复编码 command，业务对象类型不由 Core 推断。所有响应都可携带合法 Metadata。

errorCode=0 为成功，body 为原始业务字节，可为空；errorCode>0 为错误，body 区域统一承载有序的错误参数字符串数组，不再承载业务字节。参数格式如下，所有长度均为大端有符号 int32：

```text
[valueLength:int32 | UTF-8 value bytes]
[valueLength:int32 | UTF-8 value bytes]
... 直到 body 结束
```

不增加参数数量或额外头字段。valueLength 是 UTF-8 字节数，必须非负且不超过剩余 body。0 表示一个空字符串；空 body 表示零个参数，两者不同。字符串不得为 null，必须为合法 Unicode/UTF-8，不允许替换非法字符后继续传输。参数总字节数包含每项的 4 字节长度，受现有 body 和完整消息长度上限约束。

错误参数解码必须验证每项长度、截断、非法 UTF-8 和尾部残留数据；这些情况属于畸形 Response。无参数错误帧与旧版完全一致；带参数错误帧需要双方升级，旧版解码器会拒绝非空错误 body。

### 8.3 原始信封：msgType=3

`[3:int8][sourceNodeId:int32][targetNodeId:int32][inner bytes]`

外层头为 9 字节，inner 必须非空；整个 Frame 仍受大小限额约束。inner 不包含 TCP 长度前缀。Core 不要求 inner 必须能解码为某种 RPC 类型，业务可以传递不透明字节。

该格式只定义字节布局与完整数据交付，不代表 Core 支持 Router。只有业务主动发送才产生出站消息。

### 8.4 握手控制帧

`[kind:int8][sourceNodeId:int32][targetNodeId:int32][slotIndex:int32][connectionCount:int32]`

帧固定 17 字节，无 Metadata/body。kind：HELLO=4、ACK=5、REJECT_DIRECTION=6。connectionCount>0，0≤slotIndex<connectionCount。控制帧不分配 requestId，不创建 PendingCall，不交付业务入口。普通显式发送入口不能发送握手帧。

### 8.5 心跳控制帧

`[kind:int8][sequence:int32]`

帧固定 5 字节，无 Metadata/body。PING=7、PONG=8；sequence 必须为正 int32，是单条物理连接的探测序号，不是 RequestId。发起方从 1 递增，达到 2147483647 后从 1 继续；新物理连接从 1 开始。PONG 原样回显对应 PING 的 sequence。心跳不分配 RequestId、不创建 PendingCall，不交付业务入口，普通显式发送/回复入口不能提交心跳。

包含 TCP 长度前缀时，每个心跳控制帧为 9 字节，完整一次 PING/PONG 为 18 字节，不含 TCP/IP 协议开销。自定义 Codec MUST 支持心跳控制消息，与其他完整消息使用同一个 Codec；不能另建绕过编解码的字节发送路径。旧实现需增加类型 7/8 支持后再与启用本规范心跳的节点互通。

## 9. Metadata

Metadata 是连续 TLV 字节，不使用业务类型注册表：

```text
[keyId:int16][valueLength:int8][value bytes]
[keyId:int16][valueLength:int8][value bytes]
```

metadataLength 为所有条目的总字节数，不包含自己的 int32。必须非负且不超出剩余 Frame 或配置限额。Key 必须为正且不重复；Value 长度 0～127，允许空值，不允许 null。Key 1～1023 为 Core 保留，1024～32767 为项目范围，本稿无强制 Key。

Value 的语义由外部 Key 定义确定，Core 校验条目边界、重复 Key 和总长，不根据内容猜测类型。未知 Key 仍完整保留。

基础工具编码约定：Int 为 4 字节大端，Long 为 8 字节大端补码，Boolean 为 1 字节且只允许 0/1，String 为严格 UTF-8。字符串上限按编码字节计算，不能截断多字节字符。Get 缺失字段、长度不匹配、非法 Boolean/UTF-8 必须明确失败，不能静默转换为默认值。

写入前验证完整 Value 与限额；失败不能追加半条数据。缺失 Key、空 Value、0 和 false 含义不同。未知 Key 不能丢弃。选项或消息快照不能被原可写 Metadata 后续修改影响。

## 10. 连接握手与方向约束

RpcNode 同步启动本地监听及连接管理，远端连接在后台建立。每次新物理连接重新握手，握手前不能收发普通 RPC 消息。

主动端提交 HELLO；接收端校验目标、相邻来源准入、Slot 和连接数量，先提交 ACK 再发布连接；主动端收到匹配 ACK 后发布连接。ACK 必须排在接收端后续普通 RPC 输出之前。身份来自握手，握手只是身份声明，不替代部署认证。

握手就绪与业务收发必须使用一致的启动门槛。本地尚未完成启动时，不得向对端承诺 RPC 连接就绪；提前到达的连接可以关闭，由发起方重试。不能先返回成功 ACK，再因为本地仍在启动而丢弃业务消息。

一个不同节点对只能由一方主动发起整个连接数组。已存在入站方向时，反向 connect 必须立即报方向冲突。两边同时配置主动意图，在握手发现冲突时通过 REJECT_DIRECTION 明确拒绝，停止该 Peer 的重试并失败通知待完成连接回调。不能按 nodeId 自动选主，也不能按 Slot 分配双向主动角色。

拒绝后不立即丢弃排队拒绝帧；保持候选至对端关闭或原握手期限结束。拒绝因网络中断丢失时，对端可能继续观察到普通连接失败；已经检测到冲突的一端保持拒绝。修正方向需要移除旧 Peer 后重新连接。本机 TCP 回环两端同属一个节点，接受端不覆盖主动发送 Slot。

同方向的同一 Peer/Slot 只能有一个活动候选；重复握手、错误身份、无效 Slot/count 或提前业务帧关闭该连接。本机 TCP 回环也必须执行同方向重复检查，每个 Slot 仅容纳一个主动端和一个接受端。本机入站身份必须有对应的本机主动 Peer 和正在进行的 Slot 建连尝试，不能通过声明本机 nodeId 绕过连接限额。

握手有独立单调时钟 deadline；准入检查、ACK 编码/提交可能耗时，发布前须复查期限、节点和连接状态。计时任务延迟不能发布过期连接。

未完成的入站和出站握手共享节点级并发上限。达到上限立即关闭新候选，不登记其握手定时任务；成功、失败、断连、超时和关闭恰好释放一次名额。已就绪连接不占握手名额。普通连接失败及限额拒绝按配置延迟重试，方向冲突不重试。

### 10.1 物理连接心跳

每个已握手的物理连接由建立 TCP 连接的一方主动发送 PING，接收方仅在原物理连接回复 PONG。即使没有业务消息也执行探测；本机 TCP 回环仍只有主动端发送 PING。每条连接最多一个未确认的探测，不借用 RpcFuture 或业务 RequestId。

默认首次探测在握手成功 5 秒后；收到有效 PONG 后等待 5 秒发送下一次 PING。每次探测等待有效 PONG 的超时默认 15 秒，从开始探测（编码、写队列等待之前）计时，使用单调时钟。间隔及超时由主动端配置，不需要两端保持一致；接收方不启动主动探测或独立的无 PING 超时任务。接收端可显式启用网络层的读空闲超时来清理半开连接；默认不因 idle 事件关闭连接。此机制不增加 RPC 定时任务，也不要求接收方反向发起 PING。启用时，读空闲期限应覆盖发起方心跳间隔、等待 PONG 的时间及调度/网络余量；网络收到数据即可重置读空闲，不等同于验证了有效心跳。

只有同一物理连接上匹配当前 sequence、且截止时间之前收到的 PONG 可以确认探测；普通业务流量、未知/重复/迟到 PONG 不能续期。接收端不能反向发起 PING，主动端不应收到 PING，方向错误关闭该连接。

无有效 PONG 到期时只关闭当前物理连接并进入已有 Slot 重连流程；保留逻辑 Peer 与 PendingCall，不重放业务请求、不改走其他 Slot。不可写也不换槽，等待本次探测期限后关闭；编码失败或连接失效直接关闭。心跳任务、迟到回复与旧连接关闭均不能影响替换连接或新 Peer 生命周期。断连、Peer 移除、Node 关闭必须取消对应心跳任务。

## 11. 资源、背压与所有权

实现必须约束 Frame、Metadata、body、每 Peer PendingCall、节点 Peer 数量、未完成握手数量。game-rpc 不额外维护每连接待发送字节限额。未知目标发送不能通过创建任意 Peer 绕过限额。配置默认值由语言实现公布。 消息字段规则及节点配置必须由统一消息入口执行，不能因替换 Codec 或选择完整消息重载而绕过；格式 Codec 负责字节布局和截断等格式校验。此规则不要求解释业务 body 或信封内层。

发送先检查目标和连接可用性，明显不可用或背压时不编码。编码后再次检查生命周期、deadline 和连接可写状态。底层传输拒绝必须报告，RPC 不额外创建业务消息等待队列，也不通过自建字节预算控制发送。

传输接受表示消息已取得发送资源，不代表对端收到或业务执行成功。提交后的原生写失败关闭异常连接并诊断，PendingCall 等响应、超时或生命周期结束，不自动重放。

编码接口不能消费或改变调用者输入索引；返回输出必须有明确所有者。入站二进制视图可借用网络帧，但借用只在同步处理期间有效。上层异步持有必须复制或取得独立引用，并承担释放责任。Metadata 的独立存储与具体引用规则由语言 Binding 明确。

默认即时刷新。高消息量部署可以显式启用有界刷新合并，必须保证孤立消息、读批次结束、容量阈值和关闭时最终刷新，不能永久滞留。

主动建连的首次尝试立即调度。普通连接失败、握手失败或已建立连接断开后，各 Slot 独立执行有上限的指数退避并加入随机偏移。只有对应 Slot 的握手成功才重置退避；其他 Slot 的状态不影响它。方向冲突停止重试，移除/关闭取消待重试任务，重复 connect 不绕过已安装的等待任务。重连不重放业务消息。

## 12. 错误与异常

| code | 名称 | 含义 |
| --- | --- | --- |
| 0 | 成功 | 不创建错误对象 |
| 1 | NO_HANDLER | 上层没有处理此业务消息 |
| 2 | DECODE_ERROR | 上层业务消息解码失败 |
| 3 | INTERNAL_ERROR | 内部错误 |
| 4 | OVERLOADED | 连接不可写或资源容量不足 |
| 5 | UNAVAILABLE | 节点未运行、目标未知/移除、连接不可用 |
| 6 | TIMEOUT | Call 截止时间已到 |
| 7 | PROTOCOL_ERROR | 非法框架消息 |

1～1000（含边界）全部保留给 game-rpc；表中的 1～7 为当前已定义编号，8～1000 留给后续内部错误。业务错误使用 1001～2147483647，不得占用内部区间。0 表示成功，负数非法。

框架和业务统一使用含 code、message 的不可变错误对象，code 是错误身份；同 code 的不同说明仍代表同一种错误。message 为本地说明，不加入 Wire；网络仍只传 int32 errorCode 和既有字符串参数数组，不增加字段。收到合法正错误码时，回调必须提供错误对象；未知业务码或未知保留码也应原样保留，不能转换为 PROTOCOL_ERROR，本地没有说明时使用空字符串。不要求全局错误注册表，不增加 Origin。

收到合法错误响应时，调用以失败完成，但通知必须保留完整 Response，包括 errorCode、Metadata 和有序字符串参数；本地超时或连接失败没有远端 Response。错误文案、占位符替换和多语言处理属于业务层，RPC 不解析参数含义。重复或迟到的错误响应遵循同一套 Call 完成规则，不能重复通知。

| 情况 | Core 动作 |
| --- | --- |
| API 参数非法 | 同步失败；不先执行回调或网络发送 |
| 完整外层 Request 合法 | 交付完整请求，不要求 command 预注册 |
| Handler 或结果回调抛异常 | 记录诊断，隔离异常，不自动重试或补发回复 |
| 能安全取得关联信息的畸形外层 Request | requestId>0 时可返回 PROTOCOL_ERROR；通知不回复 |
| 能安全关联的畸形外层 Response | 对应调用以 PROTOCOL_ERROR 完成 |
| 未知/截断外层帧且无法安全关联 | 丢弃并诊断；不能猜测 requestId |
| 错误 TCP 长度、未完成握手时非法帧 | 关闭连接 |
| 成功 Response 在编码阶段失败 | 可以经同一 Codec 尝试一次空 body INTERNAL_ERROR；已提交消息不能补发 |
| RouteMessage 的 inner 非法 | Core 不解码、不自动回复，交给业务处理 |

## 13. 验收

各语言必须验证：

- 标准字节布局、整数边界、半帧/合帧、长度上限和 Metadata TLV。
- 业务错误码原样保留；错误参数空数组、空字符串、Unicode、长度/UTF-8 非法帧及回调一次完成。
- Call/Send/显式回复、未知 command 交付、通知无 Pending、同 Peer 响应关联及异常隔离。
- 编号回绕、占用跳过、Peer 重建不重置节点计数器、迟到响应和一次完成。
- 慢编码/选择器、计时器延迟、关闭/移除竞争、禁止过期补发。
- 握手身份、并发连接方向冲突、Slot 固定、重连、回环 TCP、并发握手限额及资源回收。
- 未知 NodeId 快速失败且不创建 Peer；信封地址不影响 NodeId → Peer 映射。
- 信封原样交付，Core 不自动转发、不解码 inner、不完成其中的响应。
- Codec 扩展、输入/输出引用所有权、连接可写状态、可选刷新合并和孤立消息可达。

共享 [Wire 样例](OGBS%20Game%20RPC%20v1%20wire%20vectors.json) 继续作为默认编码的逐字节样例。样例中历史命名的 routed_call/routed_response 只表示信封编码，不代表 Core 执行业务转发。生产吞吐与跨语言互通须单独测量，功能测试通过不能替代压测。
