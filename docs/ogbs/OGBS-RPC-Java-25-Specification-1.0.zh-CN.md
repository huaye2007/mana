# OGBS RPC Java 25 Development Specification 1.0

[English](OGBS-RPC-Java-25-Specification-1.0.md) | **[简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应 [RPC 标准规范](OGBS-RPC-1.0.zh-CN.md)，字节布局见 [Wire Profile](../rpc-wire.zh-CN.md)。

状态：已实现并通过本地验证。JDK 25，无 preview；Maven `cn.managame:game-rpc:1.0.0-SNAPSHOT` 依赖 game-core、game-network（由此传递 Netty）。RPC 不依赖 Runtime、Data、Spring、业务 codec 或服务发现；可选 Runtime 接入见 [game-spring](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)。

## 1. 包与封装

| 包 | 类型与职责 |
| --- | --- |
| cn.managame.rpc.node | RpcNode、RpcNodeBuilder；包级 RpcPeer、ConnectionSlot、PendingCall、RpcConnectionContext、RpcConnectionHandler |
| cn.managame.rpc.message | 只读 RpcRequest、RpcResponse、RpcHandshake |
| cn.managame.rpc.call | RpcHandler、RpcCallback |
| cn.managame.rpc.transport | RpcSendStatus |
| cn.managame.rpc.error | RpcErrorCodes、RpcException、RpcEncodeException |
| cn.managame.rpc.netty | 公开 RpcWire；包级 RpcEncoder、RpcProtocol |

消息是只读 record。调用 ID 为正数，到 Integer.MAX_VALUE 后回绕到 1；每个 Node 实例的分配起点随机，重启后的 Node 不会重新发放对端可能仍持有的 ID（R-CALL-04）。Node 在编码时分配 wire 请求 ID，不修改调用方的 Request；call/notify 拒绝非零 requestId。RpcWire 是独立的 Wire Profile 编解码器，不含 Peer、Slot 或计时器。可运行示例位于 game-demo（`cn.managame.demo.examples.rpc`）。

Router 等上层在构造时组合一个普通 RpcHandler（见 [Router 职责边界](OGBS-Router-1.0.zh-CN.md#1-职责与组合)）。RPC 不增加 Router 专用的连接包装、状态或生命周期钩子；新增公共集成 API 需要现有操作无法满足的具体需求。

## 2. RpcNode 与 Builder

```java
RpcNode node = RpcNode.builder()
    .nodeId(1)
    .bindAddress(new InetSocketAddress("127.0.0.1", 9000))
    .handler(handler)
    .build();

void start();
SocketAddress localAddress();
void addPeer(int nodeId, SocketAddress address, int slotCount);
void removePeer(int nodeId);
boolean isPeerConnected(int nodeId);
int peerSlotCount(int nodeId);
RpcSendStatus notify(int nodeId, RpcRequest request);
<T> void call(int nodeId, RpcRequest request, RpcCallback<T> callback);
<T> void call(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback);
RpcSendStatus reply(int nodeId, int sourceSlotId, RpcResponse response);
RpcSendStatus forwardResponse(int nodeId, long routeKey, RpcResponse response);
void close();
```

nodeId、bindAddress、handler 必填。nodeId 非零；int 保存 uint32 原始位，负数也是合法 ID。Builder 非线程安全，build 只快照配置。

| Builder 方法 | 默认值 | 边界 |
| --- | --- | --- |
| callTimeout(Duration) | 5 秒 | ≥1 ms 且可表示为 long 纳秒；单次 timeoutMillis 范围相同 |
| handshakeTimeout(Duration) | 5 秒 | 同上 |
| reconnectDelay(Duration) | 1 秒 | 正的基础延迟 |
| reconnectRandomDelay(Duration) | 基础延迟 / 4 | 每次重试附加的非负上限（均匀整毫秒）；0 为固定延迟。1000 ms + 250 ms 时等待 1000..1250 ms |
| heartbeatInterval(Duration) | 10 秒 | 正值 |
| heartbeatTimeout(Duration) | 30 秒 | 大于 interval |
| maxFrameSize(int) | 4 MiB | ≥32，含长度前缀；`RpcNode.maxFrameSize()` 返回该值，Router 等组合组件据此计算自身上限，不另设配置 |
| maxPendingCalls(int) | 65536 | ≥1，每个 Peer 的待完成 call 上限（R-SEND-04）；达到上限的 call 以 onFail(UNAVAILABLE) 立即失败，body 已释放；notify/reply 不计入 |
| maxInboundPeers(int) | 1024 | ≥1，入站握手学到的 Peer 上限（R-HS-06）；超出后宣告未知 Node 的握手被拒绝并关闭其连接，已接纳的 Peer 不受影响；addPeer 显式配置的 Peer 不计入 |
| maxConnections(int) | 4096 | ≥1，Node 同时持有的连接数上限，自己拨出与接受的一起计入（R-HS-06）；超出的连接在握手前被关闭，限制拨号风暴在握手超时清理之前的占用 |
| eventLoopGroup(EventLoopGroup) | 无（Node 自建） | 调用方持有的事件循环，例如与客户端网关共用；Node 不关闭它，close 仍会等待自身连接结束（最多 5 秒）。未设置时 Node 按平台自建 Epoll 或 NIO 事件循环 |
| writeBufferWaterMark(low, high) | Netty 32 KiB / 64 KiB | 超过 `high` 连接不可写，call/notify 转到下一 Slot、全部不可写返回 UNAVAILABLE，reply 没有其他 Slot 可用（R-SEND-01）；内部链路通常需要调大 |
| channelOption(option, value) | 无 | 作用于入站和出站连接（TCP_NODELAY、SO_SNDBUF 等） |
| transport(Consumer&lt;ChannelPipeline&gt;) | 无 | 放在 RPC 分帧之前的字节级 handler：SslHandler（addFirst）、流量整形、日志；不得解码 RPC 帧 |
| handshakeSecret(byte[] [, Duration]) | 无 | ≥16 字节，全集群一致；时钟偏差默认 60 秒 |

Duration.toMillis 溢出抛 ArithmeticException；其他越界值抛 IllegalArgumentException。RpcNode 自行持有 TCP NetworkServer/NetworkClient；EventLoopGroup 默认由 Node 按 [NetworkTransports](OGBS-Network-Java-25-Specification-1.0.zh-CN.md) 自建（Linux 加载到原生库时为 Epoll，否则 NIO），也可通过 eventLoopGroup 传入调用方持有的组。分帧、心跳、握手留在内部。

`start` 只能调用一次：创建计时器和事件循环，绑定成功后才进入 RUNNING；失败时关闭已创建资源并抛 RpcException。`localAddress` 绑定前为 null，绑定后返回实际（可能是动态分配的）端口。

`addPeer` 要求 RUNNING、非零且非自身 ID、地址和 1..255 个 Slot。相同配置重复调用幂等；地址或 Slot 数不同抛 IllegalStateException。一对节点只有一侧调用 addPeer：对已主动连入的节点调用会抛 IllegalStateException，本侧已拨号的节点发来的入站握手会被拒绝。`removePeer` 不存在的 Peer 时不做任何事。

<a id="直连转发与基础设施扩展"></a>
<a id="直连转发与-handler-组合"></a>
<a id="direct-forwarding-and-handler-composition"></a>
<a id="handler-组合与-node-通知"></a>

### Handler 组合与可用性查询

RpcHandler 只有 onRequest、onResponse、onFail，构造时固定；没有修改 Handler 的 API、就绪监听或清理监听。`isPeerConnected` 表示该 Peer 是否有任一 Slot 存在活动连接（NEW/CLOSED 时为 false）；`peerSlotCount` 返回配置的 Slot 数或 0。两者都是无锁快照，不代表可写、送达或远端进程身份。被动 Peer 在既无连接也无待完成调用时消失。服务是否存在由外部服务发现负责。

## 3. 消息与 ByteBuf 所有权

```java
public record RpcRequest(int command, int requestId, long routeKey, int businessIdType,
                         long businessId, Metadata metadata, ByteBuf body) {}
new RpcRequest(command, body);
new RpcRequest(command, routeKey, businessIdType, businessId, metadata, body);

public record RpcResponse(int requestId, int errorCode, Metadata metadata, ByteBuf body) {}
```

command 非零，businessIdType 为 0..255；请求的 requestId 为 0 或正数，响应要求 requestId 为正数，errorCode ≥ 0。构造非法时抛 IllegalArgumentException，不释放 body。Metadata 为 null 时视为空；body 为 null 表示空负载。

| 边界 | 所有权 |
| --- | --- |
| 参数、null 或生命周期校验失败 | 调用方保留 body |
| 校验通过的 call/notify/reply | 之后每条路径恰好消费一个 body 引用 |
| Peer 不存在或无候选连接 | 不编码，直接释放 body |
| 编码（成功或失败） | 编码器消费 body；成功时只剩帧。超过 2 KiB 的 body 成为帧的一部分，帧写出前其引用计数仍为正：调用返回后绝不能再释放 body，只有在校验阶段抛出异常时才由调用方释放 |
| Network 返回 ACCEPTED | 帧归 Network 所有，不再回退 |
| 所有连接都拒绝 | RPC 释放帧 |
| 入站 onRequest/onResponse | body 为借用；异步使用前需 retain 或复制 |

编码器先校验总长度再分配。不超过 2048 字节的 body 复制到帧头之后的同一缓冲区；更大的 body 不复制：帧是帧头与 body 的组合缓冲区，body 的引用转入其中。非空的自定义 Metadata 须通过 Core 结构校验。编码失败在释放自身持有的资源后抛 RpcEncodeException。

入站 Metadata 被复制并校验；body 是帧的 slice，回调结束后由 Network 释放帧。即使同步回显，也须先 retain 再交给 `reply`。

## 4. Handler、Callback 与异常

```java
public interface RpcHandler {
    void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request);
    void onResponse(int sourceNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}
@FunctionalInterface
public interface RpcCallback<T> { void onResponse(T response); }
```

Handler 须线程安全并尽快返回。请求、响应以及在连接上发现的失败（响应格式错误、Peer 失去最后一条连接）运行在 Netty EventLoop；立即发送失败运行在调用线程；移除与关闭运行在管理线程；超时在 Node 的计时器线程上通知。框架只负责发出通知：Handler 若不是很短的处理，应交给自己的执行器（例如 Spring 适配层会投递到调用方的 Route），因为阻塞计时器线程会推迟后续超时、重连和心跳。所有远端响应（包括框架错误和业务错误）进入 onResponse，只有本地失败进入 onFail；由应用解码并调用自己的 RpcCallback。

onRequest 抛出 RuntimeException 时记录日志；对 Call 尝试回复空的 HANDLER_ERROR 响应，连接保持。onResponse/onFail 的异常只记录，不会二次通知。堆栈不会发给对端。

| 情况 | Java 结果 |
| --- | --- |
| 必填参数为 null | NullPointerException |
| 参数、超时或 Slot 范围非法 | IllegalArgumentException |
| NEW 或 CLOSED 状态调用 API | IllegalStateException |
| notify/reply 结果 | RpcSendStatus ACCEPTED / PEER_NOT_FOUND / UNAVAILABLE |
| call 时无 Peer 或无可写 Slot | onFail(PEER_NOT_FOUND / UNAVAILABLE) |
| Peer 失去最后一条连接 | 其待完成调用立即 onFail(UNAVAILABLE) |
| 超时 / removePeer / close | onFail(TIMEOUT / PEER_REMOVED / NODE_CLOSED) |
| 编码失败 | 同步抛 RpcEncodeException，body 已消费 |
| ID 回绕后冲突 | 同步抛 RpcException，保留旧调用 |
| 已匹配响应格式错误 | onFail(PROTOCOL_ERROR)，关闭该连接 |
| Network 写入同步抛异常 | 清理该调用和帧，关闭连接，异常向上抛出 |

`reply` 校验 sourceSlotId（0..254 且小于该 Peer 的 Slot 数）；失败时 body 仍归调用方。响应只走该 Slot（R-SEND-01）：该 Slot 的连接已断或不可写时返回 UNAVAILABLE 并释放 body，调用方的 call 随后按自身超时结束。`forwardResponse` 是中继入口：不接收来源 Slot，按 routeKey 像 notify 一样选 Slot，供 Router 投递并非自己产生的响应。RpcErrorCodes 引用 Core 的错误码。

## 5. call 顺序

1. 校验参数，进入关闭屏障，接管 body。
2. 无 Peer 或无候选连接时释放 body 并通知失败。
3. 分配 Node 范围的请求 ID（不为 0，Peer 重建时不重置）并编码。
4. 用 putIfAbsent 登记 PendingCall；冲突时抛异常，不覆盖。
5. 再次确认 Node 仍在运行且 Peer 未被替换，否则让该调用失败。
6. 按亲和与回退发送；同一帧最多被接受一次。
7. ACCEPTED 后登记超时；若调用已完成则取消该超时。

响应、超时、移除、关闭、发送失败与最后一条连接丢失通过条件删除竞争，每个调用恰好完成一次。多个 Slot 中只丢一个时保留待完成调用：响应可能从其他 Slot 返回。每个 Peer 的待完成调用数受 maxPendingCalls 限制（R-SEND-04）：登记前预留名额，任何完成路径（响应、超时、移除、关闭、发送失败）在条件删除成功时归还；ID 冲突等同步失败也归还预留。没有字节上限：单个调用的大小仍受 maxFrameSize 约束。

## 6. 网络装配与并发

pipeline 顺序：应用的 `transport` handler，然后是 `rpc-flush`（Netty FlushConsolidationHandler(256, true)，把并发发送方的多次 flush 合并为每轮 EventLoop 一次写出），然后是由 `RpcWire.configurePipeline` 加入的 `rpc-frame`（LengthFieldBasedFrameDecoder）和 `rpc-idle`（IdleStateHandler）。只有完整的帧才会刷新读超时。

每个 Node 一个 HashedWheelTimer（10 ms tick），负责调用和握手超时以及延迟重连；调用超时的 onFail 直接在该线程上通知（见上文 Handler 线程规则），业务处理须由 Handler 转交到自己的执行器。Peer 存放在写时复制的 int→Peer 表中：发送按原始 int 查找，不装箱、不加锁；拓扑变化（start、close、addPeer、removePeer、握手绑定、被动 Peer 回收）在生命周期锁内复制并发布新表，发送从不获取该锁。每个 Slot 用 AtomicReference 保存连接（按身份绑定与解绑），用 AtomicBoolean 标记唯一的恢复链（延迟、连接、握手）。某个 Slot 的连接结束时，若该 Peer 已没有任何连接，Node 让其待完成调用失败，然后为主动 Peer 启动恢复。

## 7. 关闭屏障与线程边界

每个服务使用一个长期存在的 RpcNode：启动一次，并由管理线程关闭一次。远端重启或连接失败不影响本地 Node；不支持暂停、恢复或关闭后复用。

`close` 在生命周期锁内设置 CLOSED 并移除所有 Peer，等待已准入的 API 操作退出（使用分段 LongAdder 计数，并发发送方不争用同一变量，也没有参与方数量上限），以 NODE_CLOSED 结束待完成调用，关闭连接、监听、客户端和事件循环，并停止计时器（会等待正在执行的超时通知结束）。并发 close 等待同一清理；资源关闭异常记录日志后继续。在本 Node 的 Handler、EventLoop 或计时器中调用 start/close 会抛 IllegalStateException，防止自我等待。

close 返回后不再有待完成调用、连接、计时器或自有通知线程。应用派发到别处的工作不会被等待：先停止业务准入，再关闭 RPC。

## 8. 示例与源码

[RpcEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) 运行两个 Node，演示调用、解码和 retain 后的回显回复，由 RpcExampleTest 执行。源码：[RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java)、[RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java)、[RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java)。RpcWire 提供 configurePipeline、各消息类型的编解码与握手证明，并公开帧布局常量（`TYPE_OFFSET`、`REQUEST_ID_OFFSET`、`REQUEST_ROUTE_KEY_OFFSET`、`RESPONSE_ID_OFFSET`、`MIN_REQUEST_FRAME`、`MIN_RESPONSE_FRAME` 等），供 Router 等组件只读路由头而不重复定义偏移；直接使用时没有 Node 的握手状态、Peer 与完成仲裁。

## 9. 验证与边界

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java)：黄金向量、整数范围、Metadata、引用计数、长度限制、分片与非法帧。
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java)：Slot 回退、完成竞争、ID 回绕、最后连接丢失、大 body 共享、被动 Peer 生命周期、握手超时、Handler 异常、关闭。
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java)：真实 TCP、重连、跨 Slot 响应。
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java)：Peer 重建、恢复归属竞争、超时隔离、未完成握手。
- [RpcSecurityTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcSecurityTest.java)：共享密钥、重放/过期/伪造握手、transport 顺序、水位线。

运行 `mvn -pl game-rpc -am test`；跨模块变更需要根目录 `mvn clean verify`。

<a id="91-审阅确认的缺陷与规模风险"></a>
<a id="92-后续审阅与扩展候选"></a>
<a id="rpc-router-review-2026-10-07"></a>

剩余边界：关闭时按 Peer 扫描全部连接；wire 不携带 Node 代际，新进程重新从头分配 ID 时可能匹配到对端保存的旧回复（R-CALL-04）。响应现在只走承载请求的那个 Slot（R-SEND-01），窗口已收窄但未关闭；要关闭它需要调用方只接受自己发出调用的那条连接上的响应，而 Router 把路由响应经自己持有的任意一条连接转交给调用方，Router Profile v4 也不携带调用方 Slot，因此需要新增 wire 字段或 Router 状态。Handler 运行在 EventLoop 上，阻塞会拖慢该 EventLoop 上的所有连接。未实现：TLS/WebSocket Builder 选项、服务发现、业务重试、远端取消、持久投递。未验证：跨语言互通、生产吞吐与内存。Router 在现有 Node 上组合，除普通 API 外只使用 `isPeerConnected` 与 `forwardResponse`（见[审查记录](../reviews/2026-10-07-router-rpc-review.zh-CN.md#rpc-integration)）。
