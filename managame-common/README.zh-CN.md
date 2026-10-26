[English](README.md) | 中文

# managame-common

所有 managame 项目共用的基础类型，不依赖任何库：

- `Metadata` 与 `MetadataKey`：随每个游戏上下文传递的少量带类型的键值数据，例如 trace id、请求来自哪台服务器。
- `FrameworkErrorCodes`：框架应答失败时使用的错误码。

## Metadata

Metadata 是一段紧凑、不可变的字节序列，由若干 `uint16 key, uint16 length, value` 条目组成。键由 `MetadataKey`
定义类型，它把一个非零的 `uint16` id 和值的编解码器绑在一起。值只在读取时才解码。

```java
static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);
static final MetadataKey<Long> FROM_SERVER = MetadataKey.longKey(2);

Metadata metadata = Metadata.builder()
        .put(TRACE_ID, "a1b2c3")
        .put(FROM_SERVER, 7L)
        .build();

String trace = metadata.get(TRACE_ID);        // 不存在时为 null
long server = metadata.get(FROM_SERVER, 0L);  // 不存在时取默认值
boolean traced = metadata.contains(TRACE_ID);
```

内置的键类型：`booleanKey`、`intKey`、`longKey`（大端）、`stringKey`（UTF-8）和 `bytesKey`。其它类型自己提供
`MetadataCodec`：

```java
static final MetadataKey<UUID> SESSION = MetadataKey.of(3, new MetadataCodec<>() {
    public byte[] encode(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    public UUID decode(byte[] bytes, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
});
```

在网络上传输的就是它的字节：

```java
byte[] wire = metadata.bytes();           // 共享的数组，不要修改
Metadata received = Metadata.wrap(wire);  // 校验格式，不复制
```

规则：

- 键 id 取值 1–65535，同一个 metadata 内唯一；对已有的键再次 `put` 保留最后一次的值。
- 整体编码最多 65535 字节，超出时 `build()` 和 `wrap()` 抛 `IllegalArgumentException`；`wrap()` 还会拒绝被截断或键重复的数据。
- 每次 `build()` 返回一份独立的快照；`Metadata.empty()` 是共享的空实例。
- 键 id 由业务分配。请集中定义，保证集群里所有服务器一致。

## 错误码

0 表示成功，所有失败码都是正数。框架目前使用以下错误码：

| 错误码 | 名称 | 含义 |
| --- | --- | --- |
| 2002 | `RPC_UNAVAILABLE` | 对端没有可用连接、连接无法接收这次调用，或等待应答期间连接断开 |
| 2003 | `RPC_TIMEOUT` | 在节点的超时时间内没有收到应答 |
| 2005 | `RPC_NODE_CLOSED` | 发起调用的节点已关闭 |
| 2006 | `RPC_HANDLER_ERROR` | 对端无法编码处理器返回的结果 |
| 2007 | `RPC_PROTOCOL_ERROR` | 请求或结果无法解码 |
| 3001 | `RUNTIME_CLOSED` | runtime 已关闭，不再接受新任务 |
| 3002 | `HANDLER_NOT_FOUND` | 没有处理该消息类型的处理器 |
| 3005 | `INVALID_ROUTE_KEY` | 处理器所在的 domain 无法为该消息找到路由键 |
| 3006 | `ROUTE_EXECUTOR_OVERLOADED` | 该 domain 的执行器排队任务过多 |
| 3009 | `RUNTIME_EXECUTION_ERROR` | 处理器抛出了异常 |

其余错误码（`RPC_PEER_NOT_FOUND`、`RPC_PEER_REMOVED`、`HANDLER_CONTEXT_MISMATCH`、`ROUTE_DOMAIN_MISMATCH`、
`ROUTE_EXECUTOR_CLOSED`、`ROUTE_CALL_EXECUTION_ERROR`、`ROUTE_CALLBACK_DISPATCH_FAILED`、`DATA_SAVE_FAILED`、
`DATA_LOG_SAVE_FAILED`）已为框架预留，目前不会出现。号段：RPC 2001–2999，runtime 3001–3999，数据 4001–4999；
业务自己的错误码应避开这些号段。

打日志时，`FrameworkErrorCodes.describe(2003)` 得到 `RPC_TIMEOUT(2003)`，`name(code)` 只得到名称；未知错误码显示为
`UNKNOWN(<code>)`。不要用名称做程序判断。

## 构建

```bash
mvn install
```

构建 managame-runtime 之前先安装它。
