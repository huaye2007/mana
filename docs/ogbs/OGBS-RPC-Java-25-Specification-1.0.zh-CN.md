# OGBS RPC Java 25 Development Specification 1.0

[English](OGBS-RPC-Java-25-Specification-1.0.md) | **[简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应 [RPC 标准规范](OGBS-RPC-1.0.zh-CN.md)，字节布局见 [Wire Profile](../rpc-wire.zh-CN.md)。

状态：game-rpc 已实现。JDK 25，无 preview；Maven 坐标 cn.managame:game-rpc:1.0.0-SNAPSHOT，依赖 game-core、game-network，传递使用 Netty。无 Runtime/Data、Spring、协议 codec 或服务发现依赖。

## 1. 包与封装

| 包 | 类型与职责 |
| --- | --- |
| cn.managame.rpc.node | RpcNode、RpcNodeBuilder；包级 RpcPeer、ConnectionSlot、PendingCall、RpcConnectionContext、RpcConnectionHandler |
| cn.managame.rpc.message | 只读 RpcRequest、RpcResponse、RpcHandshake wire 值对象 |
| cn.managame.rpc.call | RpcHandler、泛型 RpcCallback |
| cn.managame.rpc.transport | RpcSendStatus |
| cn.managame.rpc.error | RpcErrorCodes、RpcException、RpcEncodeException |
| cn.managame.rpc.netty | RpcWire 公开 wire 绑定；包级 RpcEncoder、RpcProtocol |

可运行示例及其执行测试位于 game-example（cn.managame.example.rpc），不随 game-rpc artifact 发布。示例调用方需要更新 import 与模块依赖，不保留旧包别名。组件契约测试仍位于 game-rpc。类型按职责分包，内部实现保持包级封装。为避免跨包暴露 requestId setter 或内部访问桥，消息沿用只读 record 形式；节点给编码器传入内部生成的 ID，**不回写调用方 Request**。公开完整值构造器可表示已解码消息，但 call/notify 拒绝非零 requestId。该封装方式不改变 Wire 布局与调用行为。

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
| reconnectJitter(Duration) | floor(reconnectDelay 毫秒值 / 4) | 非负毫秒，基础延迟 + 抖动须可表示为正 long 纳秒 |
| maxPendingCalls(int) | 16384 | 正 Node 级接纳上限；DEFAULT_MAX_PENDING_CALLS |
| heartbeatInterval(Duration) | 10 秒 | 正 Duration 范围与 callTimeout 相同 |
| heartbeatTimeout(Duration) | 30 秒 | 必须大于 interval |
| maxFrameSize(int) | 4 MiB | >=32，包含长度前缀 |

Duration.toMillis 的溢出同步抛 ArithmeticException；低于 1ms 或超过 Long.MAX_VALUE 纳秒的配置抛 IllegalArgumentException。单次 timeoutMillis 同样校验。reconnectJitter 另允许零和正的亚毫秒 Duration（截断为零），拒绝所有负 Duration，在 build 时校验总和。隐式 25% 默认值按剩余纳秒范围裁剪，随被快照的基础延迟计算；显式抖动不随基础延迟改变。默认每次重新抽取整数 1000..1250ms，reconnectJitter(Duration.ZERO) 恢复 1000ms。这改变了原先精确的默认重试时间，线格式不变。

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

RpcHandler 必须线程安全、快速返回。request/response 与已匹配响应的协议失败通常在 Netty EventLoop；立即失败在调用线程；remove/close 失败在管理线程。timeout 失败在独立的 Node 自有虚拟线程执行，时间轮先认领完成权并移除 PendingCall。不保证回调全局顺序，没有可配置 callbackExecutor，也不自动恢复 Runtime Context。阻塞的超时处理器不会占用维护时间轮，其调用接纳额度仍保持到处理器返回。

所有远端响应（成功、框架错误、业务错误）都走 onResponse。由应用依据 command/协议绑定解释错误、解码 body，再调用合适的 RpcCallback<T>。本地失败才走 onFail；callback 本身没有 onFailure。保存 callback 可携带应用上下文，但恢复 Route 仍由接入层负责。

onRequest 抛 RuntimeException 时记录诊断，Call 尝试 HANDLER_ERROR 空响应，Notify 只记录；不关闭健康连接。onResponse/onFail 的 RuntimeException 只记录，不再次通知。JVM Error 不当普通业务异常吞掉。诊断使用 System.Logger，不回传异常堆栈。

| 情况 | Java 结果 |
| --- | --- |
| null 必填项 | NullPointerException |
| 参数、timeout、Slot 范围不合法 | IllegalArgumentException |
| NEW/CLOSED 调用运行 API | IllegalStateException |
| notify/reply 接纳/无 Peer/不可用 | RpcSendStatus 三种值 |
| call 无 Peer/无可写 Slot/接纳额度耗尽 | onFail(PEER_NOT_FOUND/UNAVAILABLE) |
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
3. 原子预留一个 Node 级接纳额度；已满则释放 body 并立即 onFail(UNAVAILABLE)。Node AtomicInteger 分配 ID，跳过 0；编码最终 frame。
4. 通过 ConcurrentHashMap.putIfAbsent 注册 PendingCall；碰撞不覆盖、不扫描。
5. 再次检查节点和 Peer 对象身份，失效则完成失败。
6. 按亲和性和 fallback 尝试发送，同一个 frame 最多接纳一次。
7. ACCEPTED 后以 HashedWheelTimer 注册 timeout，写入 volatile Timeout。
8. 重新检查 PendingCall 仍在 Map 中，否则取消刚注册的 Timeout。

PendingCall 仅保存 ID、command、callback、volatile Timeout。条件 remove 决定超时/移除/关闭/发送失败的完成权；Response 读取 ID 后 remove，取消 Timeout，再解析余下响应。未匹配直接丢弃；匹配后解析失败仍须通知 PROTOCOL_ERROR。

maxPendingCalls 在所有 Peer 之间合计限制编码、PendingCall 与正在执行的完成处理器。CAS 在 ID 分配/编码前预留，成功注册后将额度所有权转给 PendingCall。每条终止路径在通知返回后，或同步异常清理后，恰好释放一次。拒绝不再预留额度，在调用线程通知。例如 limit=2、两个超时处理器阻塞时，Map 可能已经为空，第三次 call 仍收到 UNAVAILABLE；允许它进入会使虚拟线程工作无界增长。没有 Peer 间公平性、字节预算、等待队列或消息重试队列。单个 callback/body 仍可能很大，需依据应用内存与延迟测量选上限。取消的时间轮任务由 Netty 清理，不保留到原 deadline 的 DelayQueue 方案已废弃。

## 6. 网络装配与 Peer 并发

管线入口为 `RpcWire.configurePipeline(pipeline, maxFrameSize, heartbeatIntervalMillis, heartbeatTimeoutMillis)`，替代原 `RpcWire.install` 命名，不保留旧名别名。直接调用方需要修改源码并重新编译；调用旧方法的已有二进制不兼容。管线顺序、分帧、心跳行为及线协议兼容性保持不变。

RpcWire.configurePipeline 添加 LengthFieldBasedFrameDecoder(maxFrameSize,0,4,0,4)，随后添加 IdleStateHandler；因此完整帧驱动 Read Idle，未完成帧的零散字节不能无限保持活性。Network 适配收到已剥离 4 字节长度前缀的 ByteBuf。

Network 已保证客户端 onConnected 先于 ConnectCallback.onSuccess：前者安装 AttributeKey<RpcConnectionContext> 与握手超时，后者关联 expectedPeer/expectedSlot 并发起握手。不会在两个回调中重复绑定 Slot。

每个 Node 一个 HashedWheelTimer，tick=10ms，负责调用超时、RPC 握手超时、基础延迟加抖动重连。它不调用业务超时处理器，而是将已认领通知提交至自有逐任务虚拟线程执行器。调用额度约束尚未执行及正在执行的超时通知，不另设通知拒绝策略或应用回调队列。立即失败、响应与管理通知沿用原执行线程。心跳由各连接 IdleStateHandler 负责，EventLoop 处理器必须快速返回。

Peer 为 ConcurrentHashMap<int,RpcPeer>。低频 start/close/add/remove/handshake 的拓扑修改使用生命周期锁；同 ID 被动创建、升级与清理用 compute 仲裁。热发送不获取生命周期锁。

Slot 用 AtomicReference<Connection> 绑定/身份解绑；AtomicBoolean connecting 覆盖整个恢复链。旧 Peer 的异步任务检查 Map 中对象身份，无额外 removed flag。活动 Peer 的目标地址为 volatile；断线不会清理仍在等待的调用。

RPC context 通过原生 Netty AttributeKey 存储。Node 为最终关闭记录全部自有连接，包含尚未关联的入站握手；每个 Peer 另记录其 READY 及出站未完成握手连接。关联在 lifecycleLock 内检查当前身份后才发布 Peer 引用，使删除不会漏掉迟到关联。断开时删除归属索引，按精确连接身份解绑。detach 只扫描该 Peer 索引与 Slot；最终 close 扫描全局集合一次，避免反复 P × C context 扫描。这些是内部索引，不向业务提供 ConnectionManager。

## 7. 关闭屏障与线程边界

close 在生命周期锁内先设置 CLOSED、detach 当前 peers，然后在锁外等待已接纳 API 操作完成、终止 PendingCall/Slot、关闭所有未完成握手和连接、NetworkServer/Client、自有 boss/worker group、时间轮，最后关闭超时通知执行器并等待所有已提交处理器。执行器保持开放到 timer.stop 返回，避免关闭时拒绝时间轮已认领的通知。不会持有拓扑锁等待 EventLoop。

为覆盖“close 清空 Map 后发送线程才注册 PendingCall”的竞争，API admission 使用 Phaser 登记；它不是业务任务队列，也不改变路由。并发 close 通过 CountDownLatch 等待同一次清理。应用抛 RuntimeException 不打断其他资源清理；资源关闭异常记录并继续。

start/close 不允许从本 Node RpcHandler、EventLoop 或时间轮执行；调用在修改生命周期前抛 IllegalStateException，避免自等待。管理线程上的关闭回调再次 close 为幂等。应由独立管理线程停服。

close 返回后不再保留 PendingCall、RPC 网络连接、时间轮或自有超时通知；它不等待应用投递至 Runtime/其他执行器的任务。先按业务策略停止入口并处理业务工作，再关闭 RPC。启动失败不能再次 start。

## 8. 可运行示例与源码

[RpcEchoExample](../../game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) 启动两个本地随机端口节点，完成 TCP 握手、call、应用字符串解码与回复。示例在 reply 前 retain 入站 body，配置客户端接纳上限为 1024，并用 try-with-resources 清理节点。

[RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java)、[RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java)、[RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java) 是主要实现入口。

RpcWire 公开 configurePipeline、encodeRequest(request,assignedId,maxFrameSize)、encodeResponse、encodeHandshake、encodeHeartbeat 和对应 decode 方法。encode 消费输入 body，decode 返回依附输入 frame 的借用 slice；调用方须遵守 Javadoc 的 type/ID 读取位置。直接使用 RpcWire 不具备 RpcNode 的握手状态、Peer 或完成仲裁，不能绕过语义层宣称完整 RPC 实现。

## 9. 验证与未实现范围

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java)：四类黄金向量、uint32/uint64、Metadata、引用计数、长度边界、分片/粘包、损坏帧。
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java)：Slot 回退、快速响应、调用竞争、ID 回绕碰撞、被动生命周期、心跳拒绝、握手超时、Handler 异常及关闭屏障。
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java)：真实 TCP 双向通信、独立重连、跨 Slot 回复、主动恢复与被动升级。
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java)：Peer 重建、并发接纳、通知隔离/所有权、未完成握手关闭与重连配置。
- [RpcExampleTest](../../game-example/src/test/java/cn/managame/example/rpc/RpcExampleTest.java)：在 game-example 中执行完整示例，命令为 mvn -pl game-example -am test。

本地定向命令为 mvn -pl game-rpc -am test；依赖/跨组件接入变更必须根目录 mvn clean verify。Windows 测试沿用 Network 的 Selector TCP 唤醒兼容设置，生产代码不修改 JVM 系统属性。

未实现：自动 Runtime 接入、TLS/WS RPC Builder、服务发现、Router、重试、远端取消、持久投递。未验证：真实跨语言互操作、生产吞吐/内存上限、长期压力和公网部署。测试通过不代表这些能力已提供。

<a id="91-审阅确认的缺陷与规模风险"></a>

### 9.1 修复验证与剩余规模边界

**Peer 重建后的关联已修复。** [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java) 在整个生命周期持有 AtomicInteger 分配器，删除主动 Peer 或回收被动 Peer 不再重置它。PendingCall 与匹配仍归属于各 Peer。uint32 布局、初始 ID 1、跳过零和占用 ID 异常保持不变，允许分配跳号。完整回绕周期现在取决于全部 Peer 的 Node 总 ID 分配量（包括编码失败），而非单个 Peer。重启/替换本地 Node 不在此连续性保证内：wire v1 不携带代际/epoch。需要该保证的应用必须校验自有 epoch 或采用明确版本化协议。

具体边界：A 调用 B 的 command 101，B 保存请求。A 删除 B 并收到 PEER_REMOVED，再添加 B、调用 command 202。B 经合法新连接先返回保存的旧回复，再返回新回复。[RpcIntegrationTest.oldBusinessReplyAfterPeerRecreationCannotCompleteNewCall](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java) 验证只有新 ID/body 交付 command 202。[RpcResilienceTest.passiveRecreationDoesNotReuseCallIdsOrAdmitOldReplies](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java) 覆盖被动自动回收。旧连接身份检查仍然必要，但单靠它不足以解决问题。

**超时通知已隔离且有界。** 时间轮认领完成权后，将 onFail 提交至自有虚拟线程，慢通知仍持有接纳额度。blockedTimeoutNotificationLeavesOtherDeadlinesLiveAndCloseWaits 测试阻塞一个超时处理器，同时验证另一条调用超时、静默握手关闭、容量耗尽拒绝、close 等待该处理器，以及处理器内同步 close 被拒绝。responseNotificationRetainsAdmissionUntilHandlerReturns 覆盖响应处理器的额度所有权。不保证硬期限或回调全局顺序，EventLoop 响应/请求处理器仍须快速返回。

**Node 级调用接纳已有限。** [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java) 提供 maxPendingCalls，默认 16384。并发接纳测试在 limit=8 时向两个 Peer 提交 64 次调用，验证八帧接纳、56 次 UNAVAILABLE、body 消费及后续容量复用。编码/发送失败、uint32 碰撞与终止竞争验证恰好释放额度，而非仅验证 Map 大小。此为数量上限，不是字节预算或 Peer 间公平机制。callback 仍可保留较大对象图，吞吐和内存配置需部署测量。

**关闭不再反复全量扫描。** 每个 Peer 索引 READY 与出站未完成握手连接，全局集合覆盖尚未关联的入站握手。removePeerOnlyTouchesItsConnections 测试在移除访问无关连接时立即失败。removePeerClosesOutboundUnfinishedHandshake 使用不响应握手的真实 TCP 对端，验证移除后 EOF。合计遍历为 O(P + C + S)，另加在途调用通知，S 为配置 Slot 总数；不声称已测量大拓扑关闭延迟。

**重连增加有界抖动。** 每次失败恢复等待基础延迟与 [0, jitter] 中新抽取整数之和。默认 jitter 为基础延迟的 25%（向下取整并按范围裁剪），零值保留固定时序。Builder 测试覆盖含端点范围、独立快照、负值/溢出拒绝与零；真实 TCP 集成覆盖首次失败恢复与独立 Slot 重连。抖动分散尝试，不保证尝试速率上限、指数退避或无限拓扑增长下的恢复。业务帧不会重发。

这些修复保持 wire v1 与 Core 错误编号。默认值现在限制接纳并分散重连时间，timeout onFail 从时间轮改到自有虚拟线程；依赖时间轮线程身份或原精确默认重试时间的应用需调整。跨语言互操作、持续生产容量、Node 重启代际、完整回绕迟到回复及规模下的双向同时恢复仍未验证。Runtime 接入、公开 TLS/WS 配置、认证与持久投递仍在实现范围外。
<a id="92-后续审阅与扩展候选"></a>

### 9.2 后续审阅与扩展候选

**已确认缺陷，尚未修复：恢复链停止时可能漏掉并发解绑。** [RpcNode.connect/reconnect](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java) 中，观察 Slot 已占用与清除 connecting 是两个独立操作；同时 disconnected 解绑连接，只有将 connecting 从 false 改为 true 成功才开启恢复。合法交错：旧出站尝试失败，而入站连接占用了同一 Slot；reconnect 看到该连接；另一连接 EventLoop 解绑它，看到 connecting 仍为 true，因而不启动新链；随后 reconnect 清除 connecting 并返回。主动 Peer 仍为当前对象，Slot 却为空且没有恢复任务。再次添加相同 Peer 可以重启维护，但正常自动恢复已停止。这违反 R-LIVE-03 的维护意图，不修改规范来允许此行为。

本地双线程诊断从“主动 Peer、READY 连接、已有恢复链”出发，调用真实 reconnect 与 disconnected 方法。三轮分别在 8953、18868、9209 次交错中各观察到 10 次丢失恢复链。这是合成竞态计数，不是生产发生率或真实网络负载基准。诊断源码/日志暂存 game-rpc/target/rpc-review2，root clean 会删除；永久确定性回归与修复仍待完成。修复需使恢复停止及空 Slot 复查与解绑形成一致操作，保留每 Slot 单链和旧 Peer 拒绝；仅增加抖动不能修复此竞争。

**Node 替换关联是已验证的 wire-v1 边界。** 真实 TCP 诊断保持 B 运行，关闭本地 A，再以相同 nodeId 创建新 A。旧 command 101 与新 command 202 都分配 requestId=1；B 经合法新连接返回保存的旧回复，A 将旧 body 交付 command 202，随后正确回复被丢弃。这是 R-CALL-04 已声明的 Node 生命周期边界，与已修复的同 Node 内 Peer 重建不同。Node 代际关联或更宽标识是未来协议设计候选；若重启仍重置分配，仅加宽 ID 不能解决重启复用。本文不选定 wire-v2 布局或要求。

**双向同时绑定仍需收敛验证。** 在已确认的“先合法绑定者获胜”策略下，受控握手顺序让 A、B 在处理出站握手回复前，各自绑定不同物理连接的入站一端；随后两条出站候选都以 Duplicate slot 失败。传递其断开后，两边 Slot 均为空并等待恢复。这只证明一轮恢复失败，不证明永久活锁或生产发生率。永久真实 TCP 回归应覆盖同时 addPeer、同时断开/恢复，以及零与非零抖动。当前契约仍不采用 Node-ID 顺序仲裁，此次审阅不恢复该方案。

**容量与执行边界仍存在：** maxPendingCalls 在整个 Node 合计本地发起调用及未返回完成处理器，不提供 Peer 间公平性、字节预算、入站调用、被动 Peer 或未完成入站握手的上限。单个静默 Peer 或阻塞完成处理器可占满共享额度，导致健康 Peer 的调用被拒绝。请求/响应处理器与已匹配响应的协议失败仍在连接 EventLoop 执行；将业务投递到其他机制时，须 retain/copy 借用 body，并覆盖投递接纳与拒绝两条释放路径。每个 Node 自有 NIO groups，同 JVM 部署大量 Node 时需资源规划。抖动不提供全局建连速率上限。长期压力、大 body、慢 Peer、入站突发、反复恢复与关闭延迟需测量，现有测试不证明生产容量。

**有用的扩展候选，尚未实现且不是生效要求：** 不可变 Peer/Slot 就绪快照与状态事件；pending/admitted 数量、拒绝原因、超时、未匹配回复、重连和握手失败计数；可选 Peer 级与入站接纳；应用/Runtime 业务投递接入；拒绝新工作、允许在途响应与 reply 持续到期限的 drain 阶段；明确解码后 body 所有权的本地可取消调用句柄或 CompletionStage 接入。drain 期限届满不能隐式削弱现有 close 屏障，本地取消不表示远端执行停止。Node 代际 Wire Profile 与 TLS/身份授权应由明确的滚动重启或信任边界需求驱动。发现/路由、业务自动重试、接收去重和持久投递仍在当前 RPC 核心范围之外，实施前需单独明确契约。

优先处理恢复正确性及其回归，再完善就绪/诊断与经过测量的接纳。减少分配、发送/flush 批处理或内部拆分应依据测量，并保持所有权、接纳和完成顺序。RpcHandler 中过时的线程注释已更正为当前虚拟线程超时通知契约，此次审阅不改变运行行为。
