# 本仓库 RPC Wire Profile v2 — 2026-10 RPC binding

[English](rpc-wire.md) | **[简体中文](rpc-wire.zh-CN.md)**

本文件是字节布局的唯一来源。此 binding 替代旧的未实现草案：nodeId 从 uint64 改为 uint32，Slot 从 uint16 改为 uint8，errorCode 取消高位标记。旧草案从未发布实现；不能与旧布局混用。未来已部署版本的破坏性修改必须提升协议版本。

语义见 [RPC Specification](ogbs/OGBS-RPC-1.0.zh-CN.md)，Java 接入见 [RPC Java 开发规范](ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)，Metadata 与错误码见 [Core](ogbs/OGBS-Core-1.0.zh-CN.md)。

所有多字节整数为 big-endian。Java int/long 保存原始位；nodeId、command、requestId 的负 int 是合法 uint32。errorCode 仅允许 0..2147483647。

## Frame

| 字段 | 宽度 | 说明 |
| --- | --- | --- |
| frameLength | uint32 | 包含 type 与 payload，不包含自身 |
| messageType | uint8 | 1 Handshake、2 Heartbeat、3 Request、4 Response |
| payload | bytes | 见下文 |

最小 frameLength=1。Java maxFrameSize **包含 4 字节长度前缀**，默认值和合法配置范围在 Java 规范定义。解码先检查声明长度，支持分片/合包；零长度、超限、未知 type 为连接级错误。

## Handshake（type=1）

| 字段 | 宽度 | 值 |
| --- | --- | --- |
| magic | uint32 | 0x474e5352，沿用仓库 magic |
| version | uint16 | 2 |
| nodeId | uint32 | 非零且不等于本地 ID |
| slotId | uint8 | 0..slotCount-1 |
| slotCount | uint8 | 1..255 |
| timestamp | int64 | 发送方 Unix 毫秒时间；未配置密钥时为 0 |
| nonce | 16 字节 | 发送方随机数；未配置密钥时全 0 |
| peerNonce | 16 字节 | 发起方为全 0；回复方回填发起方的 nonce |
| mac | 32 字节 | 配置密钥时为 HMAC-SHA256(secret, magic..peerNonce 全部字节)；未配置时全 0 |

frameLength=85，完整帧=89 字节，不允许尾随数据。只有调用 addPeer 的一方（发起方）发送首个握手；被动端创建/复用被动 Peer，回送本地身份和相同 Slot 信息。发起方核对预期身份，并要求回复的 peerNonce 等于自己发送的 nonce。重复握手、Slot 冲突、slotCount 不一致、握手前业务帧/心跳、MAC 不匹配均关闭候选连接。若接收方本地已对该 nodeId 调用过 addPeer，入站握手同样被拒绝：一对节点之间只能有一方调用 addPeer。

**鉴权。** 同一集群的节点配置相同密钥时，被动端验证 MAC，要求 |本地时间 − timestamp| 不超过允许的时钟偏差（Java 默认 60 秒），并拒绝偏差窗口内重复出现的 nonce；发起方通过 peerNonce 回显确认回复是对本连接的新鲜应答。未配置密钥的节点只接受 MAC 全 0 的握手，因此密钥配置不一致的两端无法建立连接，而不是静默跳过鉴权。MAC 只认证连接建立，不保护后续帧的机密性和完整性；需要时在传输层启用 TLS。

v2 与 v1 握手不兼容（长度与 version 不同），集群内节点需一起升级；Request/Response/Heartbeat 布局不变。

## Heartbeat（type=2）

没有 payload，frameLength=1，完整帧=5 字节。READY 后 Write Idle 触发；不需要回复。

## Request（type=3）

| 字段 | 宽度 |
| --- | --- |
| command | uint32，非零 |
| requestId | uint32 |
| routeKey | uint64 |
| businessIdType | uint8 |
| businessId | uint64 |
| metadataLength | uint16 |
| metadata | metadataLength bytes |
| body | 剩余全部字节 |

固定开销（含 type、不含前缀）28 字节。requestId=0 为 Notify，非零为 Call。routeKey=0 表示无亲和性。businessIdType=0 表示未指定身份；RPC 不解释 businessId 或非零 command 对应的业务内容。

## Response（type=4）

| 字段 | 宽度 |
| --- | --- |
| requestId | uint32，非零 |
| errorCode | uint32，最高位必须为 0 |
| metadataLength | uint16 |
| metadata | metadataLength bytes |
| body | 剩余全部字节 |

固定开销（含 type、不含前缀）11 字节。0 成功；1..9999 为框架预留；10000..2147483647 为业务错误。完整编号由 Core 定义。所有合法响应交给统一 RpcHandler，RPC 不根据错误码自动调用本地 onFail。

同一本地 Node 内 Peer 重建不再重置 ID 分配，见 RPC Specification R-CALL-04；此连续性不改变字段、字节序或版本。Wire 没有 Node 代际字段，完整回绕迟到回复与跨本地 Node 替换的保存回复需遵守规范中的部署/应用边界。

接收 Response 先读取非零 requestId 并争取 PendingCall 完成权。无匹配的迟到/重复响应直接丢弃余下帧，不解析其错误码/Metadata；有匹配时继续校验，格式损坏交付 PROTOCOL_ERROR 并关闭连接，不能丢失完成通知。

## Metadata

metadataLength 最大 65535。内容重复：

```text
key:uint16 (1..65535)
valueLength:uint16
value:bytes[valueLength]
```

没有 count/type。重复 Key、零 Key、截断 header/value 非法；未知 Key 合法。值编码见 Core，结构检查不调用业务 MetadataCodec。空 body 在 wire 上没有单独 null 标记。

## 黄金向量

Handshake：nodeId=0x01020304、slotId=2、slotCount=3：

```text
00000055 01 474e5352 0002 01020304 02 03 (timestamp、nonce、peerNonce、mac 共 72 个 0 字节)
```

Heartbeat：

```text
00000001 02
```

Request：command=0x01020304、requestId=9、routeKey=10、businessIdType=2、businessId=11、Metadata 空、body=0x0c：

```text
0000001d 03 01020304 00000009 000000000000000a 02 000000000000000b 0000 0c
```

Response：requestId=9、errorCode=2006、Metadata/body 空：

```text
0000000b 04 00000009 000007d6 0000
```

<a id="router-profile-v1"></a>
<a id="router-profile-v2"></a>
<a id="router-profile-v3"></a>

## Router Profile v3（显式启用的 RPC payload）

[Router 语义](ogbs/OGBS-Router-1.0.zh-CN.md) 与 [Java 绑定](ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) 定义角色/所有权，本节是唯一 Router 字节定义。RPC framing、消息 type 与握手版本不变。Router 流量使用 Request.command **0x80000288**（Java **-2147483000**），在启用路由的 Node 内保留，普通业务不能使用此 command。外层 Metadata/业务身份为空/零。数据 Request 复制内层请求亲和字段，其他 Router Request 使用 routeKey=1。

每个 Router 请求 body 先写 `profileVersion:uint8=3`，再写 `operation:uint8`。其他版本、不支持的操作、截断/多余字节、非法 owner 或快照计数均非法。全部整数大端。nodeId/Router ID 为非零 uint32，epoch 为非零 uint64，serviceId 为限制在 1..2147483647 的 uint32，bindingKey 为任意 uint64。

Node 条目为 `nodeId:uint32 + nodeEpoch:uint64 + serviceId:uint32`（16 字节）；绑定条目为 `serviceId:uint32 + bindingKey:uint64 + nodeId:uint32 + nodeEpoch:uint64`（24 字节）；流头为 `routerEpoch:uint64 + session:uint64 + seq:uint64`（24 字节），Begin 不含 seq（16 字节）。

| Op | 名称 | 公共两字节头之后的字段 |
| --- | --- | --- |
| 1 | Sync | requesterEpoch:uint64, knownEpoch:uint64, brokenSession:uint64 |
| 2 | Begin | routerEpoch:uint64, session:uint64, nodeCount:uint32, bindingCount:uint32 |
| 3 | Nodes | 流头, count:uint16, count × Node 条目 |
| 4 | Bindings | 流头, count:uint16, count × 绑定条目 |
| 5 | End | 流头 |
| 6 | Delta | 流头, count:uint16, count × 增量操作 |
| 7 | State | 流头（seq = 本会话最后发送的序号） |
| 10 | NodeRegister | Node 条目 |
| 11 | NodeBind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 12 | NodeUnbind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 13 | NodeDetach | nodeEpoch:uint64 |
| 14 | RoutedData | 见下方数据 envelope |
| 15 | RoutedError | targetNodeId:uint32, targetNodeEpoch:uint64, requestId:uint32, errorCode:uint32 |
| 16 | Verify | expectedRouterEpoch:uint64, nodeEpoch:uint64, revision:uint64=0 |

增量操作为 `kind:uint8` 加：1 NodeAdd → Node 条目；2 NodeRemove → nodeId:uint32 + nodeEpoch:uint64；3 Bind → 绑定条目；4 Unbind → 绑定条目。分块和 Delta 的 count 为 1..1024。Java 容量限制见 Java 规范。

### Router 同步流（op 1–7）

op 1–7 只在相互声明的 Router 之间、经单个 Router Slot 以 RPC Notify（外层 requestId=0）发送。接收方忽略来自未声明 Peer 的这些帧；以 Call 形式发送或来自其他 Slot 数 Peer 的帧被拒绝。Router 身份为直连 RPC Peer ID，routerEpoch 标识其代际。同步流没有任何确认：单个 Slot 按序投递，丢失只会表现为序号缺口。

权威方（拥有该桶的 Router）向每个 Peer 发送一个会话：Begin、Nodes 分块、Bindings 分块、End，之后是 Delta，空闲时发送 State。会话号非零，在同一 routerEpoch 内递增。Begin 不带序号；会话后续每帧携带从 1 开始的下一个序号，State 重复最后使用的序号。快照相对本地变更原子捕获，之后的每个变更以 Delta 跟随。Nodes 先于 Bindings；每个绑定必须引用已暂存且 epoch、服务一致的 Node。End 时暂存条数必须与 Begin 计数完全相等，随后暂存桶才替换已提交桶。

接收方记录当前（epoch, session, 期望 seq）。同一 epoch 下旧会话的帧被忽略。Begin 开始暂存，除非它就是接收方已放弃的会话（epoch 相同且会话号不比已损坏会话新）。其他不匹配、序号缺口、seq 不等于最后应用序号的 State、非法帧、连接丢失、暂存快照停止到达或同步流静默，都会使副本损坏：已提交桶仍可见，接收方发送 Sync，其中 requesterEpoch 为自身 Router epoch，knownEpoch/brokenSession 为已放弃的会话（从未有过时为零）。权威方收到 Sync 后开启新会话；若 Sync 指明的是旧会话且当前会话刚刚开始，MAY 忽略。只要 Slot 可用且没有会话，权威方也会主动开启会话；未就绪的接收方会周期性重发 Sync，因此恢复不依赖任一消息先到达。待发送变更超出上限时同样重启会话。没有重放日志。

### 服务控制（op 10–13、16）

服务控制是直连服务发往所属 Router 的普通非零 ID RPC Call，字段与 Profile v2 相同。NodeRegister.nodeId 必须等于直连 Peer。NodeRegister 和 Verify 成功时原生 RPC Response body 恰为 routerEpoch:uint64（八字节，无 Router 头）；其他成功控制 body 为空。正 errorCode 表示拒绝；被发现移除的代际 NodeRegister 返回 NOT_REGISTERED。Verify 仅在 expectedRouterEpoch 等于接收方 epoch 且发送方以该 nodeEpoch 注册时成功，revision 必须为零。Verify 不注册、不改变权威，也不证明服务在线。NodeBind 响应只确认本地 Router。Router 之间不发送 Verify。

### RoutedError（op 15）

RoutedError 是发现失败的 Router 向请求的 origin Router 发送的 RPC Notify（外层 requestId=0），独立于同步流。requestId 为原非零业务调用 ID，errorCode 为正数。接收方只接受来自已声明 Router 的 RoutedError，且仅当 targetNodeId/targetNodeEpoch 仍在本地注册时，向该 Node 返回原生正错误码 Response。它不创建中间 PendingCall，不需要确认，也不重试；准入失败时由原调用的既有超时结束。

### RoutedData envelope

| 字段 | 宽度/含义 |
| --- | --- |
| mode | uint8：1 精确 Node 请求，2 动态请求，3 服务广播，4 响应 |
| originRouterId | uint32，调用方服务所附着的 Router；服务初始请求中为零 |
| sourceNodeId | uint32，原发送服务 Node |
| sourceNodeEpoch | uint64，原来源附着关系 |
| targetNodeId | uint32，精确目标；动态解析前或广播为零 |
| targetNodeEpoch | uint64，精确解析/返回附着关系；解析前为零 |
| serviceId | uint32，mode 2/3 必填；响应中为未使用的零 |
| bindingKey | uint64，mode 2 使用 |
| hops | uint8：0 为服务到 Router 的初始 envelope，1 为已解析/已转发 |
| innerFrame | 完整 RPC Request 或 Response 帧，**包含自身 uint32 长度与 type** |

含公共两字节在内，内层帧之前的 Router 数据开销为 44 字节。内层声明长度必须恰好覆盖剩余字节。mode 4 要求内层为 Response，mode 1..3 要求内层为 Request。内层 Metadata、command、身份、body 与 routeKey 按普通 RPC 格式，不增加业务 codec。广播内层 requestId 必须为零。动态 mode 只在来源 Router 接受，解析一次后变为精确 mode 1。远端 Router 不向另一远端 Router 转发。

服务初始请求内层 requestId=0；若外层为 Call，来源 Router 将外层分配的 ID 复制到内层，并把 originRouterId 设为自身。转发/投递 envelope 为外层 Notify（ID=0），内层业务 ID 不变，originRouterId 非零。初始来源身份必须匹配直连服务附着关系；已声明的 Router 为转发来源担保；目标服务检查自身精确目标 ID/epoch。

服务回复使用 mode 4，以原来源 ID/epoch 为目标，并带上所回复请求的 originRouterId；source 字段标识响应服务。响应方所在 Router 直接把回复转发到该 origin Router（或本地投递）；originRouterId 为零时由它在自身视图中定位目标 Router。在 origin Router，最终原生 RPC Response 使用原 requestId、**外层 errorCode=0、空 Metadata**，body 中为完整的 Router version/op=14/mode=4 envelope。调用方在 RPC 认领 pending 完成后解包内层响应的实际业务/框架错误与 Metadata。Router 生成的失败改用原生正外层 errorCode 和空 body；Router 之间用 op 15 传递此类错误。两种形式都不改变基础 RPC 错误编码。

黄金向量（见 [RouterWireTest](../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)）：

| 帧 | 字节 |
| --- | --- |
| Sync(requester 1, known 2, broken 3) | `03 01 0000000000000001 0000000000000002 0000000000000003` |
| Begin(epoch 1, session 2, nodes 3, bindings 4) | `03 02 0000000000000001 0000000000000002 00000003 00000004` |
| Delta(epoch 1, session 2, seq 3, Bind (5,6)→(7,8)) | `03 06 0000000000000001 0000000000000002 0000000000000003 0001 03 00000005 0000000000000006 00000007 0000000000000008` |
| State(epoch 1, session 2, seq 9) | `03 07 0000000000000001 0000000000000002 0000000000000009` |

该测试还检查 envelope 的 origin、偏移 44 处的内层长度前缀、字段保留及借用所有权。Profile v3 与 v1、v2 不兼容：同步流操作、RoutedError 与数据 envelope 均已改变。拒绝其他版本，路由参与方需一起升级。RPC Wire framing 与普通 RPC 消息不变；旧锚点仅为保持链接而保留。旧版本或非 Router 端点不实现此显式启用的 command，跨语言 Router 互通仍未验证。
