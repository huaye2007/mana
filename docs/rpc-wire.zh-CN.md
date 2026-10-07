# 本仓库 RPC Wire Profile v1 — 2026-09 RPC binding

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
| version | uint16 | 1 |
| nodeId | uint32 | 非零且不等于本地 ID |
| slotId | uint8 | 0..slotCount-1 |
| slotCount | uint8 | 1..255 |

frameLength=13，完整帧=17 字节，不允许尾随数据。主动端先发送；被动端创建/复用 Peer、回送本地身份和相同 Slot 信息。主动端核对预期身份。重复握手、Slot 冲突、slotCount 不一致、握手前业务帧/心跳均关闭候选连接。

握手不是认证。V1 RpcNode 使用内部 TCP，接入可信性由部署网络边界保证。

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

同一本地 Node 内 Peer 重建不再重置 ID 分配，见 RPC Specification R-CALL-04；此连续性不改变字段、字节序或版本。Wire v1 没有 Node 代际字段，完整回绕迟到回复与跨本地 Node 替换的保存回复需遵守规范中的部署/应用边界。

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
0000000d 01 474e5352 0001 01020304 02 03
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

## Router Profile v2（显式启用的 RPC payload）

[Router 语义](ogbs/OGBS-Router-1.0.zh-CN.md) 与 [Java 绑定](ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) 定义角色/所有权，本节是唯一 Router 字节定义。RPC framing、消息 type 与握手版本不变。Router 流量使用 Request.command **0x80000288**（Java **-2147483000**），在启用路由的 Node 内保留，普通业务不能使用此 command。外层 Metadata/业务身份为空/零。数据 Request 复制内层请求亲和字段，控制 Request 使用 routeKey=1。

每个 Router 请求 body 先写 `profileVersion:uint8=2`，再写 `operation:uint8`。其他版本、不支持的操作、截断/多余控制字节、非法 owner 或快照计数均非法。全部整数大端。nodeId/Router ID 为非零 uint32，epoch 为非零 uint64，serviceId 为限制在 1..2147483647 的 uint32，bindingKey 为任意 uint64。

Node 条目为 `nodeId:uint32 + nodeEpoch:uint64 + serviceId:uint32`（16 字节）；绑定条目为 `serviceId:uint32 + bindingKey:uint64 + nodeId:uint32 + nodeEpoch:uint64`（24 字节）。

| Op | 名称 | 公共两字节头之后的字段 |
| --- | --- | --- |
| 1 | RouterHandshake | routerEpoch:uint64 |
| 2 | SnapshotBegin | routerEpoch:uint64, nodeCount:uint32, bindingCount:uint32, revision:uint64 |
| 3 | SnapshotNodes | routerEpoch:uint64, count:uint16, count × Node 条目 |
| 4 | SnapshotBindings | routerEpoch:uint64, count:uint16, count × 绑定条目 |
| 5 | SnapshotEnd | routerEpoch:uint64 |
| 6 | NodeAdd | routerEpoch:uint64, revision:uint64, Node 条目 |
| 7 | NodeRemove | routerEpoch:uint64, revision:uint64, nodeId:uint32, nodeEpoch:uint64 |
| 8 | RouteBind | routerEpoch:uint64, revision:uint64, 绑定条目 |
| 9 | RouteUnbind | routerEpoch:uint64, revision:uint64, 绑定条目 |
| 10 | NodeRegister | Node 条目 |
| 11 | NodeBind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 12 | NodeUnbind | nodeEpoch:uint64, serviceId:uint32, bindingKey:uint64 |
| 13 | NodeDetach | nodeEpoch:uint64 |
| 14 | RoutedData | 下述数据 envelope |
| 15 | RoutedError | routerEpoch:uint64, targetNodeId:uint32, targetNodeEpoch:uint64, requestId:uint32, errorCode:uint32 |
| 16 | Verify | expectedRouterEpoch:uint64, senderEpoch:uint64, senderRevision:uint64 |

Router 身份取直连 RPC Peer ID，握手 epoch 与当前连接关系限定其桶。Op 2..9/15 要求对应身份/epoch。Op 10..13 为直连服务 Node 控制，NodeRegister.nodeId 须等于直连 Peer。快照 Node 先于绑定，绑定须引用已注册且服务/epoch 匹配的 Node。结束时条数须精确等于暂存的不同条目数。分块条数为 1..256，Java 数量/容量上限见 Java 规范。

HELLO 允许在同一 RPC 连接上重启路由同步：丢弃未完成的入站暂存，要求新的 BEGIN/分块/END；在 END 前保留最后提交的桶。已完成双向同步的接收方也启动自身快照；交换仍在进行的接收方不重复回送 HELLO。它只重置路由协议状态，不重置 RPC 握手、Peer 或无关调用，该交换使用现有 HELLO/快照操作，不引入 RPC 消息 type。

控制操作使用非零 ID 的普通 RPC Call。成功 NodeRegister 与 Verify 的原生 RPC Response body 恰好包含 routerEpoch:uint64（八字节，无 Router header）；其他成功控制 body 为空，正 errorCode 表示拒绝。NodeRegister 对发现已移除的代际返回 NOT_REGISTERED。Verify 校验接收方 epoch 与发送方状态：Router 发送方需要同 senderEpoch、senderRevision 的已提交双向关系；服务发送方使用 nodeEpoch、senderRevision=0，并要求匹配注册。状态不匹配/缺失返回 NOT_REGISTERED；Verify 不注册、不改变权威，也不证明服务存在性。每个 Router Peer 只有一个在途控制，下一个在前者响应后发送；两端交换各自快照，不通过控制关联广播或业务请求。NodeBind 只确认本地 Router，不等待其他 Router 确认。

SnapshotBegin 捕获来源 revision。操作 6..9 对每次已接受本地修改（含幂等控制）将其加一，按 2^64 取模；增量 revision 必须为已提交来源 revision 加一。End 将捕获版本与暂存桶一起提交。版本缺口拒绝同步流，要求新快照，不定义重放日志。Verify 比较版本，即使未观察到短暂重连也能发现遗漏增量。

Profile v2 与早期 v1 草案不兼容：快照/增量字段及 NodeRegister ACK 已变化。拒绝 v1，路由参与方一起升级；RPC Wire v1 framing/握手及普通 RPC 消息不变。旧 Router-profile 锚点仅用于兼容链接。

### RoutedData envelope

| 字段 | 宽度/含义 |
| --- | --- |
| mode | uint8：1 精确 Node 请求，2 动态请求，3 服务广播，4 响应 |
| sourceNodeId | uint32，原发送服务 Node |
| sourceNodeEpoch | uint64，原来源附着关系 |
| targetNodeId | uint32，精确目标；动态解析前及广播为零 |
| targetNodeEpoch | uint64，精确解析/返回附着代际；解析前为零 |
| serviceId | uint32，mode 2/3 必须提供，响应中未使用时为零 |
| bindingKey | uint64，mode 2 使用 |
| hops | uint8：0 服务到 Router 的初始消息，1 已解析/转发消息 |
| innerFrame | 完整 RPC Request/Response 帧，**包含其自己的 uint32 length 和 type** |

含公共两字节头，内层帧之前的 Router 数据开销为 40 字节。内层声明长度须精确覆盖剩余字节。mode 4 要求内层 Response，mode 1..3 要求 Request。内层 Metadata、command、身份、body、routeKey 使用普通 RPC 格式，不增加业务 codec。广播内层 requestId 须为零。动态 mode 只在来源 Router 接受，解析一次后转为精确 mode 1。远端 Router 不向另一个远端 Router 中继。

初始服务请求内层 requestId=0；外层为 Call 时，来源 Router 把外层分配的 ID 复制到内层请求。转发/投递消息外层均为 Notify（ID=0），内层业务 ID 保持不变。初始来源身份须匹配直连服务附着关系；已同步、受信任的 Router 为转发来源负责；目标服务检查精确的目标 ID/epoch。hops=1 表示已转发/投递状态，不是物理连接数。

服务回复使用 mode 4，目标为原来源 ID/epoch，source 字段标识响应服务。中间仍通过 Notify 传输。来源 Router 最终发送的原生 RPC Response 使用原 requestId，**外层 errorCode=0、Metadata 空**，body 为完整 Router version/op=14/mode=4 envelope。原 RPC 取得 pending 完成权后，来源解包内层真正的业务/框架错误及 Metadata。Router 产生的错误则使用原生正外层 errorCode 和空 body；必要时 op 15 在 Router 间传递此类错误。错误 requestId 须非零，errorCode 须为正。这两种形式均不改变基础 RPC 错误编码。

握手 payload 黄金向量（epoch=0x0102030405060708）：`01 01 0102030405060708`。[RouterWireTest](../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java) 验证向量、envelope 长度、字段保留及借用所有权。旧/未启用 Router 的端点不实现这一显式 command；普通 RPC v1 支持不意味着支持 Router 互通。Router 跨语言互通未验证。
