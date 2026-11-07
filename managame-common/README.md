English | [中文](README.zh-CN.md)

# managame-common

Types shared by every managame project, with no dependencies:

- `Metadata` and `MetadataKey`: small typed key-value data that travels with every game context, for example a
  trace id or the id of the server a request came from.
- `IdGenerator`: makes unique long ids, with one method, `generateId()`; the framework gives `SnowflakeIdGenerator`
  (unique across servers and their merges, ordered by time, holding some data of the application's own, laid out by
  an `IdLayout`) and `SequenceIdGenerator` (counting up in this process).
- `FrameworkErrorCodes`: the error codes the framework answers failures with.
- `Metrics`: counters, gauges and timers, which the framework and the application report through and the
  application binds to its own backend (Micrometer, Prometheus, OpenTelemetry), in package `cn.managame.metrics`.

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

## Unique ids

```java
IdGenerator mails = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId);   // the server id fills the data part
long mailId = mails.generateId();

SnowflakeIdGenerator items = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId << 4);
long itemId = items.generateId(serverId << 4 | ITEM_KIND);   // data per id, with a kind in it as well
long kind = IdLayout.DEFAULT.data(itemId) & 0xF;             // data, time and sequence read back from any id

IdGenerator rooms = new SequenceIdGenerator();               // 1, 2, 3... in this process only
IdGenerator own = () -> idService.next();                    // or the application's own
```

- `IdGenerator` has one method, `generateId()`. The framework gives two: `SnowflakeIdGenerator` for ids that are
  stored or seen by other servers, and `SequenceIdGenerator` for what lives no longer than the process (rooms,
  battles, drops on a map). An application may give its own, such as ids reserved in blocks from its database; one
  called from several threads must be safe to call from them.
- A `SnowflakeIdGenerator` id holds, from the high bits to the low, its time in ticks since the `epoch`, a sequence
  counting the ids of one tick, and the application's data. `IdLayout.DEFAULT` has 41 bits of milliseconds from 2026,
  lasting until 2095, 12 bits of sequence (4096 ids a millisecond per generator) and 10 bits of data. Lay out your own
  as needed, for example ids a JavaScript number holds exactly, under 2^53:
  `new IdLayout(epoch, Duration.ofSeconds(1), 31, 10, 12)`.
- The application lays out the data part, but it must tell apart every generator making ids at the same time and
  every one whose ids may meet later, as when servers merge: usually a server id, plus whatever bits the application
  wants (a kind, a shard). A cluster shares one layout, which cannot change once ids exist.
- Ids of one generator increase whatever data they hold. Any thread may call it; it takes no lock.
- Time is the system clock, not `GameTime`, which may be shifted. A generator never returns an id ahead of the clock:
  when a tick's sequence is used up, the next id takes the next tick and `generateId` waits for the clock to reach it,
  a tick at most as a rule. So ids stay unique across a restart as long as the clock at the restart is past the last
  id made. If the clock is set back, ids go on from the last one's time and wait for the clock once that tick is used
  up. A thread that must not wait, such as an event loop, should stay well under `2^sequenceBits` ids a tick.

## Metrics

`Metrics` is where the framework and the application report metrics, without depending on a metrics library: the
application implements it over its own backend, as `System.Logger` is bridged to its logging. A component takes one
as a builder setting, `Metrics.noop()` by default; business code uses the instance the application holds.

```java
// Once, when a component is built or something it measures is added:
Counter refused = metrics.counter("managame.runtime.tasks.refused", "domain", "1");
metrics.gauge("game.players.online", sessions::size);                // read when the backend collects
Meter sent = metrics.counter("game.mails.sent", mailer::sentCount);  // counted elsewhere, read the same way

// Per event: one call, no tags built, nothing allocated
refused.increment();

sent.close();   // when what it reads goes away: removed from the backend
```

| Method | Returns | For |
| --- | --- | --- |
| `counter(name, tags...)` | `Counter`: `increment()`, `increment(n)` | Events, such as tasks refused or calls timed out |
| `counter(name, count, tags...)` | `Meter`: `close()` | A count kept elsewhere, such as a field a component counts anyway, read when the backend collects; it never falls |
| `gauge(name, value, tags...)` | `Meter`: `close()` | A value that rises and falls, such as players online or tasks waiting, read when the backend collects |

Rules:

- Names are lowercase and dotted, segments of a letter followed by letters, digits or underscores. The framework's
  are `managame.<component>.<area>.<what>`, after its loggers `cn.managame.<component>.<area>`; the application's
  take a prefix of their own, such as `game.shop.purchases`.
- Tags are key, value, key, value, keys following the rule of names. Values must come from a small set (a domain,
  an error code's name, a node id), never a player id or a route key: every value makes a series of its own.
- Create meters once and keep them in fields, each name and tag set once. Every meter is safe to use from any thread.
- Reporting costs what the backend's own meter costs and nothing more: `MetricsBenchmark` in managame-benchmark counts
  through `Metrics` into a LongAdder, the kind of striped adder Micrometer counts in, as fast as into the LongAdder alone, by one thread or by
  four sharing it. `Metrics.noop()` is one empty meter, whose methods the JIT inlines away; a gauge and a counter kept
  elsewhere cost nothing until the backend reads them.
- There is no timer. Reading the clock around every task or call costs a hot path most of its time, for an average that
  finds nothing, so a component reports what it refuses, what fails and what it holds. An application times the calls
  it cares about with its own backend.
- `close()` removes a gauge or a counter kept elsewhere from the backend and lets go of what it reads, for one of
  something that goes away, such as a peer. Counters last as long as the backend: a peer back under the same node id
  reports to the same series again.

### Over Micrometer

The framework has no bridge project; an application on Micrometer writes its own, in a few lines:

```java
public final class MicrometerMetrics implements Metrics {
    private final MeterRegistry registry;
    /** What function counters read: Micrometer holds it weakly. */
    private final Map<io.micrometer.core.instrument.Meter, LongSupplier> counts = new ConcurrentHashMap<>();

    public MicrometerMetrics(MeterRegistry registry) { this.registry = registry; }

    @Override public Counter counter(String name, String... tags) { return registry.counter(name, tags)::increment; }

    @Override public Meter counter(String name, LongSupplier count, String... tags) {
        FunctionCounter counter = FunctionCounter.builder(name, count, LongSupplier::getAsLong).tags(tags).register(registry);
        counts.put(counter, count);
        return () -> { registry.remove(counter); counts.remove(counter); };
    }

    @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
        Gauge gauge = Gauge.builder(name, value, DoubleSupplier::getAsDouble).tags(tags).strongReference(true).register(registry);
        return () -> registry.remove(gauge);
    }
}
```

Micrometer converts names for each backend: for Prometheus, dots into underscores. Over OpenTelemetry, the same
methods map to its counter, observable counter and observable gauge.

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

Install it before building the projects that use it. The tests cover reading, deriving and validating metadata,
the id generators, and metrics: an application's own backend behind `Metrics`, and the no-op one.
