# 本仓库 RPC Wire Profile v2 — 2026-10 RPC binding

[English](rpc-wire.md) | **[简体中文](rpc-wire.zh-CN.md)**

本文件是字节布局的唯一来源。此 binding 替代旧的未实现草案：nodeId 从 uint64 改为 uint32，Slot 从 uint16 改为 uint8，errorCode 取消高位标记。旧草案从未发布实现；不能与旧布局混用。未来已部署版本的破坏性修改必须提升协议版本。

语义见 [RPC Specification](ogbs/OGBS-RPC-1.0.zh-CN.md)，Java 接入见 [RPC Java 开发规范](ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)，Metadata 与错误码见 [Core](ogbs/OGBS-Core-1.0.zh-CN.md)。

所有多字节整数为 big-endian。Java int/long 保存原始位；nodeId 与 command 的负 int 是合法 uint32；requestId 不同，它是 1..2147483647 的正数（0 为 Notify），负值视为坏帧。errorCode 仅允许 0..2147483647。

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
| requestId | int32，0 为 Notify，否则 1..2147483647 |
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
| requestId | int32，1..2147483647 |
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
<a id="router-profile-v4"></a>
<a id="router-profile-v5"></a>

## Router Profile v5（显式启用的 RPC payload）

[Router 语义](ogbs/OGBS-Router-1.0.zh-CN.md)和 [Java 绑定](ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md)定义角色与所有权。本节是 Router 字节布局的唯一定义。RPC framing、消息类型和握手不变。Router 流量使用 Request.command **0x80000288**（Java **-2147483000**），在启用路由的 Node 中保留；普通业务 command 不得使用它。外层 Metadata 与业务身份为空/零。Data 请求的外层 routeKey 取内层请求的 routeKey；每个控制操作（Attach、Detach、Locations、Unlocate）都使用 routeKey 1，因此同一连接上的全部控制流量共用一个 Slot，按发送顺序到达。

每个 Router 请求 body 以 `profileVersion:uint8=5` 开头，随后是 `operation:uint8`。其他版本、不支持的操作、截断或尾随数据均非法。所有整数为大端序。nodeId 为非零 uint32，nodeEpoch 为非零 uint64，serviceId 为限定在 1..2147483647 的 uint32。Router Profile 流量在服务与它所附着的那一个 Router 之间传输，也在网格的两个 Router 之间传输。方向列是规范性的：接收方必须拒绝来自已声明 Router 的 Attach 或 Detach，也必须拒绝来自非已声明 Router 的 Locations 或 Unlocate。

| Op | 名称 | 方向 | 公共两字节头之后的字段 |
| --- | --- | --- | --- |
| 1 | Attach | 服务 → Router，Notify | nodeEpoch:uint64, serviceId:uint32 |
| 2 | Detach | 服务 → Router，Notify | nodeEpoch:uint64 |
| 3 | Data | 服务 ↔ Router，Router ↔ Router | 下文 envelope，随后是内层帧 |
| 4 | Locations | Router → Router，Notify | replace:uint8, entryCount:uint16，随后 entryCount × (nodeId:uint32, nodeEpoch:uint64, serviceId:uint32) |
| 5 | Unlocate | Router → Router，Notify | entryCount:uint16，随后 entryCount × (nodeId:uint32, nodeEpoch:uint64) |

Attach 声明本连接上的 Node，Node ID 即直连 RPC Peer。Router 在该 Node 保持连接期间保存每个 Node 的最新声明，收到其他 nodeEpoch 或 serviceId 时替换。Detach 仅在 nodeEpoch 相同时删除声明。两者都没有确认：服务在每条新连接上及周期性地重复 Attach，因此重启的 Router 会重新得知它；收到自己并未使用的 Router 发来的 Data 时，服务再回一条 Detach。以 Call 发送的 Attach 或 Detach 被拒绝。

### Locations 与 Unlocate

Locations 上报附着在**发送方** Router 上的服务 Node；发送方 Router 即直连 RPC Peer，因此它不是一个字段。`replace=1` 表示条目列表是发送方的完整集合，替换它先前上报的全部内容——未经 Unlocate 就消失的 Node 正是这样被忘掉的；`replace=0` 只对所列条目做 upsert。每个条目定位一个 Node：接收方保存 `(nodeId → nodeEpoch, serviceId, 上报的 Router)`，并替换同一 Router 为该 nodeId 上报的既有条目。两个 Router 上报同一个 nodeId 属于配置错误；接收方可保留任意一个，而它自己的在线附着永远胜过任何上报。

Unlocate 删除上报来的条目，且仅在 nodeId 与 nodeEpoch 都与已存条目匹配时删除，于是一条与更新上报竞争失败的删除不会误删更新的位置。

来自某个 Router 的 Data 帧同时把它自己的 `sourceNodeId`/`sourceNodeEpoch`/`sourceServiceId` 定位在那个 Router 上，效果等同于随帧携带了一条单条目的 `replace=0` Locations。控制操作与 Data 使用不同 routeKey，因而走不同 Slot，所以响应不能依赖某条上报抢在需要它的那个请求之前到达。

`entryCount` 取值 0..65535，payload 必须在所声明的条目之后恰好结束。`entryCount=0` 仅在 `replace=1` 时合法，含义是"我没有任何附着"；零条目的 Unlocate 或 `replace=0` 的 Locations 非法。完整集合必须装进一个 RPC 帧：在 `maxFrameSize` 为 4 MiB 时，限制来自 uint16 条目数而不是帧长。以 Call 发送的 Locations 或 Unlocate 被拒绝，两者都不被确认：下一次周期性完整集合会修复任何丢失。

payload 大小：Locations 为 5 + 16 × entryCount 字节，Unlocate 为 4 + 12 × entryCount，均含公共两字节。

### Data envelope

| 字段 | 宽度 / 含义 |
| --- | --- |
| mode | uint8：1 Node 请求，2 服务广播，3 响应 |
| sourceNodeId | uint32，发起的服务 Node；服务发出该帧时等于直连 Peer，Router 转交时仍保持为发起者 |
| sourceNodeEpoch | uint64，发送方代际 |
| sourceServiceId | uint32，发送 Node 所属服务 |
| targetNodeId | uint32：请求目标，或响应中的调用方；服务发出的广播中为 0 |
| targetNodeEpoch | uint64：服务发出的请求中为 0（当前代际），响应中为调用方代际；Router 投递时总是精确值 |
| targetServiceId | uint32：广播的服务；其他情况为 0 |
| innerFrame | 完整 RPC Request 或 Response 帧，**包含其自身 uint32 长度和类型** |

连同公共两字节，envelope 共 35 字节；加上外层 Request 头（长度前缀和 28 个固定字节），Data 请求比其内层帧长 67 字节，并且必须符合 RPC 帧上限。内层声明长度必须恰好覆盖剩余字节。mode 3 要求内层为 Response；mode 1 和 2 要求内层为 requestId 为 0 的 Request。内层 Metadata、command、身份、body 与 routeKey 遵循普通 RPC 格式。

服务把 Node 请求（mode 1）发给自己的 Router：Notify，或需要响应时用 Call。Router 同时把该帧视为其来源的 Attach，然后解析 targetNodeId。目标以 targetNodeEpoch（为 0 时不限代际）附着在本 Router 上时直接投递：作为外层 Notify，targetNodeEpoch 改为附着的代际；若是 Call，内层 requestId 改为外层调用 ID。若是另一个 Router 上报过该目标，则把该帧一次性以 Notify 交给那个 Router，targetNodeEpoch 置为上报的代际，由它投递。除这些字段外，Router 逐字节原样传递内层帧。**来自 Router** 的帧只会被投递给本地已附着的目标，绝不再次转出。不存在可用位置时，Router 立即以原生 Response 回答 Call，errorCode 为 RPC PEER_NOT_FOUND；向目标或向下一个 Router 发送被拒绝时为 UNAVAILABLE；Notify 直接丢弃。

广播（mode 2）是 Notify。接收该帧的 Router 向附着在自己身上的 targetServiceId 的每个 Node（包括发送方）各投递一份，地址为该 Node 的精确 ID 与代际；并把该帧一次性交给网格中每一个其他已连接 Router，targetNodeId 与 targetNodeEpoch 保持为 0。来自 Router 的广播只在本地投递。

响应（mode 3）是响应服务发往投递该请求的 Router 的 Notify，目标为调用方的 ID 与代际。该 Router 按 targetNodeId 路由它，与 mode 1 完全相同：调用方附着在此处时，用原生 RPC Response 回答调用方挂起的 Call，携带内层 requestId，**外层 errorCode 为 0、Metadata 为空**，body 为完整的 Router version/op=3/mode=3 payload，调用方再解开内层响应的实际错误与 Metadata；若是另一个 Router 上报过调用方，则把该帧一次性交给那个 Router，由它完成该调用。由于服务 Node 只附着一个 Router，这次解析必然抵达持有该待完成调用的 Router。调用方已不再以该代际附着、或没有可用位置时，响应被丢弃。服务只接受自己正在使用的那个 Router 发来的 Data，且 targetNodeId 与 targetNodeEpoch 必须是自己的。

黄金向量（来自 [RouterWireTest](../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java)）：

| 帧 | 字节 |
| --- | --- |
| Attach(epoch 5, service 6) | `05 01 0000000000000005 00000006` |
| Detach(epoch 5) | `05 02 0000000000000005` |
| Locations(replace，一条：node 7、epoch 8、service 9) | `05 04 01 0001 00000007 0000000000000008 00000009` |
| Unlocate(一条：node 7、epoch 8) | `05 05 0001 00000007 0000000000000008` |
| Data(Node 请求 1/2/3 → 4/5，command 44，routeKey 9，无 body) | `05 03 01 00000001 0000000000000002 00000003 00000004 0000000000000005 00000000 0000001c 03 0000002c 00000000 0000000000000009 00 0000000000000000 0000` |

Profile v5 与 v1–v4 不兼容。相对 v4，Data envelope 逐字节相同，原有三个操作也未变，但 Data 现在也在 Router 之间流动，新增了 Locations 与 Unlocate，而且服务只附着一个 Router 而不是全部，因此一个 v4 参与方既不会上报也不会接受位置，并且仍会把自己的流量铺到每个 Router 上。相对 v1–v3，业务键绑定的 Router 同步、服务注册与绑定控制以及 RoutedError 依旧不复存在：v5 只复制 Node 位置。拒绝其他版本，路由参与方需一起升级。RPC Wire framing 与普通 RPC 消息不变；旧锚点仅为保持链接而保留。跨语言 Router 互通仍未验证。
