# OGBS RPC Java 25 Development Specification 1.0

文档类型：**Java 开发规范**。对应 [RPC 标准规范](OGBS-RPC-1.0.md)，字节布局见 [Wire Profile](../rpc-wire.md)。

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
| cn.managame.rpc.example | 可运行 RpcEchoExample |

按用户确认保留职责分包。为避免跨包暴露 requestId setter 或内部访问桥，消息沿用只读 record 形式；节点给编码器传入内部生成的 ID，**不回写调用方 Request**。公开完整值构造器可表示已解码消息，但 call/notify 拒绝非零 requestId。与会话中单包可变对象的 Java 草图有此区别，Wire 与调用行为不变。

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
| reconnectDelay(Duration) | 1 秒 | 同上 |
| heartbeatInterval(Duration) | 10 秒 | 同上 |
| heartbeatTimeout(Duration) | 30 秒 | 必须大于 interval |
| maxFrameSize(int) | 4 MiB | >=32，包含长度前缀 |

Duration.toMillis 的溢出同步抛 ArithmeticException；低于 1ms 或超过 Long.MAX_VALUE 纳秒的配置抛 IllegalArgumentException。单次 timeoutMillis 同样校验。

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

RpcHandler 必须线程安全、快速返回。request/response 通常在 Netty EventLoop；立即失败在调用线程；timeout 在时间轮；remove/close 失败在管理线程。没有 callbackExecutor，也不自动恢复 Runtime Context。

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
3. Peer AtomicInteger 分配 ID，跳过 0；编码最终 frame。
4. 通过 ConcurrentHashMap.putIfAbsent 注册 PendingCall；碰撞不覆盖、不扫描。
5. 再次检查节点和 Peer 对象身份，失效则完成失败。
6. 按亲和性和 fallback 尝试发送，同一个 frame 最多接纳一次。
7. ACCEPTED 后以 HashedWheelTimer 注册 timeout，写入 volatile Timeout。
8. 重新检查 PendingCall 仍在 Map 中，否则取消刚注册的 Timeout。

PendingCall 仅保存 ID、command、callback、volatile Timeout。条件 remove 决定超时/移除/关闭/发送失败的完成权；Response 读取 ID 后 remove，取消 Timeout，再解析余下响应。未匹配直接丢弃；匹配后解析失败仍须通知 PROTOCOL_ERROR。

不设置 maxPendingCallsPerPeer 或维护消息重试队列。调用量与超时时间决定内存占用，生产需测量。取消的时间轮任务由 Netty 清理，不保留到原 deadline 的 DelayQueue 方案已废弃。

## 6. 网络装配与 Peer 并发

RpcWire.install 安装 LengthFieldBasedFrameDecoder(maxFrameSize,0,4,0,4)，随后安装 IdleStateHandler；因此完整帧驱动 Read Idle，未完成帧的零散字节不能无限保持活性。Network 适配收到已剥离 4 字节长度前缀的 ByteBuf。

Network 已保证客户端 onConnected 先于 ConnectCallback.onSuccess：前者安装 AttributeKey<RpcConnectionContext> 与握手超时，后者关联 expectedPeer/expectedSlot 并发起握手。不会在两个回调中重复绑定 Slot。

每个 Node 一个 HashedWheelTimer，tick=10ms，负责调用超时、RPC 握手超时、固定延迟重连。心跳由各连接 IdleStateHandler 负责。时间轮与 EventLoop 不执行阻塞业务。

Peer 为 ConcurrentHashMap<int,RpcPeer>。低频 start/close/add/remove/handshake 的拓扑修改使用生命周期锁；同 ID 被动创建、升级与清理用 compute 仲裁。热发送不获取生命周期锁。

Slot 用 AtomicReference<Connection> 绑定/身份解绑；AtomicBoolean connecting 覆盖整个恢复链。旧 Peer 的异步任务检查 Map 中对象身份，无额外 removed flag。活动 Peer 的目标地址为 volatile；断线不会清理仍在等待的调用。

RPC context 通过原生 Netty AttributeKey 存储。节点另持有自有连接集合，仅用于关闭与未完成握手清理，不向业务提供 ConnectionManager。

## 7. 关闭屏障与线程边界

close 在生命周期锁内先设置 CLOSED、detach 当前 peers，然后在锁外等待已接纳 API 操作完成、终止 PendingCall/Slot、关闭所有未完成握手和连接、NetworkServer/Client、自有 boss/worker group、时间轮。不会持有拓扑锁等待 EventLoop。

为覆盖“close 清空 Map 后发送线程才注册 PendingCall”的竞争，API admission 使用 Phaser 登记；它不是业务任务队列，也不改变路由。并发 close 通过 CountDownLatch 等待同一次清理。应用抛 RuntimeException 不打断其他资源清理；资源关闭异常记录并继续。

start/close 不允许从本 Node RpcHandler、EventLoop 或时间轮执行；调用在修改生命周期前抛 IllegalStateException，避免自等待。管理线程上的关闭回调再次 close 为幂等。应由独立管理线程停服。

close 返回后不再保留 PendingCall、RPC 网络连接或时间轮；它不等待应用投递至 Runtime/其他执行器的任务。先按业务策略停止入口并处理业务工作，再关闭 RPC。启动失败不能再次 start。

## 8. 可运行示例与源码

[RpcEchoExample](../../game-rpc/src/main/java/cn/managame/rpc/example/RpcEchoExample.java) 启动两个本地随机端口节点，完成 TCP 握手、call、应用字符串解码与回复。示例在 reply 前 retain 入站 body，并用 try-with-resources 清理节点。

[RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java)、[RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java)、[RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java) 是主要实现入口。

RpcWire 公开 install、encodeRequest(request,assignedId,maxFrameSize)、encodeResponse、encodeHandshake、encodeHeartbeat 和对应 decode 方法。encode 消费输入 body，decode 返回依附输入 frame 的借用 slice；调用方须遵守 Javadoc 的 type/ID 读取位置。直接使用 RpcWire 不具备 RpcNode 的握手状态、Peer 或完成仲裁，不能绕过语义层宣称完整 RPC 实现。

## 9. 验证与未实现范围

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java)：四类黄金向量、uint32/uint64、Metadata、引用计数、长度边界、分片/粘包、损坏帧。
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java)：Slot 回退、快速响应、调用竞争、ID 回绕碰撞、被动生命周期、心跳拒绝、握手超时、Handler 异常及关闭屏障。
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java)：真实 TCP 双向通信、独立重连、跨 Slot 回复、主动恢复与被动升级。
- [RpcExampleTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcExampleTest.java)：完整示例执行。

本地定向命令为 mvn -pl game-rpc -am test；依赖/跨组件接入变更必须根目录 mvn clean verify。Windows 测试沿用 Network 的 Selector TCP 唤醒兼容设置，生产代码不修改 JVM 系统属性。

未实现：自动 Runtime 接入、TLS/WS RPC Builder、服务发现、Router、重试、远端取消、持久投递。未验证：真实跨语言互操作、生产吞吐/内存上限、长期压力和公网部署。测试通过不代表这些能力已提供。

