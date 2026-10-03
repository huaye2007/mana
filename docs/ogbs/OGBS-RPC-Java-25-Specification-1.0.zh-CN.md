# OGBS RPC Java 25 Development Specification 1.0

[English](OGBS-RPC-Java-25-Specification-1.0.md) | **[简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应 [RPC 标准规范](OGBS-RPC-1.0.zh-CN.md)，字节布局见 [Wire Profile](../rpc-wire.zh-CN.md)。

状态：已实现并通过本地验证。Peer 重建保持 Node 生命周期内的调用 ID；超时通知与维护隔离；停止恢复链时重新检查并发解绑。R-SEND-04 的有限调用接纳上限仍未实现，暂缓处理。JDK 25，无 preview；Maven cn.managame:game-rpc:1.0.0-SNAPSHOT 依赖 game-core、game-network，传递使用 Netty。RPC 核心无 Runtime/Data、Spring、业务 codec 或服务发现依赖。可选 Runtime 接入由 [game-spring](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc) 提供。

## 1. 包与封装

| 包 | 类型与职责 |
| --- | --- |
| cn.managame.rpc.node | RpcNode、RpcNodeBuilder；包级 RpcPeer、ConnectionSlot、PendingCall、RpcConnectionContext、RpcConnectionHandler |
| cn.managame.rpc.message | 只读 RpcRequest、RpcResponse、RpcHandshake wire 值对象 |
| cn.managame.rpc.call | RpcHandler、泛型 RpcCallback |
| cn.managame.rpc.transport | RpcSendStatus |
| cn.managame.rpc.error | RpcErrorCodes、RpcException、RpcEncodeException |
| cn.managame.rpc.netty | RpcWire 公开 wire 绑定；包级 RpcEncoder、RpcProtocol |

可运行示例及其执行测试位于 game-demo（cn.managame.demo.examples.rpc），不随 game-rpc artifact 发布。示例调用方需要更新 import 与模块依赖，不保留旧包别名。组件契约测试仍位于 game-rpc。类型按职责分包，内部实现保持包级封装。为避免跨包暴露 requestId setter 或内部访问桥，消息沿用只读 record 形式；节点给编码器传入内部生成的 ID，**不回写调用方 Request**。公开完整值构造器可表示已解码消息，但 call/notify 拒绝非零 requestId。该封装方式不改变 Wire 布局与调用行为。

RpcWire 是可独立使用的 Wire Profile 接入 API，不暴露可变 Peer/Slot/计时器。RPC 核心、Network 适配和编解码在同一 artifact 发布，不创建独立 netty artifact。旧草案 RpcNodeConfig、RpcDialer、EstablishmentOwnership、RpcRequestHandler、RpcErrors 不再提供。

## 2. RpcNode 与 Builder

```java
RpcNode.builder()
    .nodeId(1)
    .bindAddress(new InetSocketAddress("127.0.0.1", 9000))
    .handler(handler)
    .build();

int nodeId();
void start();
SocketAddress localAddress();
void addPeer(int nodeId, SocketAddress address, int slotCount);
void removePeer(int nodeId);
RpcSendStatus notify(int nodeId, RpcRequest request);
<T> void call(int nodeId, RpcRequest request, RpcCallback<T> callback);
<T> void call(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback);
RpcSendStatus reply(int nodeId, int sourceSlotId, long routeKey, RpcResponse response);
void close();
```

Builder 的 nodeId/address/handler 必填。nodeId 非零，int 保存 uint32 原始位；负数合法。Builder 不并发安全，build 可重复，配置被快照，build 不创建线程、绑定端口或建立 Peer。

| Builder 方法 | 默认值 | 边界 |
| --- | --- | --- |
| callTimeout(Duration) | 5 秒 | 转换后 >=1ms，可表示为正 long 纳秒 |
| handshakeTimeout(Duration) | 5 秒 | 同上 |
| reconnectDelay(Duration) | 1 秒 | 正基础延迟，Duration 范围同上 |
| reconnectRandomDelay(Duration) | 基础延迟的 1/4 | 非负附加随机上限；0 使用固定延迟，合计须可表示为纳秒 |
| heartbeatInterval(Duration) | 10 秒 | 正 Duration 范围与 callTimeout 相同 |
| heartbeatTimeout(Duration) | 30 秒 | 必须大于 interval |
| maxFrameSize(int) | 4 MiB | >=32，包含长度前缀 |

Duration.toMillis 溢出同步抛 ArithmeticException；正延迟低于 1ms 或超过 Long.MAX_VALUE 纳秒抛 IllegalArgumentException，单次 timeoutMillis 范围相同。reconnectRandomDelay(Duration) 设置非负的额外随机延迟上限，默认 reconnectDelay/4 并截断到毫秒；零表示固定延迟。基础延迟加随机上限必须可表示为正 long 纳秒。每次重试均匀抽取含端点的整数毫秒值。例如基础 1000ms、随机上限 250ms，实际等待 1000..1250ms。该配置分散重试，与恢复权仲裁独立，不限制全局建连速率。当前不提供 maxPendingCalls，R-SEND-04 有限接纳仍暂缓实施。

V1 RpcNode 自己组装内部 TCP NetworkServer/NetworkClient，不复制 Network 的 pipeline、TLS/WS、ChannelOption、EventLoopGroup 配置入口。

start 一次性创建时间轮与自有 NIO groups，装配 client/server，同步 bind 成功后才 RUNNING。失败进入 CLOSED，清理部分资源后抛 RpcException，不复用实例。localAddress 在 bind 前为 null，可返回随机端口实际地址；关闭后可用于诊断，不表示仍监听。

addPeer 只能在 RUNNING 调用；ID 非零且非自身、地址非 null、slotCount=1..255。相同配置幂等，地址/数量冲突抛 IllegalStateException。被动 Peer 可以原地升级。removePeer 未命中无操作，但 NEW/CLOSED 时仍抛生命周期异常。

## 3. 消息与 ByteBuf 所有权

```java
public record RpcRequest(
    int command, int requestId, long routeKey, int businessIdType,
    long businessId, Metadata metadata, ByteBuf body) {}

new RpcRequest(command, body);
new RpcRequest(command, routeKey, businessIdType, businessId, metadata, body);

public record RpcResponse(
    int requestId, int errorCode, Metadata metadata, ByteBuf body) {}
```

便利 Request 构造器令 requestId=0。command 非零；businessIdType 为 0..255。Response requestId 非零，errorCode>=0。构造参数非法立即抛 IllegalArgumentException，不释放调用方 body。null Metadata 归一化为空，null body 表示空负载；入站零长度 body 返回空 slice。

| 边界 | 所有权 |
| --- | --- |
| 参数/null/生命周期校验失败 | 调用方仍持有 body |
| 通过校验的 call/notify/reply | 接管恰好一个 body 引用，所有后续路径消费 |
| Peer 不存在/无可用连接 | 直接 release body，不编码 |
| 编码成功或失败 | 编码器 release body；成功只剩最终 frame |
| Network ACCEPTED | frame 交给 Network，立即停止 fallback |
| 所有连接拒绝 | RPC release frame |
| 入站 onRequest/onResponse | body 借用，不应直接 release；retain/copy 后可异步使用 |

Encoder 预计算 long 总长度，在分配前校验上限；只分配一个连续最终 ByteBuf，body 按 readerIndex/readableBytes 复制一次，不移动原 readerIndex。Metadata 自定义实现也需通过 Core 结构检查。异常包装为 RpcEncodeException；已分配 frame 和输入 body 都回收。

入站 Metadata 复制到独立 byte[] 并校验结构；body 用 frame 的 readSlice，Network 在消息回调之后释放 frame。保存 Request 对象不延长 body 寿命。同步 echo 也须 retain 后才交给 reply，否则会重复释放借用引用。

采用连续帧而非 CompositeByteBuf 是为了简化所有权与 fallback；只有生产测量表明大 body 复制成为瓶颈时才重新评估。当前没有零复制出站承诺。

## 4. Handler、Callback 与异常

```java
public interface RpcHandler {
    void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request);
    void onResponse(int sourceNodeId, int requestCommand,
                    RpcResponse response, RpcCallback<?> callback);
    void onFail(int targetNodeId, int requestCommand,
                int errorCode, RpcCallback<?> callback);
}
@FunctionalInterface
public interface RpcCallback<T> {
    void onResponse(T response);
}
```

RpcHandler 必须线程安全、快速返回。request/response 与已匹配响应的协议失败通常在 Netty EventLoop；立即失败在调用线程；remove/close 通知在管理线程。超时获胜时先在共享维护时间轮移除 PendingCall，再用单独登记的虚拟线程执行 onFail，慢通知不会阻塞其他维护期限。通知在 finally 注销，包含 Handler 异常路径；close 先停止时间轮，再等待这些通知。不创建固定业务线程池，game-rpc 本身仍无有限接纳额度、回调全局顺序或自动 Runtime 恢复。

所有远端响应（成功、框架错误、业务错误）都走 onResponse。由应用依据 command/协议绑定解释错误、解码 body，再调用合适的 RpcCallback<T>。本地失败才走 onFail；callback 本身没有 onFailure。保存 callback 可携带应用上下文，但恢复 Route 仍由接入层负责。

onRequest 抛 RuntimeException 时记录诊断，Call 尝试 HANDLER_ERROR 空响应，Notify 只记录；不关闭健康连接。onResponse/onFail 的 RuntimeException 只记录，不再次通知。JVM Error 不当普通业务异常吞掉。诊断使用 System.Logger，不回传异常堆栈。

| 情况 | Java 结果 |
| --- | --- |
| null 必填项 | NullPointerException |
| 参数、timeout、Slot 范围不合法 | IllegalArgumentException |
| NEW/CLOSED 调用运行 API | IllegalStateException |
| notify/reply 接纳/无 Peer/不可用 | RpcSendStatus 三种值 |
| call 无 Peer/无可写 Slot | onFail(PEER_NOT_FOUND/UNAVAILABLE) |
| timeout/remove/close | onFail(TIMEOUT/PEER_REMOVED/NODE_CLOSED) |
| 编码错误 | RpcEncodeException，同步抛出且已消费 body |
| 极端 requestId 冲突 | RpcException，同步抛出，保留旧 PendingCall |
| 匹配后响应格式坏 | onFail(PROTOCOL_ERROR)，关闭当前连接 |
| 同步 Network write 异常 | 停止 fallback、清理本次 PendingCall/帧，关闭连接并同步传播 |

reply 的来源 Slot 先检查 0..254；Peer 存在时还检查小于其 slotCount，失败不接管 body。Peer 不存在时返回 PEER_NOT_FOUND 并消费 body。所有参数有效但与关闭竞争的 call 仍由完成权仲裁，不能重复通知。

RpcErrorCodes 引用 Core 的统一编号；不另外维护编号。旧高位框架错误封装已删除，详细兼容性见 Wire Profile 与 Core。

## 5. call 发送与完成顺序

1. 校验参数和 RUNNING，登记进行中的 API 操作，接管 body。
2. 查 Peer 和候选连接；失败直接释放 body，并交付本地失败。
3. 从 Node 的 AtomicInteger 分配 ID，跳过 0，编码最终 frame；Peer 删除/被动回收/重建不重置，完整 uint32 回绕和 Node 重启仍遵循 R-CALL-04 边界。不预留有限接纳额度。
4. 通过 ConcurrentHashMap.putIfAbsent 注册 PendingCall；碰撞不覆盖、不扫描。
5. 再次检查节点和 Peer 对象身份，失效则完成失败。
6. 按亲和性和 fallback 尝试发送，同一个 frame 最多接纳一次。
7. ACCEPTED 后以 HashedWheelTimer 注册 timeout，写入 volatile Timeout。
8. 重新检查 PendingCall 仍在 Map 中，否则取消刚注册的 Timeout。

PendingCall 仅保存 ID、command、callback、volatile Timeout。条件 remove 决定超时/移除/关闭/发送失败的完成权；Response 读取 ID 后 remove，取消 Timeout，再解析余下响应。未匹配直接丢弃；匹配后解析失败仍须通知 PROTOCOL_ERROR。

pending Map 没有配置数量或字节上限。Network 可写性只控制出站接纳，不限制已经发出但仍在等待响应的调用。可连接但响应慢的 Peer 会在超时间隔内持续积累 PendingCall、callback 和计时任务。粗略的在途数量随已接纳调用速率乘以响应驻留时间增长；这是容量估算关系，不是已测量的容量结果。当前不提供 Peer 间公平性、等待队列或业务重试队列，R-SEND-04 要求的有限 Node 级接纳上限尚未实现。

## 6. 网络装配与 Peer 并发

管线入口为 `RpcWire.configurePipeline(pipeline, maxFrameSize, heartbeatIntervalMillis, heartbeatTimeoutMillis)`，替代原 `RpcWire.install` 命名，不保留旧名别名。直接调用方需要修改源码并重新编译；调用旧方法的已有二进制不兼容。管线顺序、分帧、心跳行为及线协议兼容性保持不变。

RpcWire.configurePipeline 添加 LengthFieldBasedFrameDecoder(maxFrameSize,0,4,0,4)，随后添加 IdleStateHandler；因此完整帧驱动 Read Idle，未完成帧的零散字节不能无限保持活性。Network 适配收到已剥离 4 字节长度前缀的 ByteBuf。

Network 已保证客户端 onConnected 先于 ConnectCallback.onSuccess：前者安装 AttributeKey<RpcConnectionContext> 与握手超时，后者关联 expectedPeer/expectedSlot 并发起握手。不会在两个回调中重复绑定 Slot。

每个 Node 一个 HashedWheelTimer，tick=10ms，负责调用/握手期限以及加入所配置随机延迟的重连调度，不直接执行超时业务通知。并发集合登记单独启动的虚拟通知线程；close 在 timer.stop 后等待剩余通知结束，不因中断放弃等待，并恢复中断状态。迟到计时登记不能逃出屏障。立即失败、响应和管理通知仍沿用原执行线程。各连接 IdleStateHandler 负责心跳，EventLoop 处理器须快速返回；timerThread 用于识别维护线程、防止自等待。

Peer 为 ConcurrentHashMap<int,RpcPeer>。低频 start/close/add/remove/handshake 的拓扑修改使用生命周期锁；同 ID 被动创建、升级与清理用 compute 仲裁。热发送不获取生命周期锁。

Slot 用 AtomicReference<Connection> 绑定/身份解绑，AtomicBoolean connecting 覆盖延迟、建连与握手。观察 Slot 已占用后停止恢复链时，先清除 connecting，再检查 Slot 为空、Peer 仍当前且目标有效，通过 CAS 重新获得恢复权。若解绑此前看到 true，停止方补上这次交接；若解绑看到 false 并自己获得恢复权，旧链 CAS 失败。被动 Peer 升级与观察到无目标而停止的恢复链也使用相同重新检查规则，避免升级获得目标后遗漏空 Slot。两条路径保持唯一恢复者。旧 Peer 任务检查 Map 对象身份，断线不清理在途调用。RpcResilienceTest 执行 10000 轮同步竞争回归，不代表容量压测。

原生 Netty AttributeKey 保存 RPC context。Node 全局记录全部自有连接，包含未关联入站握手。出站 expectedPeer/expectedSlot 发布与当前 Peer 检查同在 lifecycleLock 内，移除不会漏掉拓扑删除后才发布的关联。RpcPeer 无连接索引；detach 扫描全局集合中的 READY/expected 关联，再清理 Slot。关闭遍历仍为 O(P × C + S)，另计在途完成工作；大拓扑关闭延迟未验证，优化暂缓。

## 7. 关闭屏障与线程边界

部署模型遵循 RPC 标准第 1 节：服务持有长期运行的 RpcNode，在服务启动时启动一次，在维护或退出时由独立管理上下文关闭。远端重启或连接故障期间，本地 RpcNode 继续运行。不提供 pause/resume、关闭后复用、热替换协调器或专用关闭工作线程。关闭前停止业务接纳和管理业务工作由应用负责，详见标准第 8 节。JVM 被突然终止时，不能执行尚未调用的清理屏障。

简化指导：保留现有一次性生命周期与资源归属，使用普通的统一清理路径。Phaser、CountDownLatch 和线程身份字段目前承载已接纳操作与防自等待边界；删除时应核对这些边界，不能将每个字段都理解成新增线程。只有维护测量证明必要时才优化反复全局清理扫描；额外执行器、生命周期状态和 drain API 需要具体的执行或维护需求。此次说明不改变公开 API 或当前关闭行为。

close 在 lifecycleLock 内先设置 CLOSED、移除当前 Peer；锁外等待已接纳 API 操作，清理 PendingCall/Slot，关闭连接、监听、客户端、自有 group，停止时间轮，再等待全部自有超时通知。先停止 timer 再进入通知等待屏障，防止迟到登记。阻塞通知仍可能令 close 等待，但其他期限可独立推进。不能持有拓扑锁等待 EventLoop。closingThread 保证管理清理重入幂等，不创建关闭工作线程。

为覆盖“close 清空 Map 后发送线程才注册 PendingCall”的竞争，API admission 使用 Phaser 登记；它不是业务任务队列，也不改变路由。并发 close 通过 CountDownLatch 等待同一次清理。应用抛 RuntimeException 不打断其他资源清理；资源关闭异常记录并继续。

start/close 不允许从本 Node RpcHandler、EventLoop 或时间轮执行；调用在修改生命周期前抛 IllegalStateException，避免自等待。管理线程上的关闭回调再次 close 为幂等。应由独立管理线程停服。

close 返回后不再保留 PendingCall、RPC 网络连接、时间轮或自有超时通知；它不等待应用投递至 Runtime/其他执行器的任务。先按业务策略停止入口并处理业务工作，再关闭 RPC。启动失败不能再次 start。

## 8. 可运行示例与源码

[RpcEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) 在动态端口启动两个本地节点，展示 TCP 握手/call、字符串解码、body retain 与回复。RpcExampleTest 在根 clean verify 执行完整示例。可选 Spring 适配用法另见 [集成规范](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)。

[RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java)、[RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java)、[RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java) 是主要实现入口。

RpcWire 公开 configurePipeline、encodeRequest(request,assignedId,maxFrameSize)、encodeResponse、encodeHandshake、encodeHeartbeat 和对应 decode 方法。encode 消费输入 body，decode 返回依附输入 frame 的借用 slice；调用方须遵守 Javadoc 的 type/ID 读取位置。直接使用 RpcWire 不具备 RpcNode 的握手状态、Peer 或完成仲裁，不能绕过语义层宣称完整 RPC 实现。

## 9. 验证与未实现范围

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java)：四类黄金向量、uint32/uint64、Metadata、引用计数、长度边界、分片/粘包、损坏帧。
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java)：Slot 回退、快速响应、调用竞争、ID 回绕碰撞、被动生命周期、心跳拒绝、握手超时、Handler 异常及关闭屏障。
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java)：真实 TCP 双向通信、独立重连、跨 Slot 回复、主动恢复与被动升级。
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java)：Peer 重建、恢复权竞争、通知隔离/所有权、未完成握手关闭与重连配置。
- [RpcExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/rpc/RpcExampleTest.java)：在 game-demo 中执行完整示例，命令为 mvn -pl game-demo -am test。

2026-10-03 当前验证：根 mvn clean verify 通过全部七个组件/应用模块，包含 32 项 RPC 测试及可运行 RPC 示例。定向命令 mvn -pl game-rpc -am test；跨组件/依赖变更须根 clean verify。Windows 测试沿用 Network Selector TCP 唤醒兼容设置，生产不修改 JVM 属性。

未实现/暂缓：有限调用接纳、TLS/WS RPC Builder、发现、Router、业务重试、远端取消及持久投递。可选 Spring 解码/Runtime 投递/对象回复/Route 回调已在 RPC 核心之外实现。未验证：跨语言互操作、生产吞吐/内存、长期压力与公网部署。

<a id="91-审阅确认的缺陷与规模风险"></a>

### 9.1 当前源码修复与剩余规模边界

Node 级 ID 分配保留跨 Slot/替换连接回复能力，同时避免同一 Node 中 Peer 重建立即复用旧 ID。[RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java) 通过真实 TCP 检查保存的旧回复不会完成新请求；[RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java) 覆盖被动回收、超时通知隔离/关闭等待、未完成握手关闭、恢复权竞争与随机延迟边界。

仅通过原物理 Connection 回复会让原连接断开后的有效回复无法交付，也无法修复维护线程阻塞或恢复权丢失。因此保留逻辑 Peer/Slot 回复模型，修复关联 ID 与并发交接，不改变 Wire v1。

有限 Call 接纳（R-SEND-04）、入站握手/被动 Peer 上限仍未实现，按当前范围暂缓。Network 可写性不是已发送调用数量上限。关闭仍为每个 Peer 扫描全局连接；大规模关闭优化与诊断暂缓。随机延迟分散重试，但不限制全局尝试速率。这些剩余边界没有因构建通过而消失。

<a id="92-后续审阅与扩展候选"></a>

### 9.2 验证边界与扩展候选

Wire v1 没有 Node 代际字段；替换/重启 Node 后重置 ID，也可能接纳保存的旧回复。这是 R-CALL-04 已声明的 Node 生命周期边界，与现已通过 Node 级分配保护的同 Node 内 Peer 重建不同。若分配重置，单独加宽 ID 不能解决重启复用。应用 epoch 或明确版本化协议需独立设计，本次审阅不选定方案。

已确认的“先合法绑定者获胜”策略下，两边同时建连可能各自绑定不同物理连接的入站一端，再将两条出站连接以重复 Slot 拒绝。恢复收敛需要永久真实 TCP 回归，覆盖同时 addPeer 和同时断开；此次审阅不证明永久活锁或生产发生率，也不恢复 Node-ID 仲裁方案。

请求/响应处理器在连接 EventLoop 执行，阻塞会延迟同一 EventLoop 上其他连接。应用另行投递时须 retain/copy 借用 body，并覆盖接纳和拒绝路径的释放。接纳、字节预算及 Peer 间公平性需要依据测量确定边界，当前没有 API 提供这些约束。现有诊断不证明持续容量。

可选诊断、独立 RPC drain 阶段与本地取消仍为扩展候选。可选 Spring Runtime 投递现已实现，不增加发现、业务重试、接收去重或持久投递。内部拆分及分配/flush 优化需测量依据，必须保持所有权、接纳与完成顺序。