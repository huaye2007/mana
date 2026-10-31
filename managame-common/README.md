English | [中文](README.zh-CN.md)

# managame-common

Types shared by every managame project, with no dependencies:

- `Metadata` and `MetadataKey`: small typed key-value data that travels with every game context, for example a
  trace id or the id of the server a request came from.
- `FrameworkErrorCodes`: the error codes the framework answers failures with.

## Metadata

Metadata is a compact, immutable byte sequence of `uint16 key, uint16 length, value` entries. Keys are typed by
`MetadataKey`, which pairs a nonzero `uint16` id with a codec for its value. Values are decoded only when read.

```java
static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);
static final MetadataKey<Long> FROM_SERVER = MetadataKey.longKey(2);

Metadata metadata = Metadata.builder()
        .put(TRACE_ID, "a1b2c3")
        .put(FROM_SERVER, 7L)
        .build();

String trace = metadata.get(TRACE_ID);        // null when absent
long server = metadata.get(FROM_SERVER, 0L);  // default when absent
boolean traced = metadata.contains(TRACE_ID);
```

Built-in key types: `booleanKey`, `intKey`, `longKey` (big-endian), `stringKey` (UTF-8) and `bytesKey`. Any other
type takes its own `MetadataCodec`:

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

On the wire, metadata is its bytes:

```java
byte[] wire = metadata.bytes();           // shared, never modify it
Metadata received = Metadata.wrap(wire);  // validates the layout without copying
```

Rules:

- Key ids are 1–65535 and unique within one metadata; `put` with an existing key keeps the last value.
- The whole encoding is at most 65535 bytes; `build()` and `wrap()` throw `IllegalArgumentException` beyond that,
  and `wrap()` also rejects truncated or duplicate entries.
- Each `build()` returns an independent snapshot; `Metadata.empty()` is the shared empty instance.
- `metadata.toBuilder()` derives new metadata with values added or replaced, leaving the original as it was:
  `metadata.toBuilder().put(FROM_SERVER, 8L).build()`.
- The application owns the key ids. Allocate them in one place so that every server of a cluster agrees.

## Error codes

Zero is success and every failure code is positive. The framework uses these:

| Code | Name | Meaning |
| --- | --- | --- |
| 2002 | `RPC_UNAVAILABLE` | The peer has no connection up, its connection is too busy, too many calls to it wait, a new instance of it replaced the one asked, or it was removed or the calling node closed |
| 2003 | `RPC_TIMEOUT` | No answer within the call's timeout |
| 2006 | `RPC_HANDLER_ERROR` | The peer's RPC handler threw |
| 2007 | `RPC_PROTOCOL_ERROR` | The request is larger than the peer receives, or its answer larger than the caller does |
| 3001 | `RUNTIME_CLOSED` | The runtime is shut down and refuses new tasks |
| 3002 | `HANDLER_NOT_FOUND` | No handler for the message |
| 3005 | `INVALID_ROUTE_KEY` | The handler's domain could not find a route key for the message |
| 3006 | `ROUTE_EXECUTOR_OVERLOADED` | The domain's executor has too many tasks waiting |
| 3009 | `RUNTIME_EXECUTION_ERROR` | The handler threw |
| 4001 | `DATA_SAVE_FAILED` | A change could not be saved and went to the data error handler, or a flush or close left changes unsaved |
| 4002 | `DATA_LOG_SAVE_FAILED` | A log could not be saved, or was refused because its queue was full |
| 4003 | `DATA_LOAD_FAILED` | Data could not be loaded from storage |

The others (`HANDLER_CONTEXT_MISMATCH`, `ROUTE_DOMAIN_MISMATCH`, `ROUTE_EXECUTOR_CLOSED`,
`ROUTE_CALL_EXECUTION_ERROR`, `ROUTE_CALLBACK_DISPATCH_FAILED`) are allocated for the framework and not raised
today. Ranges: RPC 2001–2999, runtime 3001–3999, data 4001–4999; application codes should stay outside them.

For logs, `FrameworkErrorCodes.describe(2003)` gives `RPC_TIMEOUT(2003)` and `name(code)` the bare name; an
unknown code reads `UNKNOWN(<code>)`. Never base program logic on the names.

## Build

```bash
mvn install
```

Install it before building the projects that use it. The tests cover reading, deriving and validating metadata.
