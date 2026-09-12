# game-rpc Java 实现文档

**版本：** Java Binding Draft 0.14  
**日期：** 2026-09-12  
**技术基线：** JDK 25、game-network Java API、Netty 4.2.15.Final

本文只规定 game-rpc 项目。语言无关的消息语义与默认 Wire 见 [Game RPC 标准](../OGBS%20Game%20RPC%20Specification%20v1.md)，快速接入见 [README](../../README.md)。

## 1. 项目边界

RpcNode 维护 NodeId → RpcPeer 映射。RpcPeer 表示登记的直接相邻节点，持有固定连接数组和该节点的 PendingCall。Core 不识别 Router 服务，不负责服务发现、下一跳查询、业务消息转发或跨节点端到端关联。

不存在 Builder.routes、Builder.allowedSources、RpcPeer.route、路由目标 Peer、路由查询准备集合或 RpcRoutedRequest。未知 NodeId 的 Call/Send 快速失败，不临时建 Peer、不查询其他组件、不借用别的 Peer 的连接。

RpcRouteMessage 保留为普通原始信封格式，仅由 Codec 编解码外层并交付 Handler。其 sourceNodeId/targetNodeId 和 inner 都由业务解释；Core 不比较目标、不校验声明来源、不自动解码 inner 或发送到其他节点。信封中的 Response 不会自动匹配 RpcFuture。

业务类型映射、序列化、业务调度、压缩、加密、签名和其他项目的实现不在本文范围内。Core 不增加协议注册表、RpcBodyCodec、RpcMessageProcessor 或业务执行上下文。

## 2. 模块和状态归属

| 类/模块 | 职责及状态 |
| --- | --- |
| game-rpc-core | 公共 API、消息数据、Metadata、默认 Codec、Peer/Pending 生命周期 |
| game-rpc-netty | 默认网络 Provider、TCP 分帧及帧发送 |
| RpcNode | 对外 API、组件装配、启动/关闭与自有资源释放、诊断汇总 |
| RpcCalls | 节点级 requestId、调用登记/计时/响应关联/完成；调用提交前检查 deadline 与生命周期 |
| RpcMessages | 统一消息编解码入口、发送与回复流程、响应编码失败策略、业务消息交付 |
| RpcConnections | NodeId → RpcPeer、主动建连状态、握手/心跳/重连、准入和连接选择；连接集合只用于生命周期管理 |
| RpcConnections.ConnectionHandler | 每物理连接一个 NetworkHandler，持有连接身份、握手和心跳状态，过滤控制消息及失效连接后交付 RpcMessages |
| RpcPeer | 逻辑节点身份、就绪连接数组、轮询位置、Pending 表及准入、生命周期状态 |
| RpcFuture | 单次 Call 的不可变 requestId、deadline、callback、Timeout 句柄及一次性完成状态 |
| RpcCodecHandler / RpcChecks | 统一调用 Codec，执行与 Codec 无关的消息字段、Metadata/body 限额及编码输出校验；不制定响应策略 |
| DefaultRpcCodec | 默认 Request/Response/RouteMessage/Handshake/Heartbeat 字节布局 |
| RpcMetadata / RpcMetadataUtil | put/get 门面与字节存储、编码、校验实现 |
| RpcNetworkConfig / RpcNetworkProvider | 不可变网络配置与 Transport 创建；不接收整个 Node |
| NettyRpcTransport | TCP 客户端/监听、共享资源借用、物理 Channel 写入及失败处理 |
| NettyRpcFrameCodec | 双向 TCP 长度前缀及帧边界校验 |

RpcPeer 不持有其他 Peer。连接数组长度在 Peer 生命周期内固定；断线不压缩数组。connections[] 只发布握手就绪的 Connection。候选连接由其 ConnectionHandler 持有；不再额外分配 Binding。

每个 Node 装配一个 RpcCalls，独占该节点的 AtomicInteger 编号分配；RpcPeer 不保存计数器引用。RpcCalls 提供候选 ID，Peer.tryRegister 在内部原子检查生命周期、容量和 ID 冲突，然后创建并登记 Future。占用的候选返回 null，由 RpcCalls 继续分配；失败候选不创建 Future。RpcCalls 不持有 Peer 的外部锁，不分步操作 Pending 表。物理重连和 Peer 重建都不重置节点计数器。

RpcPeer 只保留 7 个实例字段：nodeId、maxPending、connections、pendingCalls、roundRobin、closed、established。closed 表示整个逻辑生命周期结束；established 表示曾有连接握手成功，普通断线不能将其清零。二者分别用于阻止退役 Peer 接收调用、阻止尚未建立的临时 Peer 登记调用。Pending 的准入和退役在 Peer 自身锁内互斥，已发布连接使用 AtomicReferenceArray，普通读写不取得拓扑锁。

远端地址、方向冲突、建连回调和每 Slot 的尝试/重连任务归 RpcConnections.Outbound。RpcConnections 用按 RpcPeer 实例区分的 IdentityHashMap 保存仅主动连接的 Outbound；这是受拓扑锁保护的建连索引，普通 Call/Send/Reply/接收/心跳路径不查询它。接收侧 Peer 不创建 Outbound、不分配重连数组或等待者。既有 Peer 没有 Outbound 即为入站方向，不另存 accepting 标记。

Outbound 的地址是未解析的 InetSocketAddress，不持有 RpcNode 或 RpcPeer 引用。每 Slot 另保存一个 long 退避上限，只有实际安排重试时更新，握手成功时归零，不进入普通收发路径。一个 Slot 只保存一个任务身份：等待重连时为 Timeout，开始建连后替换为新的尝试标记，结束时清空；不再同时保存 attempts[]、retries[]。就绪/失败只清理匹配的尝试，方向冲突取消全部尝试并保留失败原因。移除/关闭在拓扑锁内分离连接回调、取消任务并删除 Outbound，随后锁外关闭和通知。迟到断连只按已知 Slot 和 Connection 实例清理，不能清掉替代连接；旧尝试不能修改新 Peer 的 Outbound。

RpcConnections 通过构造参数获得 Transport、Timer、必要配置、生命周期查询和诊断入口；ConnectionHandler 通过 RpcMessages 发送握手与心跳。RpcCalls、RpcMessages 和 RpcConnections 均不持有整个 RpcNode。共享的 RpcObserver 只定义诊断回调，不要求消息路径创建事件包装对象。

ConnectionHandler 合并了原匿名 NetworkHandler 和 Binding。它在 onConnected 时通过 Connection 属性关联自己，握手成功后才公开 Peer 身份。ConnectionHandler 持有实际 Peer 实例和 Slot，nodeId、连接数从 Peer 取得；成功、断连或关闭后取消并清空握手 Timeout、attempt 等临时状态。正常收发直接使用 Handler/Connection 属性，不按 connectionId 查询连接集合。集合仍以 connectionId 检查重复登记，并在拓扑锁内维护、遍历和移除。

RpcFuture 不持有 RpcNode、RpcPeer 或 targetNodeId，也不保存 RpcRequest、body、Metadata、响应结果或业务上下文。它不访问 Pending 表、不检查节点、不选择执行线程、不记录诊断；只提供单次完成状态和可取消的超时句柄。完成时取消已安装的句柄；句柄晚于完成安装时立即取消。所有者决定何时通知 callback，Future 自身不执行回调。

底层 Connection 不增加 RPC 请求状态。RPC 使用 game-network 的属性接口关联握手身份；connectionId 使用 game-network 提供的不透明 String。

## 3. 创建、启动和连接

```java
var rpc = RpcNode.builder()
        .nodeId(10)
        .listen("127.0.0.1", 7000)
        .handler(handler)
        .defaultTimeout(Duration.ofSeconds(3))
        .build();
rpc.start();
rpc.connect(20, "127.0.0.1", 7001);
```

nodeId、监听地址、Handler 和默认超时必须配置。listenPort=0 可由系统选择端口。build 装配 Core 和 Provider；start 同步初始化 TCP 客户端并绑定监听，正常返回表示本地可用。远端连接在后台进行，不能使用 start().join()。STARTING 期间不接受 RPC 握手，提前到达的连接被关闭，发起方按既有重连策略重试；Transport.start 正常返回后才发布 RUNNING，此后握手和业务收发统一使用运行状态，避免对端先收到 ACK、业务消息却被启动状态丢弃。

只有 game-rpc-netty 在依赖中时，通过 ServiceLoader 加载默认 Provider；无 Provider 或有多个 Provider 时必须显式配置。构建过程不发起业务连接。

```java
void connect(int nodeId, String host, int port);
void connect(int nodeId, String host, int port, int connectionCount);
void connect(int nodeId, String host, int port, ConnectCallback callback);
void connect(int nodeId, String host, int port, int connectionCount, ConnectCallback callback);
void removePeer(int nodeId);
RpcPeer peer(int nodeId);
RpcPeer peer(Connection connection);
```

connect 必须在 start 后调用，登记主动相邻节点。普通网络失败按以 reconnectDelay 为初始上限的退避策略重试，不无限存储待连接业务消息。目标在连接表中存在不代表已就绪，Call/Send 仍检查连接。

带 ConnectCallback 的调用，在所有 Slot 首次就绪后通知一次；返回最后就绪的连接，已经全部就绪时使用 Slot 0。重连不重复通知。移除、关闭和方向冲突失败通知待完成回调。配置错误同步抛出，不先执行回调。连接回调在完成事件的当前线程直接通知；已经就绪的 connect 在调用线程通知。锁内只取出等待列表，锁外完成必要清理后通知；RPC 不投递业务线程池，回调应自行转交业务线程并及时返回，异常隔离。

每个 Peer 最多保留 64 个等待就绪的 ConnectCallback；超限同步抛出携带 OVERLOADED 的 RpcException，不登记也不通知被拒绝的回调。成功、移除、关闭或方向冲突会释放等待列表。无需等待结果时重复调用不带回调的 connect，不额外保留等待者。

rpc.peer(connection) 返回此物理连接曾验证且仍属于当前生命周期的相邻 Peer；普通断线保留身份供延后回复使用，Peer 移除或替换后返回 null。Core 不根据 RouteMessage 的业务地址改变该结果。

服务发现可以决定何时调用 connect/removePeer；game-rpc 不订阅或查询服务发现。接收端自动接纳符合 peerAdmission 的握手，无 acceptPeer/addPeer 前置步骤。

## 4. 发送与回复 API

```java
void call(int targetNodeId, int command, ByteBuf body, RpcOptions options, RpcCallback callback);
boolean send(int targetNodeId, int command, ByteBuf body, RpcOptions options);
boolean send(int targetNodeId, RpcMessage message, RpcOptions options);
boolean reply(Connection connection, RpcMessage response);
```

所有 targetNodeId 都指向登记的直接相邻节点。call 创建并登记 RpcFuture；按 command 发送的 send 构造 requestId=0 的通知。未知节点调用回调 UNAVAILABLE，发送返回 false；不会隐式登记新节点、分配请求编号或编码消息。

完整消息 send 是显式发送入口：可以发送 RpcRequest、RpcResponse 或 RpcRouteMessage，不自动登记 RpcFuture或改写已有头字段。只使用 options.routeKey 选择该 Peer 内的连接，消息头取自 message；options 的 timeout/Metadata/busId/busType 不再次覆盖完整消息。RpcHandshake 只能由 Core 内部发送，公共入口拒绝。

reply 接收完整 RpcResponse，或业务自行构造的 RpcRouteMessage。Core 不解释信封内层、不反转地址、不校验信封来源。普通 Response 中的 requestId 必须为原 Call ID。

```java
// request 是 Handler 收到的 RpcRequest；responseBody 已由使用方编码。
rpc.reply(connection, new RpcResponse(
        request.requestId(), 0, RpcMetadata.EMPTY, responseBody));
```

Handler 返回和抛异常都不自动回复。调用者决定前后消息的提交顺序并避免重复回复。原连接断开后，仅允许沿同一 Peer 生命周期、同一 Slot 的新连接回复；背压不换槽，旧 Peer 身份不能回复新生命周期。

boolean=true 表示提交被网络接受，不代表已送达或已执行。原生写失败关闭异常连接并诊断，不重发已提交消息。Call 等待 Response、超时或生命周期结束。

## 5. 完整消息交付

```java
public interface RpcHandler {
    void handleUserMsg(Connection connection, Object msg);
}
```

| 接收消息 | 动作 |
| --- | --- |
| RpcRequest | 交付同一个完整对象，任意合法 command 都允许 |
| RpcResponse | 根据接收连接的相邻 Peer 和 requestId 完成 RpcFuture，不交给 Handler |
| RpcRouteMessage | 原样交给 Handler，不解析 inner，不比较地址，不自动转发 |
| RpcHandshake | 内部状态机消费，不交给 Handler |

RpcRequest 为纯数据 record：int command、int requestId、long routeKey、long businessId、byte businessIdType、RpcMetadata metadata、ByteBuf body。它不携带 RpcNode、Connection、sourceNodeId、targetNodeId、responseType、replied 或 attach/reply 方法。

RpcResponse 为 int requestId、int errorCode、RpcMetadata metadata、ByteBuf body、String[] errorArgs。成功响应 body 非 null，可为空缓冲区，errorArgs 必须为空数组；错误响应通过 errorArgs 携带参数，不允许非空原始 body。响应不携带 command 或业务类型。

保留原四参数构造方法，默认零个错误参数。推荐创建错误响应：

```java
static final RpcError NOT_ENOUGH_GOLD = new RpcError(1001, "金币不足");

rpc.reply(connection, RpcResponse.error(request.requestId(), NOT_ENOUGH_GOLD, "1000", "300"));
// 需要 Metadata 时使用 error(requestId, errorCode, metadata, args...)。
```

error 工厂支持 RpcError 或正 int errorCode，均只保存错误编号，body 为 null；支持带 Metadata 的重载。int 重载保留给框架错误及原始协议处理，业务常量使用 RpcError 构造方法定义。errorArgs 不允许 null 数组或 null 元素，允许空字符串；构造和对外读取非空数组时复制数组，防止外部修改。内部通过 RpcResponse 的包内操作校验和写入私有数组，不调用会复制数组的公共 getter，也不暴露可变数组引用。空数组共享，成功消息不为参数新建数组。String 不可变，错误参数不持有接收 ByteBuf，回调返回后仍可使用。

DefaultRpcCodec 在错误响应 body 区域按通用标准编码连续的 int32 UTF-8 字节长度和字符串，不增加参数数量或头字段。包内 RpcErrorArgs 工具统一处理长度、严格 Unicode/UTF-8 验证及编解码；解码先检查完整参数区域，再分配字符串数组。参数长度包含各项长度前缀，受 maxBodyBytes/maxMessageBytes 约束。RpcChecks 返回本次校验得到的错误 payload 长度，DefaultRpcCodec 直接用于分配编码缓冲区，避免为计算总长度再次扫描。Handler 的节点校验及默认 Codec 的独立使用校验继续保留，以确保自定义 Codec 和不同限额配置不绕过节点规则。成功 body 仍是原始借用 ByteBuf，不作业务序列化。

RpcRouteMessage 仍只有 int sourceNodeId、int targetNodeId、ByteBuf inner。Core 的单次 decode 返回此对象后立即交付，不创建 RpcRoutedRequest。即便 inner 恰好是一条合法响应，也不会由 Core 解包完成调用。业务是否解析、转发或丢弃信封由 Handler 自行决定，本文不定义业务转发框架。

所有数据对象均没有 msgType/flags 字段，msgType 仅由默认 Codec 写入/读取 Wire。

## 6. RpcOptions 与连接选择

RpcOptions 是只读 record：long routeKey、byte busType、long busId、RpcMetadata metadata、Duration timeout。DEFAULT 为零 routeKey/业务身份、EMPTY Metadata、未覆盖超时。busType 对应 Wire businessIdType，busId 对应 businessId。

```java
var options = RpcOptions.builder()
        .routeKey(123L)
        .busType((byte) 1)
        .busId(456L)
        .putLong((short) 1024, 123L)
        .putBoolean((short) 1025, true)
        .timeout(Duration.ofSeconds(1))
        .build();
```

也可用 metadata(RpcMetadata) 提供数据；构建选项时对可写 Metadata 创建快照。timeout 只影响 call，没有单独 timeout 参数重载。notify 和显式完整消息 send 不创建超时任务。

routeKey 只选目标 Peer 的 Slot。默认非零 Key 使用 SplitMix64 finalizer，再 Math.floorMod(hash, connectionCount)：

```text
hash = key
hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9
hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111eb
hash = hash ^ (hash >>> 31)
slot = floorMod(hash, connectionCount)
```

不可用返回 UNAVAILABLE，不可写返回 OVERLOADED，不改选其他 Slot。零 Key 在可用连接间轮询。自定义 ConnectionSelector 接收这个直接相邻 Peer 和 routeKey，输出必须是其当前合法连接。编码前预检不额外调用自定义选择器或推进轮询；编码后调用选择器并复查连接可写状态。

## 7. Call、计时与并发

Call 进入时计算单调时钟 deadline。直接查找目标 Peer，由 RpcCalls.begin 登记 RpcFuture 并安装计时任务，然后执行编码和连接选择，最后在 RpcFuture 的完成锁内复查 deadline、状态和 Peer，确定发送准入后退出锁，再提交 Transport。没有 preparingCalls、路由查询阶段或准备集合锁。begin 内部处理计时安装失败：完成并移除已登记调用，通知一次失败，返回已结束的 Future，消息层停止提交。消息层不单独调用 register/schedule，也不直接查询 Pending；协议错误关联由 RpcCalls 按 requestId 查找并结束。

RpcPeer.tryRegister 将本 Peer 的准入、冲突检查和登记作为一个原子操作，与退役互斥；候选 ID 由 RpcCalls 的节点级 AtomicInteger 分配，支持跨 Peer 并发推进。正 int ID 在 MAX 后从 1 开始，跳过当前 Peer 已占用 ID。Send 通知为 0，不分配编号。跨 Peer 的 PendingCall 不混用。

响应只能匹配接收连接所属 Peer 的 PendingCall，信封中的 sourceNodeId 不参与关联。匹配使用接收 ConnectionHandler 保存的 Peer 实例，不重新按 nodeId 查找替代实例。

解码成功后、处理解码错误前均校验 Connection/ConnectionHandler/Peer 仍属于当前有效生命周期；移除发生在解码期间时丢弃该帧，不分发消息、不发送协议错误响应。此检查是分发准入点，已通过检查或已进入 Handler 的处理不被强制取消，removePeer 不等待业务 Handler 返回。

完成之前再检查 deadline，慢解码或时间轮延迟不能导致迟到成功。过期、移除和关闭中的 Call 不得在慢编码返回后补发。

RpcCalls 统一处理响应、失败、超时和退役调用。正常完成经过 complete，生命周期收尾经过 settlePeer；两者复用 claimCall，以 Future.tryComplete 争取唯一完成权并取消计时句柄，再通过 Peer 按 Future 实例删除 Pending。截止时间判断、错误转换和结果通知均属于 RpcCalls。生命周期结束先确定结果，清理资源后再通知；普通完成在完成锁外通知。 Transport.write 在完成锁外执行，因此同步回入的请求、响应和诊断也不会继承发送锁；结果直接通知，保持“通知、响应、通知”的原消息顺序。Future 不暂存结果，不增加回调线程池、全局队列或响应 retain。发送准入是线性化边界：超时/移除先发生则拒绝提交，通过准入后视为在途，后续超时或移除可以完成调用，但不保证撤回已获准的网络写入。Transport 必须继续检查连接状态；同步响应已赢得结果后，发送再抛错或返回拒绝不能重复通知。

超时任务由 RpcCalls 创建，显式携带原 Peer 和 Future，先清理 Pending，再由检测到超时的当前线程直接通知。时间轮触发的超时在时间轮线程通知，收发路径发现过期则在对应线程通知；Future 不引用调度组件，不创建无用 CompletableFuture。RPC 不使用 commonPool 或另建回调线程池，业务回调负责投递业务线程，不应在时间轮上执行耗时工作。

所有结果回调都直接通知，不隐式切换线程。普通成功/失败通常运行于调用线程或网络线程；移除/关闭在调用线程完成资源清理并退出生命周期锁后通知。不同调用可并发完成，用户负责把后续处理交给自己的运行环境。成功结果中的 ByteBuf 在同步回调中借用，异步使用必须保留引用。

计数器在 Peer 重建时延续，在完整编号回绕之前避免复用旧 ID。跨 RpcNode 运行实例重启或完整回绕后的旧响应隔离仍是协议边界，不保证永久去重。

## 8. Metadata

RpcMetadata 只提供基础 put/get、contains、encodedLength、encoded、copyOf 和可选 Builder；实现委托 RpcMetadataUtil。内部为连续 `[short key][byte length][bytes]`，不使用 Map、逐 Value 对象或业务类型注册表。

putInt/putLong 为大端 4/8 字节；Boolean 为 1 字节 0/1；String 为严格 UTF-8。put(short, byte[]/ByteBuf) 拷贝 Value 原始字节，不改变输入索引。单 Value 最多 127 字节，Key 必须为正且不重复。非法写入须在追加条目前拒绝。

getInt/getLong/getBoolean 直接读取对应偏移，不创建临时切片；长度或取值错误抛出异常。get 返回新的 byte[]，getBuffer 返回只读借用视图，getString 校验 UTF-8。缺失字段不会伪装为 0/false。

可写 Metadata 不支持并发写。EMPTY、入站 Metadata、Builder.build 和 RpcOptions 快照均只读，可共享。后续修改原可写对象不影响快照。

Metadata 存储是独立堆 ByteBuf，通过 unreleasableBuffer 包装，由 GC 管理，不池化；用户无需 retain/release。这样选项和 Metadata 可以脱离网络帧存活，不增加跨组件释放协议。body/inner 不享有此独立生命周期。

入站重复 Key 检查不使用 ThreadLocal。前 8 个 Key 存在两个局部 long 中；超过 8 个才创建本次校验独占的 4 KiB BitSet。少量字段不为查重分配堆对象，大量字段保持线性处理，不依赖平台线程或虚拟线程长期复用。该选择不是生产压测结论。

## 9. Codec 与 ByteBuf 所有权

```java
public interface RpcCodec {
    ByteBuf encode(RpcMessage message);
    RpcMessage decode(ByteBuf input);
}
```

一个节点只配置一个支持并发调用的 Codec。默认 DefaultRpcCodec 可直接使用，也可配置自定义完整外层格式；握手、心跳、请求、响应和信封都走同一 Codec。自定义实现必须遵守节点限额与引用契约，不能让框架猜测释放责任。

encode 返回调用者拥有的完整 RPC Frame，不含 TCP 前缀，不消费输入引用、不改变输入索引。Request/Response 默认将 body 写入最终帧。RouteMessage 只分配 9 字节外层头，与 inner.retainedSlice 组成两组件 CompositeByteBuf，避免复制整个 inner。

RpcMessages 的 encodeMessage 入口统一调用 RpcCodecHandler 同步编码，编码后选择连接并提交，在 finally 中释放编码帧。Transport 接受时取得输出引用，写入结束释放。业务可在 send/reply 返回后释放自己的输入引用；信封输出可能仍引用 inner，发送期间不能改写底层字节。

decode 不改变输入索引。Request.body、成功 Response.body、RouteMessage.inner 是有界只读借用视图。网络桥持有入站帧至同步 Handler/结果回调返回，此后不能直接使用借用视图。异步持有须 copy 或 retainedDuplicate/retain，并最终 release。Metadata 解码后独立复制存储。

完整信封 send 会经同一 Codec 重新编码外层；Core 没有绕过 Codec 的自动原始帧转发分支，也不会为了处理信封再次解码 inner。

## 10. 统一收发与 Netty

```text
Call / Send / 显式完整消息 / Reply / Handshake
    → RpcMessages.encodeMessage（统一响应失败策略）
    → RpcCodecHandler.encode
    → 连接状态和可写状态检查
    → RpcTransport.write
    → NettyRpcFrameCodec 编码 TCP 长度前缀

TCP 输入
    → NettyRpcFrameCodec 分帧
    → ConnectionHandler 检查帧长度并调用 RpcMessages.decode
    → RpcCodecHandler.decode
    → ConnectionHandler 消费握手/心跳、重新确认连接身份
    → RpcMessages 分发完整消息
    → 业务 Handler / RpcCalls 响应关联
```

game-rpc-netty 默认使用池化 ByteBufAllocator。NettyRpcFrameCodec 内部组合长度解码/前缀编码器，双向遵守同一个有符号 int32 长度契约，拒绝非正数和超限值。前缀和 payload 不必复制为一个新大缓冲区。

RpcNetworkProvider.create(RpcNetworkConfig) 只接收不可变的 nodeId、监听地址、maxMessageBytes 和刷新配置，返回尚未启动的 Transport；不接收整个 RpcNode。NettyRpcTransport 保存该网络配置，不查询节点或调用状态。Node 完成组件装配后，在同步 start 中调用 Transport.start(Listener)，Listener 统一提供 newInboundHandler()、newOutboundHandler() 和 onWriteFailure(connection, failure)。因此 Provider 构造阶段不能调用未初始化 Node 的 Handler 接口，也不依赖延迟访问半初始化对象的约定。

Handler 工厂在创建物理连接时执行，每条连接必须使用新 Handler。Provider 须先执行出站 Handler.onConnected，再调用 ConnectCallback.onSuccess；game-network 原生桥满足该顺序。普通用户仍只创建 RpcNode；networkHandler、observe 和仅供旧 Provider 使用的配置读取方法不再作为 Node 公共 API。自定义 Transport 必须实现当前 Listener、connect(address, callback)、write 和 checkLifecycleThread 契约。RpcNode 面向业务的 connect/call/send/reply API 不变。

RpcTransport 只定义网络 Provider 的能力，不保存 Peer、Call、协议类型或重连策略：

| 方法 | 契约 |
| --- | --- |
| allocator() | start 前即可取得，Core 借用分配器 |
| start(Listener) | 同步初始化客户端并绑定监听；Core 调用一次；部分启动失败仍可 close |
| connect(InetSocketAddress, ConnectCallback) | 一次物理 TCP 建连，不握手、不重试；未解析地址不在调用线程执行 DNS |
| localAddress() | 返回实际监听地址，绑定前为 null |
| write(Connection, ByteBuf) | 提交已编码帧，Transport 负责长度分帧和刷新；返回本地提交结果 |
| checkLifecycleThread() | 在 Node 状态改变前检查同步启停能否在当前线程执行，防止 EventLoop 等待自身 |
| close() | 同步释放自有网络资源，支持启动失败后的清理及重复调用，不关闭借用资源 |

Submission.ACCEPTED 只表示本地接受，不能解释为对端收包或原生写成功。write 借用输入，不改变索引；接受时获取独立输出引用，拒绝不获取引用。接受后的异步失败通过 Listener 通知，Transport 在 finally 中关闭失败连接，诊断异常不能阻止关闭；不重放消息。

NettyRpcTransport 直接检查 Channel 的 active/writable 状态并提交编码帧，不维护自建发送字节预算，没有 Budget、maxOutboundBytes、pendingBytes 或 canSend 接口。RpcConnections 保留编码前可用性预检和编码后连接选择，预检不推进轮询；自定义选择器返回后重新确认 Peer 生命周期。Transport 接受后 retainedDuplicate 输出，原生写入负责释放输出引用。写失败仍诊断并关闭连接，不重发消息。

RpcLimits 构造参数从五项改为四项，旧配置移除最后的 maxOutboundBytes 参数。连接选择依据 Connection.isWritable，RpcTransport.write 返回本地提交结果 Submission。

默认 writeAndFlush 即时刷新。consolidateFlush(true) 在 Pipeline 安装 Netty FlushConsolidationHandler(256, true)，合并同一读批次或 EventLoop 周期内的刷新；到阈值、不可写、关闭等情况及时刷新。不开启时没有这个 Handler，不设置自动流量检测线程或额外业务发送队列。

启动中的 Provider/诊断回调不能重入同步 start/close；节点自有时间轮线程也不能调用同步 start/close；在修改状态、取得生命周期锁前直接抛 IllegalStateException，与网络 EventLoop 的规则一致。回调继续在原事件线程通知，由业务层自行投递关闭操作。通过 ThreadFactory 在构造期记录自有时间轮线程，不使用 ThreadLocal、ScopedValue 或回调线程池。借用 Timer 不由节点停止，因此不施加自有时间轮的停止限制。

关闭时只在生命周期锁内竞争关闭权并发布 CLOSED；连接收尾、Transport.close、Timer.stop 和结果通知均在锁外执行。首次取得关闭权的调用同步完成资源清理，其余并发或重入 close 幂等返回，不等待首个关闭操作。start 的异常清理也在退出启动锁后执行；自有时间轮停止仍遵循 Netty 的中断/等待语义，业务回调应及时返回。

协议错误自动响应必须使用收到该帧的 Connection，复用 reply(connection, response) 的原 Slot/同生命周期替换规则；不根据错误帧里的 routeKey 重新选连接，不调用主动发送的 ConnectionSelector。原连接仍可写时，其他 Slot 背压不能让 PROTOCOL_ERROR 响应丢失。

## 11. 握手、心跳、重连与关闭

每个就绪物理连接的主动端负责 PING/PONG 存活探测，接收端仅原连接回复。默认握手后 5 秒发首次 PING，有效 PONG 后再等 5 秒；每次等待 PONG 最多 15 秒。通过 Builder.heartbeatInterval(Duration) 和 heartbeatTimeout(Duration) 配置，两者最少 100ms，仅作用于本节点主动建立的连接；对端无须同值配置。接收端不另设无 PING 的 RPC 定时任务。可使用 Builder.readIdleTimeout(Duration) 配置仅接受端 TCP 连接的网络读空闲检测；默认 Duration.ZERO，既不安装该读空闲检测，RPC Handler 也不因 READ/ALL idle 事件关闭连接。显式设置正时长后，NettyRpcTransport 将配置传给 TcpNetworkServer 的 NetworkOptions.READ_IDLE；game-network 的 onIdle 回调关闭失活的原物理连接，保留 Peer/Pending，并允许同 Slot 后续重连。WRITE idle 不关闭；主动连接仍使用已有 PING/PONG 超时检测。关闭或已替换连接的旧 idle 事件无效。

读空闲参数通过 RpcNetworkConfig.readIdleTimeout 传给 Provider；自定义 Provider 需自行产生网络 idle 事件。启用时，应大于对端的 heartbeatInterval + heartbeatTimeout，并预留调度/网络余量；双方不必取相同配置，但要满足该约束。网络原始入站数据会重置读空闲，读空闲检测不保证收到的是合法 RPC 帧。

RpcHeartbeat(Kind kind, int sequence) 是纯控制数据；DefaultRpcCodec 对应 5 字节 PING=7/PONG=8，完整 TCP 帧 9 字节。ConnectionHandler 消费心跳，不触发 handleUserMsg、RpcCalls 或 RequestId 分配。自定义 RpcCodec 必须同时支持 RpcHeartbeat。

ConnectionHandler 复用其控制 Timeout/deadline，握手完成后仅主动端保留一个下一次探测或等待 PONG 的计时任务；保存 sequence 和 awaitingHeartbeat，不创建请求表。普通业务消息不更新时间戳，常规收发不增加计时任务或计时锁。心跳收发沿 RpcMessages → RpcCodecHandler → RpcCodec → RpcTransport，必须使用同一个物理连接，不执行 Slot 选择或回复连接替换。

探测状态和超时任务在调用 Codec/Transport 之前登记，兼容同步收到 PONG。状态转换持有拓扑锁，编码、提交、关闭和诊断在锁外；提交前重查物理连接身份及探测期限。时间轮只唤起内部连接操作，沿用建连/握手过期的虚拟线程方式执行探测，避免慢 Codec 或关闭操作堵塞共享时间轮；不创建回调线程池。到期无匹配 PONG 时仅关闭原连接并重连同一 Slot，保留 Peer 和 PendingCall。背压不换槽、不积压重试心跳；保留截止任务等待超时。断连、移除、关闭统一取消任务，过期任务用 Timeout 实例及物理连接身份排除旧任务。诊断新增 heartbeat-timeout / heartbeat-failed。

每个新 Connection 都进行 HELLO/ACK 身份与 Slot 握手，默认帧布局见通用标准。接收端先提交 ACK 再发布连接，主动端验证 ACK 后就绪。两端都发起整个节点对的连接时明确拒绝，不能自动选主或自动反向连接。

本机入站 HELLO 必须对应已登记的本机主动 Peer 和该 Slot 正在进行的建连尝试；不能仅凭 sourceNodeId 等于本机就创建 Peer。每个本机 Slot 只允许一个活动入站端和一个活动出站端，同方向仍执行重复检查。接受端不覆盖主动发送 Slot。

ConnectionHandler 在握手期间保存物理连接实例、方向、相邻 Peer、Slot、候选身份和独立 deadline。接入、准入回调返回、ACK 编码/提交后及发布前检查有效性和截止时间。maxPendingHandshakes 限制所有未完成入站/出站握手；超限关闭新候选、不登记计时器，主动 Slot 按延迟重试。成功、失败、超时、断连、关闭恰好释放一次名额。

首次入站握手会临时登记 Peer。ACK 编码失败、提交被拒绝、发布前断连或超时都经过统一 drop 清理；若 Peer 没有主动连接配置、从未有 Slot 完成握手，且已经没有其他关联 ConnectionHandler，则按实例删除并结束临时 Peer，归还 maxPeers 名额。RpcPeer.established 在任意一个 Slot 首次完成握手时置为 true，断连不清除。尚未 established 的 Peer 不允许登记 RpcFuture，避免临时登记回滚遗留 PendingCall；主动 connect 的 Peer 保留配置并继续重试。

RpcConnections 的拓扑锁保护 Peer/Slot 预留、握手状态和就绪发布；日常发送不取得该锁。握手先在锁内预留候选，再在锁外执行用户准入与编码，编码后、网络提交前和就绪发布前重新确认连接、Peer 实例、attempt 与 deadline。处理中重复握手被拒绝。移除能撤销正在准入或编码的候选；恢复执行的旧候选不得发送 ACK 或复活旧 Peer。失败回滚在锁内，物理连接关闭在锁外；批量关闭保留异常汇总。

RpcPeer 的注册锁只保护本 Peer 的 Pending 准入。诊断同步增加计数并在事件线程直接通知；连接诊断不持有拓扑锁。移除/关闭中的连接回调和清理诊断仅暂存到本次操作的局部列表，资源清理后在调用线程、生命周期锁外执行，不创建异步调度任务。未配置诊断回调时不创建诊断对象。Codec、业务回调和诊断回调都应及时返回；移出拓扑锁不等于自动切换业务消息执行线程。

首次 connect 立即调度（实际触发受时间轮 tick 影响）。每 Slot 第 n 次连续失败后的等待上限为 min(reconnectDelay × 2^(n-1), maxReconnectDelay)，实际等待在上限的一半到上限之间随机选取，至少 1ns。加倍使用饱和计算避免溢出。默认初始上限 1 秒，最大上限为 30 秒与初始上限中的较大者；可用 maxReconnectDelay(Duration) 覆盖，不能小于初始上限。第一次重试默认等待 0.5～1 秒，之后为 1～2 秒、2～4 秒，最终为 15～30 秒。

只有该 Slot 握手成功才重置退避；一次 TCP 连接成功但握手失败不能重置。移除/关闭取消任务，重复 connect 不跳过已有等待，方向冲突不重试。重连填回同一 Slot，保留 Peer 和 Pending，迟到断连必须核对 Connection 实例。接收方向没有对端监听地址时等待对端重连，不猜测地址。同一 Peer 的连接数量固定，重复 connect 的地址/数量必须一致。

RpcConnections 在 removePeer/close 中退役 Peer、取消连接任务，并调用 Node 提供的退役通知；它不引用或操作 RpcFuture。Node 在该通知内调用 RpcCalls 确定未完成调用的 UNAVAILABLE 结果，连接清理后在锁外通知业务。单条连接关闭抛出运行时异常时继续尝试关闭其他连接，完成结果通知后抛出汇总异常。后续主动连接或合法入站握手可以创建新 Peer，无永久移除集合。

close 先发布 CLOSED，收集并确定所有 Pending 的结束结果，取消计时和连接任务，释放自有网络与时间轮，最后在启动锁外通知结果。单条连接关闭失败不跳过其他连接、Transport 或自有 Timer 的清理；保留首个运行时异常，后续异常加入 suppressed，结果通知后再抛出。即使资源关闭失败也通知已经确定的结果。并发关闭幂等，关闭后不能再次 start。start 失败回滚已创建资源。

build 中 Provider 已返回 Transport 后，allocator 等后续装配步骤失败时，Node 先关闭该 Transport，再停止自有 Timer；清理异常加入原始异常的 suppressed，不覆盖原始失败，也不跳过后续清理。外部 Timer 仍由调用者所有，不能在构造回滚时停止。Provider 在返回 Transport 之前自身创建失败的资源由 Provider 清理。

默认资源由 Node 所有；NettyRpcNetworkProvider(sharedResources) 和 Builder.timer(timer) 注入的资源为借用，Node 不关闭外部资源。同步 start/close 禁止在该节点网络 EventLoop 中执行，避免等待自己。连接建立使用 ConnectCallback，不暴露 CompletionStage。

## 12. 配置默认值

| 配置 | 默认与含义 |
| --- | --- |
| nodeId / listen / handler / defaultTimeout | 必填 |
| codec | DefaultRpcCodec |
| connectionsPerPeer / maxConnectionsPerPeer | 1 / 64，固定 Slot 数量 |
| maxPeers | 4096，只统计已登记的相邻 Peer |
| maxPendingHandshakes | 1024，未完成入站/出站握手总数 |
| reconnectDelay / handshakeTimeout | 重试初始上限 1 秒 / 5 秒 |
| maxReconnectDelay | max(30 秒, reconnectDelay)，可显式配置 |
| readIdleTimeout | 0，默认不因 idle 关闭连接；仅显式配置后对接受端生效 |
| heartbeatInterval / heartbeatTimeout | 5 秒 / 15 秒，仅主动连接；最少 100ms |
| Timer | HashedWheelTimer，100ms tick、512 槽 |
| maxMessageBytes | 4 MiB，Frame 不含 TCP 前缀；配置范围 40～Integer.MAX_VALUE-4 |
| maxMetadataBytes | 16 KiB，总 TLV 长度 |
| maxBodyBytes | 4 MiB；实际 Frame 仍须加头后满足 maxMessageBytes |
| maxPendingCalls | 每个 Peer 65536 |
| consolidateFlush | false |
| peerAdmission | 接受任意配置限额内的相邻 nodeId，可替换准入谓词 |
| selector | 非零 Key 固定 hash；零 Key 可用连接轮询 |
| diagnostics | 默认只累计事件计数，可注入 Consumer&lt;RpcDiagnostic&gt; |

RpcLimits 为四字段 record：maxMessageBytes、maxMetadataBytes、maxBodyBytes、maxPendingCalls。Metadata/body 上限不得超过 Frame 上限，pending 必须为正。没有 Router、下一跳或业务来源信任配置。

## 13. 错误与诊断

RpcError 是 final 不可变对象，仅保存 int code、String message。1～1000 全部保留给 game-rpc；已有 RpcError.NO_HANDLER / TIMEOUT 等常量名称和编号不变，业务通过 static final RpcError 定义自己的错误。公开构造方法 new RpcError(code, message) 要求 code>1000 且 message 非 null；可定义到 Integer.MAX_VALUE。

RpcError.equals/hashCode 只依据 code，说明文字不参与身份比较；业务判断使用 NOT_ENOUGH_GOLD.equals(result.error())，不依赖对象引用相同。message 为本地说明，不随错误响应发送。RpcError.fromCode(int) 对已有框架错误返回共享常量，对其他正数（含尚未定义的 8～1000 保留码）创建保留原 code、message="" 的对象；0/负数不是错误，调用 fromCode 时拒绝。不建立全局业务错误注册表或未知码缓存；业务需要本地错误文案时按 code 自行映射。

RpcResult.received(response) 保留完整远端响应，无论成功还是错误；本地失败通过 failure(error) 创建，value() 为 null。isSuccess() 仅在收到 errorCode=0 的响应时为 true，errorCode() 统一返回远端错误码或本地框架错误码。error() 在任何失败（本地或远端、框架或业务）时均返回 RpcError，只有成功响应才为 null。RpcResult 的公开构造方法拒绝错误对象与响应 errorCode 不一致的组合。

```java
if (result.isSuccess()) {
    ByteBuf body = result.value().body(); // 回调期间借用
} else if (result.value() != null) {
    RpcError error = result.error();
    int code = error.code();
    String[] args = result.value().errorArgs(); // 包括远端框架错误的参数
    // 业务层处理错误文案及线程调度。
} else {
    RpcError localError = result.error();
}
```

业务错误计入 call-failure，诊断 cause 为携带相同错误码的无堆栈 RpcException；完整响应和参数由结果回调获取。参数错误同步抛出，本地不可用/超限可在 call 返回前通知失败。

RpcCodecHandler 在编码前和解码后调用同一组 RpcChecks 消息规则：Request 的 command/requestId/businessIdType 组合、Response 的 requestId/errorCode/成功 body 与错误参数互斥、错误参数 Unicode 和长度、Metadata 和 body 的节点限额、非空原始信封。此校验对默认和自定义 Codec、Call/Send/Reply、完整消息重载都生效；不解析信封内层，不扫描已构建 Metadata 的条目。DefaultRpcCodec 独立编码时复用同一组消息规则；其解码仍负责字节布局、截断、长度和 TLV 格式验证。自定义 Codec 自身的限额不能放宽 Node 限额。默认 Codec 对完整外层进行验证。可安全关联的畸形 Request 可返回 PROTOCOL_ERROR，畸形 Response 结束相邻 Peer 对应调用；无法安全关联则丢弃并诊断。合法正错误编号和参数原样交付；非法参数长度/UTF-8 才按畸形 Response 处理。Handler 异常不自动回复。成功 Response 编码失败时，由 RpcMessages.encodeMessage 经同一 Codec 尝试一次空 body INTERNAL_ERROR；send/reply 共用此策略，回退编码再次失败直接传播到发送入口处理，不递归重试，已提交的消息不能补发。RpcCodecHandler 不创建错误响应。

预期 UNAVAILABLE/OVERLOADED 等内部拒绝使用无堆栈复用 RpcException，避免每条拒绝都生成堆栈。只缓存当前内置错误的异常信号，业务和未知码的诊断信号不进入全局缓存；真正 Codec/网络异常保留原因。诊断异常被隔离，不能递归破坏资源清理。

事件包括显式启用读空闲关闭时的 read-idle、连接成功/失败、握手拒绝/超时/超限、调用成功/失败、发送拒绝、Codec 失败、未知响应和回调失败。没有 Core 自动转发事件。eventCounts() 返回计数快照。

## 14. 验证与迁移

2026-09-12 本轮执行 verify，286 项测试通过：Core 251、Netty/TCP 35，失败、错误、跳过均为 0。环境为 Windows、JDK 25.0.3、Netty 4.2.15.Final，使用 paranoid 泄漏检测；本轮未出现泄漏报告。

| 测试 | 项数 | 覆盖 |
| --- | --- | --- |
| RpcBoundaryFixTest | 14 | 启动就绪门槛和启动失败回滚、同步回入结果锁外通知及池化 body 引用、发送/回调抛错、构造清理和借用 Timer |
| RpcCallbackThreadTest | 4 | 超时直接通知、首次/已就绪连接回调线程、移除和关闭后在调用线程且锁外通知 |
| RpcArchitectureTest | 12 | 自定义 Codec 通用字段校验、节点 Metadata 限额、慢准入/ACK 编码期间其他 Peer 管理、旧握手撤销、诊断回调隔离 |
| RpcWireTest | 67 | 共享编码样例、整数与 TLV 边界、非法帧 |
| RpcErrorTest | 7 | 内部保留区边界、业务常量、按 code 比较、typed 回复、统一失败结果、诊断和框架信号复用 |
| RpcErrorArgsTest | 12 | 错误参数数组所有权、UTF-8/长度边界、无参数兼容、Codec 通用校验、业务错误原样回调与一次完成 |
| RpcNodeTest | 26 | Call/Send、回复、超时、重连、ID 回绕、异常及生命周期 |
| RpcFutureTest | 3 | 独立调用状态的并发完成权、定时句柄先后安装与取消；状态转换不触发业务回调 |
| RpcOwnershipBoundaryTest | 10 | Peer 并发登记与退役；统一编码失败策略；通信不读取 connectionId；Handler 不跨连接复用；计时登记失败清理 |
| RpcDefaultCodecTest / RpcByteBufTest | 10 / 3 | 默认编码、大小限制、原始信封与引用计数 |
| RpcDirectPeerTest | 6 | 未知节点快速失败、不创建 Peer；信封不自动解析/转发；相邻 Peer 响应隔离；重建 ID 延续；Peer 限额及完整消息发送背压 |
| RpcHotPathTest | 3 | 背压前拒绝编码、直接发送不持连接管理锁、信封编码不复制 inner |
| RpcLifecycleTest / RpcDeadlineCleanupTest | 3 / 4 | 慢准入与时间轮隔离、关闭清理、握手期限与迟到 ACK |
| RpcRegressionTest | 6 | 慢编码/选择器、迟到响应、业务自行投递耗时工作、连接回调锁边界 |
| RpcReviewFixTest | 5 | 握手名额的成功/失败/超时/断连/关闭释放与主动重试 |
| RpcConnectionBoundaryTest | 9 | 本机握手与重连、等待回调上限及释放、慢解码期间移除重建、连接关闭异常与 Pending 收尾 |
| RpcHandshakeRollbackTest | 9 | 首次入站握手失败回收 Peer 名额、旧断连隔离、已有 Slot/其他候选/主动重试保留、临时 Peer 不登记 Call |
| RpcMetadataValidationTest / RpcOptionsTest | 5 / 6 | TLV 校验、虚拟线程、基本值、快照与复用 |
| RpcRawMessageTest | 2 | 完整请求/响应、借用 body、独立 Metadata |
| RpcHeartbeatTest | 14 | 默认 idle 不关闭、启用后原 Slot 关闭与旧事件隔离、心跳方向、同步 PONG、逐 Slot 超时/背压隔离、重连、旧帧隔离、慢/失败 Codec、回环和生命周期清理 |
| RpcReconnectTest | 4 | 退避上限/随机偏移、Slot 独立重置、极端值溢出、配置校验、真实建连失败调度和移除取消 |
| RpcCloseReplyTest | 7 | 自有时间轮生命周期拒绝、并发关闭无死锁、启动失败锁外清理、借用 Timer 保留、错误响应原 Slot 与背压隔离 |
| RpcTransportTest | 2 | Listener 工厂、物理建连、字节写入/分帧、启动前及关闭后回调、重复启动/关闭 |
| RpcHeartbeatTcpTest | 3 | 接受端真实 READ_IDLE 关闭与同 Slot 替换、真实 TCP 周期探测、静默对端超时断开、统一 Codec |
| RpcTcpTest | 21 | 错误字符串数组回传、真实 TCP、双向连接、回复、握手、原生写失败、关闭；仅业务 Handler 显式发送才继续传递信封 |
| RpcCodecExtensionTest | 2 | 自定义完整 Codec；信封地址与内层不被 Core 解释 |
| RpcFlushTest / RpcFrameCodecTest | 5 / 2 | 可选合并刷新、孤立消息、统一 TCP 分帧和引用管理 |

```text
mvn -o -s game-network/.mvn/settings.xml -gs game-network/.mvn/settings.xml "-Dmaven.repo.local=D:/github/mana3/game-network/.m2" -pl game-rpc/game-rpc-netty -am verify "-Dtest=Rpc*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
```

此前只验证内置 Router 行为的用例已移除；与普通通信有关的慢编码、背压、生命周期、握手、Wire 和引用计数用例继续保留或改为直连场景。Linux Docker 的持续压力与故障验证记录见第 16 节；尚未执行生产环境压力测试和跨语言互通，不以功能测试代替吞吐和延迟结论。

从旧版迁移时移除 routes/allowedSources 配置。call/send 的 targetNodeId 必须是实际直接相邻 Peer。Handler 收到信封后使用 RpcRouteMessage，Core 不再交付 RpcRoutedRequest。业务需明确决定自己的转发与结果关联，不能依赖旧版自动解包行为。routeKey 的连接内分槽规则不变。成功及无参数错误帧布局不变；使用带参数错误响应前需升级双方默认 Codec，自定义 Codec 需支持 errorArgs。所有失败现在均有非 null result.error()，value()!=null 时可读取完整远端响应和参数。RpcError 已从 enum 改为不可变对象；原常量名称保留，业务比较改用 equals/code，不再使用 ordinal()/values() 或枚举 switch。已有占用 8～1000 的业务编号需迁移到 1001 及以上；Wire 布局不因错误对象类型变化而改变。


## 15. 可复现性能基线

`game-rpc-netty/src/test/java/cn/managame/rpc/netty/RpcLoadBenchmark.java` 是独立 main，不是 Surefire 单元测试。`benchmarks/run.py` 从最近一次 Maven RPC verify 的报告读取依赖 classpath，逐场景启动全新 JVM。运行时使用当前 JDK，默认 `-Xms256m -Xmx256m`，关闭 Netty 泄漏检测；功能验证仍使用 paranoid。Windows 运行器沿用 Maven 的 jdk.net.unixdomain.tmpdir 设置，将 selector 唤醒管道放到项目 target 目录。不要并行运行其他压测来解读本基线。

```text
mvn -pl game-rpc/game-rpc-netty -am verify "-Dtest=Rpc*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
python game-rpc/benchmarks/run.py --requests 200000 --warmup 50000 --repeats 3
```

五组固定场景为：64 字节/1 Slot/128 在途；64 字节/4 Slot/256 在途；同场景开启合并刷新；同场景开启无日志诊断回调；16 KiB/4 Slot/8 在途。发送端使用一个平台线程和 Semaphore 控制并发，接收端原样回复 body。消息无 Metadata，使用默认 Codec；默认心跳保留。每次运行任意调用失败或未完成均终止并报告错误。

报告写入 `game-rpc/target/benchmark-时间戳.json`，可通过 `--output` 指定位置。记录机器/JDK、参数、每秒完成 Call、P50/P95/P99、进程堆分配字节/Call、GC 次数/耗时、进程 CPU 时间。一个 Call 包含 Request 和 Response；分配统计覆盖同一 JVM 中的客户端、服务器和测量代码，不等于单条消息或 Core 自身的分配量。

这是有界并发的本机 TCP 往返基线。延迟从取得在途名额后开始，包含 RPC 调用到响应通知的时间，不包含等待名额；不覆盖开放到达流量、突发排队、跨机器网络或生产业务成本。短运行存在 JIT、调度和 GC 波动，应比较多次结果；不能据此直接给出线上容量保证。先测量，再决定是否优化 body 复制、诊断装箱/计数或调整合并刷新，不引入未经验证的零复制分支。

### 本轮本机记录（2026-09-11）

每场景 50,000 次预热、200,000 次测量，独立 JVM 重复两轮；共 2,000,000 次测量 Call，失败 0。结果保存在 `game-rpc/target/benchmark-baseline.json`（构建产物，clean 会删除）。初次大包/64 在途配置触发背压拒绝；正式成功基线将大包在途量设为 8。

| body / Slot / 在途 | 合并刷新 / 诊断 | Call/s 范围 | P99 毫秒范围 |
| --- | --- | --- | --- |
| 64 B / 1 / 128 | False / False | 75,719～77,653 | 3.06～3.15 |
| 64 B / 4 / 256 | False / False | 101,585～103,705 | 5.93～6.05 |
| 64 B / 4 / 256 | True / False | 245,973～271,977 | 2.78～3.10 |
| 64 B / 4 / 256 | False / True | 103,868～104,135 | 5.94～6.04 |
| 16384 B / 4 / 8 | False / False | 18,110～18,505 | 0.99～1.00 |

以上为短运行记录，部分小包场景测量不足 1 秒。它用于验证基线工具与后续同环境比较；诊断开关的细小差异不足以证明优化效果。池化堆外内存与实际网络带宽需另行采样。默认刷新策略保持关闭合并，按业务消息量选择。


## 16. Linux Docker 持续压力与故障测试

`RpcDockerStress` 是 test 源码中的独立入口，业务线程、负载生成和故障控制都只属于测试。`benchmarks/docker_stress.py` 在两个独立 Linux 容器中运行客户端与服务端；`--prepare` 可在新目录复制源码与依赖缓存、执行 Linux RPC verify，避免覆盖本机已有构建。复跑命令见 README。

### 负载与观测

默认即时刷新小包、合并刷新小包、大包高压、故障恢复各运行 300 秒；低并发大包、业务错误各 120 秒，paranoid 泄漏检测 60 秒。客户端 8 个平台线程使用 Semaphore 控制在途量，普通场景 1 个 Node、4 个 Slot，故障场景 4 个 Node、16 个 Slot。小包 body 64 B，大包 16 KiB。固定 Metadata 携带工作线程编号，响应验证长度、请求序号、尾部标记和 Metadata；错误响应验证业务码及有序字符串数组。主负载不会重发失败的请求，框架错误后负载生成器短暂退让 1 ms，避免不可用状态下无限空转。

每 5 秒记录成功数、各错误码、Pending、有效 Slot、框架事件、成功 RTT P99、堆使用量、Netty 池保留堆外内存、RSS、堆分配量、GC 和进程 CPU；约每 10 秒采样 Docker CPU/内存/网络计数。并发期间这些计数不是原子快照，最终停止发起并排空请求后再校验精确完成数、重复回调、内容错误和 Pending。正式吞吐从进程启动约 30 秒后的样本计算，减小启动和 JIT 影响；P99 使用 0.1 ms 桶，最终值覆盖整个运行且仅包括成功响应。

每个 JVM 限堆 512 MiB、直接内存 512 MiB，容器内存 1.5 GiB；服务端限 2 核、客户端限 4 核。普通场景开启 advanced 泄漏检测，专门场景使用 paranoid。分配量包含测试驱动、Codec、传输和 RPC；不能直接称为 Core 或单条消息的分配量。Netty 池保留量不等于活跃 ByteBuf 占用量，未见泄漏日志也不能替代更长时间和真实业务负载验证。

### 故障与判定

测试显式设置心跳间隔 1 秒、PONG 超时 3 秒、Call 超时 2 秒、初始重连退避 200 ms、最大退避 2 秒、接受端读空闲 10 秒，便于在有限测试时间内观察恢复。生产默认配置不变，尤其默认读空闲不关闭连接。

控制端口只发布到本机回环地址，在同一条持续负载中依次关闭所有 16 个 Slot、暂停读取 5 秒、暂停服务端进程 5 秒、强制结束并重启服务端。网络故障允许 UNAVAILABLE/OVERLOADED/TIMEOUT，仍要求最终连接全部恢复、成功调用持续推进、最后两个采样区间不再新增错误、Pending 清零、无重复回调和内容错误。正常场景不接受框架错误；业务错误场景只接受预期 1001 及其参数。检测到泄漏日志、OOM 或非零进程退出也判失败，保留原始证据。

结果是同机 Docker bridge、有界并发、客户端主动等待响应的测试，延迟不包含取得 Semaphore 名额前的等待。它不覆盖开放到达流量、跨机器丢包/延迟、真实业务执行、数小时或数天老化，也不能推导线上容量保证。不同刷新或并发配置的差异需结合 CPU、拒绝率与负载生成器退让一起解释。


### 本轮记录（2026-09-12）

Linux RPC 回归 286 项通过。正式 Docker 负载合计 25 分钟（含启停约 26 分钟），共 213,624,417 次 Call；7 个场景 6 个通过。所有场景均无内容校验错误、重复回调、最终 Pending、OOM 或 Netty 泄漏报告。四种故障均恢复到 16 个 Slot，稳定区间不再新增错误。

小包即时刷新：5 分钟 40,278,388 次全部成功，预热后约 134,770 Call/s，成功 P99 2.7 ms。相同负载启用合并刷新：74,470,429 次全部成功，约 249,155 Call/s，P99 2.1 ms。16 KiB / 2 在途对照：120 秒 784,021 次全部成功，约 6,581 Call/s，P99 0.7 ms。

16 KiB / 8 在途高压组未通过零框架错误目标，出现 855,300 次 OVERLOADED、15,576 次 UNAVAILABLE、63 次 TIMEOUT；同时发生 16 次 heartbeat-timeout 和额外重连。OVERLOADED 是正常背压反馈。已通过独立最小复现确认：一次心跳提交被背压拒绝后，即使业务消息已成功发送，连接仍因等待未发出探测的 PONG 而超时关闭。当前 sendHeartbeat 没有控制消息补发路径，这个问题本轮仅确认，尚未修改生产实现。

详细报告、资源采样和复现证据位于 `game-rpc/target/linux-stress-20260912/formal/report.md` 及同目录 JSON/日志；构建 clean 会删除这些产物。标准正文不因压测而调整。
