[English](README.md) | 中文

# managame-common

所有 managame 项目共用的基础类型，不依赖任何库：

- `Metadata` 与 `MetadataKey`：随每个游戏上下文传递的少量带类型的键值数据，例如 trace id、请求来自哪台服务器。
- `IdGenerator`：生成唯一 long id 的接口，只有 `generateId()` 一个方法；自带 `SnowflakeIdGenerator`（跨服、合服也唯一，按时间递增，可以在 id 里存一部分应用自己的数据，布局由 `IdLayout` 定）和 `SequenceIdGenerator`（进程内自增）。
- `FrameworkErrorCodes`：框架应答失败时使用的错误码。
- `Metrics`：计数器和 gauge，框架和业务都通过它上报指标，由应用接到自己的后端（Micrometer、Prometheus、
  OpenTelemetry），在 `cn.managame.metrics` 包中。

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
- `metadata.toBuilder()` 在现有内容上增加或替换一些值，得到新的 metadata，原来的不变：`metadata.toBuilder().put(FROM_SERVER, 8L).build()`。
- 键 id 由业务分配。请集中定义，保证集群里所有服务器一致。

## 唯一 id

```java
IdGenerator mails = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId);   // 数据部分放服务器 id
long mailId = mails.generateId();

SnowflakeIdGenerator items = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId << 4);
long itemId = items.generateId(serverId << 4 | ITEM_KIND);   // 每个 id 自带数据，再放一个类型
long kind = IdLayout.DEFAULT.data(itemId) & 0xF;             // 从任何 id 读回数据、时间、序号

IdGenerator rooms = new SequenceIdGenerator();               // 1、2、3……只在本进程内唯一
IdGenerator own = () -> idService.next();                    // 也可以是应用自己的实现
```

- `IdGenerator` 只有一个方法 `generateId()`。框架自带两个实现：要存库或被别的服务器看到的 id 用 `SnowflakeIdGenerator`；活得不比进程长的对象（房间、战斗、地图掉落）用 `SequenceIdGenerator`。应用也可以自己实现，比如从数据库按段预取 id；被多个线程调用的实现要线程安全。
- `SnowflakeIdGenerator` 的 id 从高位到低位是：时间（自 `epoch` 起的 tick 数）、序号（同一 tick 内的第几个）、应用自己的数据。`IdLayout.DEFAULT` 是 41 位毫秒（从 2026 年起，用到 2095 年）、12 位序号（每个生成器每毫秒 4096 个）、10 位数据；可以按需要自己定，比如要让 JavaScript 的 number 精确表示（小于 2^53）：`new IdLayout(epoch, Duration.ofSeconds(1), 31, 10, 12)`。
- 数据部分怎么排由应用决定，但必须能区分同时生成 id 的每一个生成器，以及将来 id 可能相遇的每一个（例如合服）：通常放服务器 id，再加上应用想要的位（类型、分片）。整个集群用同一个布局，有 id 之后不能再改。
- 同一个生成器的 id 一定递增，与带什么数据无关。不加锁，任意线程都可以调用。
- 用系统时钟，不用 `GameTime`（它可能被调整）。生成器从不返回超前于时钟的 id：一个 tick 的序号用完时，下一个 id 用下一个 tick，`generateId` 等时钟走到那里（正常最多一个 tick）。所以只要重启时时钟已经过了最后一个 id 的时间，重启后也不会重复。时钟被往回调时，id 接着上一个的时间继续，用完那个 tick 后等时钟追上。不能等待的线程（比如 EventLoop）要让生成速度远低于每 tick `2^sequenceBits` 个。

## 指标

框架和业务通过 `Metrics` 上报指标，它不依赖任何指标库：应用在自己的后端上实现它，就像把 `System.Logger` 桥接到自己的日志一样。
组件通过 builder 设置拿到它，默认是 `Metrics.noop()`；业务代码用应用持有的那个实例。

```java
// 只在组件构建时，或它要统计的东西加入时创建一次：
Counter refused = metrics.counter("managame.runtime.tasks.refused", "domain", "1");
metrics.gauge("game.players.online", sessions::size);                // 后端采集时读取
Meter sent = metrics.counter("game.mails.sent", mailer::sentCount);  // 别处已经在数的值，同样采集时读取

// 每次事件：一次调用，不拼 tag，不分配内存
refused.increment();

sent.close();   // 读取的对象不在了：从后端移除
```

| 方法 | 返回 | 用于 |
| --- | --- | --- |
| `counter(name, tags...)` | `Counter`：`increment()`、`increment(n)` | 事件，比如被拒绝的任务、超时的调用 |
| `counter(name, count, tags...)` | `Meter`：`close()` | 别处已经在数的值，比如组件本来就在累加的字段，后端采集时读取；只增不减 |
| `gauge(name, value, tags...)` | `Meter`：`close()` | 会涨会落的值，比如在线人数、排队的任务数，后端采集时读取 |

规则：

- 名字是小写点分的，每段以字母开头，后面是字母、数字或下划线。框架的指标叫 `managame.<组件>.<区域>.<名称>`，对应它的
  logger 名 `cn.managame.<组件>.<区域>`；应用的指标用自己的前缀，比如 `game.shop.purchases`。
- tag 按 key、value、key、value 排列，key 的规则和名字相同。value 只能取自一个小集合（domain、错误码名、node id），不能是玩家
  id 或路由键：每个不同的值都是一条单独的序列。
- 指标创建一次，存在字段里，每个名字加 tag 组合只创建一次。所有指标都可以在任意线程使用。
- 上报的开销就是后端自己的指标的开销，不多一分：managame-benchmark 的 `MetricsBenchmark` 经 `Metrics` 往 LongAdder 计数（Micrometer
  也用这类分段累加器计数），单线程和四线程共用时都和直接用 LongAdder 一样快。`Metrics.noop()` 是同一个空指标，方法被 JIT 内联掉；gauge 和别处在数的计数器在后端读取之前没有任何开销。
- 不提供计时器，这里的指标都按累计值读取。需要计时的组件（比如 rpc 的调用、runtime 的任务）用 `LatencyBuckets` 分桶累加，再作为计数器上报，两次读数相减就是这段时间的分布。
  组件在 `Metrics.noop()` 下不读时钟。
- `close()` 把 gauge 或别处在数的计数器从后端移除，并放开它读取的对象；用于会消失的东西，比如一个对端。计数器与后端同寿：
  对端用同一个 node id 回来后，还是上报到同一条序列。

### 接到 Micrometer

框架不提供桥接项目；用 Micrometer 的应用自己写一个，只要几行：

```java
public final class MicrometerMetrics implements Metrics {
    private final MeterRegistry registry;
    /** FunctionCounter 读取的对象：Micrometer 只持有它的弱引用。 */
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

Micrometer 会按各个后端转换名字：对 Prometheus，点换成下划线。接 OpenTelemetry 时，这几个方法分别对应它的 counter、observable counter 和
observable gauge。

### 定时输出到日志

没有监控系统的应用，可以定时（比如每分钟）把收到的指标全部写进日志。计数器都是累计值，两行日志相减就是这段时间发生的事：

```java
public final class LoggedMetrics implements Metrics {
    private static final System.Logger LOG = System.getLogger("game.metrics");
    private final Map<String, LongAdder> counts = new ConcurrentSkipListMap<>();
    private final Map<String, DoubleSupplier> readings = new ConcurrentSkipListMap<>();

    public LoggedMetrics(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(this::log, 1, 1, TimeUnit.MINUTES);
    }

    private void log() {
        counts.forEach((name, count) -> LOG.log(Level.INFO, name + " = " + count.sum()));
        readings.forEach((name, value) -> LOG.log(Level.INFO, name + " = " + value.getAsDouble()));
    }

    @Override public Counter counter(String name, String... tags) {
        return counts.computeIfAbsent(key(name, tags), key -> new LongAdder())::add;
    }

    @Override public Meter counter(String name, LongSupplier count, String... tags) { return gauge(name, count::getAsLong, tags); }

    @Override public Meter gauge(String name, DoubleSupplier value, String... tags) {
        String key = key(name, tags);
        readings.put(key, value);
        return () -> readings.remove(key);
    }

    private static String key(String name, String... tags) { return tags.length == 0 ? name : name + List.of(tags); }
}
```

## 错误码

0 表示成功，所有失败码都是正数。框架目前使用以下错误码：

| 错误码 | 名称 | 含义 |
| --- | --- | --- |
| 2002 | `RPC_UNAVAILABLE` | 对端没有可用连接、对端的新实例替换了被调用的实例，或对端被移除、发起调用的节点已关闭 |
| 2003 | `RPC_TIMEOUT` | 在调用的超时时间内没有收到应答 |
| 2006 | `RPC_HANDLER_ERROR` | 对端的 RPC 处理器抛出了异常 |
| 2007 | `RPC_PROTOCOL_ERROR` | 请求超过对端能接收的长度，或应答超过调用方能接收的长度 |
| 2008 | `RPC_OVERLOADED` | 对端的连接繁忙，或等待应答的调用太多 |
| 3001 | `RUNTIME_CLOSED` | runtime 已关闭，不再接受新任务 |
| 3002 | `HANDLER_NOT_FOUND` | 没有处理该消息的处理器 |
| 3005 | `INVALID_ROUTE_KEY` | 处理器所在的 domain 无法为该消息找到路由键 |
| 3006 | `ROUTE_EXECUTOR_OVERLOADED` | 该 domain 的执行器排队任务过多 |
| 3009 | `RUNTIME_EXECUTION_ERROR` | 处理器抛出了异常 |
| 4001 | `DATA_SAVE_FAILED` | 一条修改无法保存，已交给数据错误处理器；或 flush、close 结束时仍有修改没有保存 |
| 4002 | `DATA_LOG_SAVE_FAILED` | 一条日志无法保存，或因队列已满被拒绝 |
| 4003 | `DATA_LOAD_FAILED` | 无法从存储加载数据 |

其余错误码（`HANDLER_CONTEXT_MISMATCH`、`ROUTE_DOMAIN_MISMATCH`、`ROUTE_EXECUTOR_CLOSED`、
`ROUTE_CALL_EXECUTION_ERROR`、`ROUTE_CALLBACK_DISPATCH_FAILED`）已为框架预留，目前不会出现。号段：RPC 2001–2999，
runtime 3001–3999，数据 4001–4999；业务自己的错误码应避开这些号段。

打日志时，`FrameworkErrorCodes.describe(2003)` 得到 `RPC_TIMEOUT(2003)`，`name(code)` 只得到名称；未知错误码显示为
`UNKNOWN(<code>)`。不要用名称做程序判断。

## 构建

```bash
mvn install
```

构建使用它的项目之前先安装它。测试覆盖 metadata 的读取、派生与校验，id 生成器，以及指标：应用自己的后端实现 `Metrics`，和
no-op 实现。
