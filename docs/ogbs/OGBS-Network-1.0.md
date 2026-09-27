# OGBS Network Specification 1.0

文档类型：**标准规范（语言无关）**。对应实现规范：[OGBS Network Java 开发规范](OGBS-Network-Java-25-Specification-1.0.md)。

状态：V1 Draft，按最新 Network 讨论修订。Java 实现与验证入口见 [Java 25 开发规范](OGBS-Network-Java-25-Specification-1.0.md)。

## 1. 职责与范围

OGBS Network 定义可靠、有序、双向连接的传输建立、消息接纳、所有权、背压与生命周期。V1 提供 TCP、TLS TCP、Binary WebSocket 和 WSS；不提供 HTTP 应用服务、UDP、KCP、QUIC。

Network 不理解 RPC 握手、玩家、登录、认证、Session、业务连接映射、连接池、重连、心跳或 Runtime 调度。上层协议在 Network Connection 建立后继续自己的握手。Network active 不等于 RPC ready。

本规范不要求其他语言使用 Netty、Java 泛型、EventLoop 或特定缓冲区类型。Java 类型、默认参数和资源机制只在配套 Java 开发规范定义。

## 2. 连接建立

**N-EST-01** Connection 只在当前 Transport 完整建立且应用收发流水线初始化成功后创建。建立前的 socket/channel 不是 Connection；不暴露 CREATED、HANDSHAKING、READY 等公共状态。

| Transport | 创建 Connection 前必须完成 |
| --- | --- |
| TCP | 接入或连接成功 |
| TLS TCP | TCP 与 TLS 握手 |
| WebSocket | TCP 与 WebSocket Upgrade |
| WSS | TCP、TLS 与 WebSocket Upgrade |

**N-EST-02** 一次有效建立尝试恰有一个最终结果：成功或失败。失败、取消、超时和成功竞争时只能有一个结果。失败不得产生该连接的业务生命周期回调。成功后关闭不得重新解释为建连失败。

**N-EST-03** 成功交付顺序为：创建 Connection → onConnected → 建连成功结果。onConnected 抛异常通过 onException 报告，不改变 Transport 已成功的事实。onConnected 内关闭允许成功结果携带已关闭或正在关闭的 Connection。

**N-EST-04** 建立必须有失败及超时结束方式；具体超时、配置入口与各阶段边界由 binding 说明。同步等待中断必须取消未完成尝试并回收底层连接，不得留下无人接收的成功连接。

**N-EST-05** 每次 connect 独立，允许并发以及向同一目标建立多条 Connection。Client 只临时管理未完成尝试，不充当业务连接管理器。

### 2.1 四种建立流程

```text
TCP：TCP 接入/连接完成
TLS：TCP → TLS 握手完成
WS： TCP → HTTP Upgrade/WS 握手完成
WSS：TCP → TLS 握手 → HTTP Upgrade/WS 握手完成
共同交付前提：对应 Transport 完成，并且应用流水线已经初始化成功
满足交付前提后：创建 Connection
随后统一执行：onConnected → Client 成功结果（服务端没有 Client 回调）
```

任何建立前阶段失败，都结束本次尝试并回收未交付的底层连接。因为此时尚无业务 Connection，不能伪造 onConnected 后再用 onDisconnected 表示“从来没有建立成功”。

流水线初始化与 Transport 建立的具体装配次序由 binding 说明；本规范要求两者都在创建 Connection 前成功，不要求等握手结束后才装配流水线。上层可在 onConnected 发起自己的 RPC 握手或登录交换；这些协议是否完成不改变 Network Connection 的定义。Network 不为它们新增 READY 状态。

### 2.2 成功、取消与异常的竞争

| 竞争结果 | 建连结果 | 生命周期 |
| --- | --- | --- |
| 取消/超时/关闭先结束尝试 | 失败 | 不创建 Connection，不产生其业务回调 |
| Transport 与应用初始化先完成并认领成功 | 成功 | onConnected 先于成功结果 |
| onConnected 抛异常 | 仍成功 | 经 onException 报告 |
| onConnected 主动 close | 仍成功 | 成功结果中的连接可能已不可用 |
| 成功后发生 I/O 错误或断开 | 不改写原成功结果 | 按已建立 Connection 处理 |

这区分了“建连结果”和“连接能持续多久”。成功不保证调用方收到结果时仍有可用连接，也不保证对端一定会收到首条业务消息。同步调用被中断时，绑定实现还必须回收成功竞争窗口中可能无人接管的连接，详见 Java 开发规范。


## 3. 读写、接纳与背压

**N-IO-01** 建立后的客户端和服务端使用相同双向 Connection 契约。TCP 是字节流，一次入站缓冲区不等于一个业务消息；业务 framing/codec 由应用提供。

**N-IO-02** 同一 Connection 中有确定调用先后关系且均被接纳的 write 必须保持出站顺序。真正并发、无先后关系的写入不定义全局排序。传输顺序不保证远端业务执行顺序。

**N-WRITE-01** write 有且仅有以下三种正常结果：

| 结果 | 含义 | 所有权 |
| --- | --- | --- |
| ACCEPTED | 已接纳并提交底层出站流程 | 网络实现 |
| INACTIVE | 连接已不可用，没有提交消息 | 调用方 |
| NOT_WRITABLE | 观察到背压，没有提交消息 | 调用方 |

**N-WRITE-02** ACCEPTED 不保证编码成功、socket 写出、TCP ACK、对端接收或业务处理。接纳后的失败不反向改变结果，也不得诱使上层重复释放或重发同一份所有权。

**N-WRITE-03** 拒绝时不得消费、释放或修改消息。接纳后调用方不得继续修改、释放或再次提交同一份所有权。编程错误的异常形式及接管时点由 binding 明确。

**N-WRITE-04** isWritable 是观察值，不是容量预留。write 应重新检查；已接纳消息不因之后出现背压改报拒绝。Network 不等待连接可写，不增加自己的发送队列，不自动重试。

**N-WRITE-05** 建立后的编码或 I/O 异常通过 onException 报告。Network 不因为普通异常自动关闭，业务决定是否关闭或采取其他策略。传输协议自身对非法消息的拒绝与关闭规则不受此条限制。

### 3.1 发送的可观察结果

发送经历多个阶段：

```text
调用 write → 检查 active/writable → 接纳并转移所有权
→ 编码 → 底层排队/发送 → 对端传输接收 → 对端解码 → 业务处理
```

write 只回答前三步的接纳问题。需要确认对端业务处理时，上层协议必须设计响应或确认消息。ACCEPTED 后再发生编码失败，仍由 Network 负责已经接管的资源，上层不能因为 onException 到达就释放同一个对象。

| 调用场景 | 调用方动作 |
| --- | --- |
| 返回 INACTIVE | 自己释放、丢弃或以仍持有的消息执行上层策略 |
| 返回 NOT_WRITABLE | 同上；不能假定 Network 已缓存该消息 |
| 返回 ACCEPTED | 停止使用这一份消息所有权 |
| 先读 isWritable=true，随后 write 返回 NOT_WRITABLE | 按拒绝处理；观察值没有预留额度 |
| 返回 ACCEPTED，随后变得不可写 | 不撤销接纳；之后的新写入各自检查 |
| 编程错误抛异常 | 按语言绑定的接管时点处理，不能一律当成拒绝 |

### 3.2 背压的范围

Network 不等待可写、不维护业务重试队列，也不承诺严格字节内存上限。底层水位是当前积压的观察机制，多个并发发送者可能在观察值变化之前都被接纳。

上层可以丢弃可丢消息、停止生产、断开慢连接，或维护自己有界的重试策略。选择必须结合消息幂等性与时效性；Network 不替业务决定。按同一消息对象盲目重试 ACCEPTED 的写入，会同时违反所有权并可能重复发送。


## 4. 生命周期与错误

**N-LIFE-01** 同一 Connection 的应用回调串行执行，顺序为：

```text
onConnected
→ onMessage / onEvent / onException（零次或多次）
→ onDisconnected（一次）
→ 结束
```

不同连接可以并发使用同一个 handler。onConnected 中绑定的数据应可供后续回调读取。

**N-LIFE-02** close 非阻塞且幂等，发起底层关闭，不承诺刷完已接纳消息。isActive 反映实际底层可用性，不要求 close 调用返回时立即变为 false；onDisconnected 在底层真正失效后发生。

**N-LIFE-03** onConnected、onMessage、onEvent、onDisconnected 抛异常均交给 onException。onException 自身抛异常只做最终诊断，不递归回调，不由 Network 自动关闭。

**N-LIFE-04** onDisconnected 清理抛异常时，可以紧接着调用一次 onException；清理结束后不再产生应用回调。关闭不携带框架定义的 CloseCause/ReasonCode；业务自行保存原因。

**N-LIFE-05** TLS/WS 建立过程事件由 Transport 消费，不作为普通 onEvent 交给应用。建立前失败由 Client 建连结果或 Server 诊断报告，不调用 onException(Connection, cause)。

### 4.1 回调失败的具体走向

```text
onMessage 抛异常
→ 调用 onException
→ 释放本次借用的入站引用
→ 连接保持由业务决定的状态

onException 再抛异常
→ 最终诊断
→ 不递归调用 onException

底层连接失效
→ onDisconnected
→ 若清理抛异常，报告一次 onException
→ 生命周期结束，晚到错误只做诊断
```

“异常不自动关闭”针对建立后的普通业务/编码/I/O 报错。非法 WebSocket 帧、Text 输入或握手失败属于 Transport 的协议处理，不要求保留一个协议已经无效的连接。上层处理错误时应区分阶段和原因，而不是把所有 Throwable 都解释成同一种业务失败。


## 5. 入站所有权

**N-OWN-01** onMessage 使用借用语义：回调返回或抛异常后，Network 释放自己持有的入站消息引用。应用需要跨回调持有时，必须按 binding 规则保留独立引用并最终释放。

**N-OWN-02** 普通 onEvent 保留事件生产者规定的所有权约定，不自动套用入站消息释放规则。

属性的具体 API 由 binding 定义；本版不要求自定义属性 registry、关闭时冻结属性或框架清空属性。

### 5.1 借用消息的三种用法

| 用法 | 需要承担的责任 |
| --- | --- |
| 在 onMessage 内完成读取 | 不保留框架借用引用；返回后不再访问 |
| 将消息交给异步业务执行 | 在返回前取得独立所有权；提交失败与处理完成都要释放 |
| 直接回写同一个消息 | 为发送取得独立所有权；发送拒绝时释放发送份额 |

入站借用和出站转移是两个独立契约。不能把“我把消息 write 出去了”当作取消 Network 入站释放的指令。普通事件则依其生产者约定处理，不套用上述自动释放规则。


## 6. Server / Client 生命周期

**N-SERVER-01** Server 同步 start，成功返回表示已监听；失败抛出对应语言的操作异常，回收自有资源。Server 只能启动一次，失败或关闭后不能 restart；再次启动需新建实例。

**N-SERVER-02** localAddress 提供实际监听地址，支持动态端口。close 幂等，停止接入，并回收自有基础设施资源。建立中的未交付连接应取消，不能在停止接入后继续变成新连接。

**N-CLIENT-01** Client close 拒绝新的连接并取消未完成尝试，最终唤醒同步等待方或通知异步失败。已被成功一方认领的尝试仍报告成功。

**N-RESOURCE-01** binding 必须明确哪些资源自行创建、哪些借用。外部资源不得被框架关闭。已经交付的 Connection 不作为业务集合由 Server/Client 维护；关闭自有执行资源可能使它们失效，借用资源时由上层单独关闭成功连接。

### 6.1 资源所有权矩阵

| 资源 | 创建/提供方 | 关闭责任 |
| --- | --- | --- |
| Server 监听端点 | Server | Server.close |
| 尚未交付的建立尝试 | Server / Client | 失败、取消或所属入口关闭时回收 |
| 框架自建的执行基础设施 | Server / Client | 所属对象关闭时回收 |
| 应用注入的执行基础设施 | 应用 | 应用负责，不由 Network 关闭 |
| 已交付的 Connection | 交付后由上层持有 | 上层决定连接生命周期；自有底层资源关闭也会使其失效 |

Client 可以连续或并发 connect 多个目标。成功后从未完成集合移除，不将它转存为长期 ConnectionManager。业务若需要按玩家、节点或会话查找连接，应在上层建立映射，并处理断开清理。

借用资源时，关闭 Server 停止监听，关闭 Client 停止新建连接；两者都不等于自动关闭全部已交付连接。反过来，自建执行资源关闭会连带关闭其上的连接，这属于资源生命周期，不代表框架维护了业务连接集合。

### 6.2 关闭方法不能互相替代

Connection.close 是单连接异步关闭请求。Server/Client.close 则关闭入口及自有基础设施，其等待形式由 binding 明确。服务停机应先停止接入和新建连接，再按照业务协议处理在途消息，关闭持有的连接，最后回收共享资源。

Network 不自动等待业务响应完成、不刷完全部发送，也不替 RPC 或 Runtime 执行优雅停机。需要“发送完成后关闭”时，必须在上层协议或更具体的传输接入中定义完成依据。


## 7. Binary WebSocket Profile

**N-WS-01** 入站将 Binary 与 Continuation 分片聚合为完整二进制消息后，再交给应用流水线。出站二进制缓冲区转换为 Binary WebSocket Message。

**N-WS-02** Ping/Pong/Close 由 Transport 处理，不交付 onMessage。Text 不属于此 Profile，应关闭连接；消息超限或协议违规同样关闭，不交付被拒绝的业务消息。

**N-WS-03** 最大消息大小同时约束单帧 payload 和聚合后的完整消息。binding 必须提供明确默认值；HTTP Upgrade 内容大小与业务消息大小是不同限制。

**N-WS-04** Server endpoint 按完整 path 精确匹配，query 不参与匹配。不能把 /game 当作 /game/ 或 /game/child；不提供通配符、路由参数或自动规范化。

### 7.1 消息边界与端点示例

WebSocket 完整二进制消息与 TCP 入站字节片段的边界不同。应用可以在二进制消息中进一步解码自己的协议，但不能据此要求 TCP 一次回调也对应同一个业务消息。

| 输入或端点 | 结果 |
| --- | --- |
| Binary 起始帧 + 若干 Continuation，最终完成 | 聚合后交付一个二进制消息 |
| 合法 Ping / Pong / Close | Transport 内处理，不作为业务消息 |
| Text 消息 | 此 Profile 不接受，关闭连接 |
| 单帧未超限，但聚合总长度超限 | 拒绝并关闭 |
| endpoint=/game，请求 /game?token=x | path 匹配；query 由上层自行解释 |
| endpoint=/game，请求 /game/ 或 /game/child | 不匹配 |
| path 使用不同编码或拼写形式 | 不提供自动规范化等价承诺 |

最大消息大小不是整个连接的流量配额，也不是应用解码后对象大小的上限。HTTP Upgrade 内容上限单独配置或由 binding 固定，不能拿它替代二进制业务消息上限。

### 7.2 已确认设计取舍

| 选择 | 理由及影响 | 后续需要重新评估的条件 |
| --- | --- | --- |
| Transport 完成后才创建 Connection | 业务面对统一可收发入口，无中间公共状态 | 引入 Transport 前业务干预 API |
| 只有三种发送接纳结果 | 热路径简单；交付确认由上层负责 | 要求逐消息发送完成结果 |
| 普通异常由业务决定关闭 | 允许按协议和业务原因处理 | 改变默认故障策略 |
| 不提供 ConnectionManager | 连接归属由玩家/节点等上层模型定义 | 新增独立连接管理组件 |
| 原生传输扩展点 | 避免重复包装 codec、心跳与底层参数 | 引入跨实现统一扩展能力 |
| 二进制 WS Profile | 明确业务载荷与控制帧边界 | 支持文本或其他传输 |

旧 Connector/Acceptor、生命周期中间状态、tryWrite、自定义属性键和 CloseCause 模型不是本版的待补功能。后续继续完善当前设计时，不应因旧聊天出现过这些提议而重新加回；如确有新需求，应明确记录变更动机与迁移影响。


## 8. 合规验证

当前 Java 测试映射见 [Java 开发规范 验证](OGBS-Network-Java-25-Specification-1.0.md#9-验证与边界)。测试覆盖是实现证据，不是生产容量、全部竞态或跨语言互操作认证。

本次替换旧的 Connector/Acceptor、onCreated/onRead/onClosed、tryWrite、自定义 ConnectionKey、CloseInfo 及异常自动关闭模型。新实现不提供旧 API 兼容层；上层接入须迁移。
