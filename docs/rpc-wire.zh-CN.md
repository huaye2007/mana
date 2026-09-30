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
