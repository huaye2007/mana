# OGBS Core Java 25 Development Specification 1.0

[English](OGBS-Core-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Core-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应标准：[OGBS Core Specification](OGBS-Core-1.0.zh-CN.md)。

状态：当前 game-core Java 25 实现约定。本文与 Core 标准共同约束 Java 实现；Metadata 格式和共享错误码的规范定义仍以标准文档为单一来源。

## 1. 模块与职责

Maven 坐标为 cn.managame:game-core:1.0.0-SNAPSHOT，要求 JDK 25，无 preview 特性。公开类型位于 cn.managame.core；不依赖 Network、Runtime、Data、RPC。game-core 没有第三方依赖（见 [Core POM](../../game-core/pom.xml)）；各组件自行声明所用的库（Runtime 为空闲 Mailbox 缓存声明 Caffeine）。Core 的 Metadata/错误码 API 不创建缓存、线程或外部资源。

公开类型包括 Metadata（含嵌套 Builder）、MetadataKey、MetadataCodec、FrameworkErrorCodes。Metadata 与 MetadataKey 是 final 类：每个 Metadata 实例都已通过结构校验，RPC 编码器等使用方无需再次检查。

## 2. Metadata API 与行为

```java
public final class Metadata {
    public static Metadata empty();
    public static Metadata wrap(byte[] encoded);
    public static Builder builder();
    public <T> T get(MetadataKey<T> key);
    public <T> T get(MetadataKey<T> key, T defaultValue);
    public boolean contains(MetadataKey<?> key);
    public boolean isEmpty();
    public byte[] bytes();

    public static final class Builder {
        public <T> Builder put(MetadataKey<T> key, T value);
        public Metadata build();
    }
}

public final class MetadataKey<T> {
    public static <T> MetadataKey<T> of(int id, MetadataCodec<T> codec);
    public static MetadataKey<Boolean> booleanKey(int id);
    public static MetadataKey<Long> longKey(int id);
    public static MetadataKey<Integer> intKey(int id);
    public static MetadataKey<String> stringKey(int id);
    public static MetadataKey<byte[]> bytesKey(int id);
    public int id();
}

public interface MetadataCodec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes, int offset, int length);
}
```

内置 Key 与构造方法：

| 方法 | 行为 |
| --- | --- |
| MetadataKey.booleanKey(id) | 1 字节，false=0、true=1；其他值或长度抛 IllegalArgumentException |
| MetadataKey.longKey(id) | 8 字节 big-endian；解码长度不是 8 时失败 |
| MetadataKey.intKey(id) | 4 字节 big-endian；解码长度不是 4 时失败 |
| MetadataKey.stringKey(id) | UTF-8 |
| MetadataKey.bytesKey(id) | 原始字节值 |
| MetadataKey.of(id, codec) | 自定义编码类型 |
| Metadata.empty() | 空 Metadata |
| Metadata.wrap(encodedBytes) | 校验结构，包装原数组，不做防御性复制 |
| Metadata.builder() | 构造新 Metadata |

MetadataKey.id() 返回数值 Key。不同 MetadataKey 实例只要 id 相同，就查询同一个 wire 条目；编码一致性由定义方负责，没有全局 Key 注册中心。

get 未命中返回 null，带 defaultValue 的重载在未命中时返回默认值。get 调用的 codec 出错时直接传播异常。bytes() 返回共享底层数组；bytesKey 的 get 为返回独立 Java byte[] 而提取对应范围，不是让所有 get 都复制整个 Metadata。

Builder 禁止 null value。重复 put 相同 id 会替换该 Builder 内原值；build 输出只有一个该 Key 的条目。build 重新组装编码数组，因此后续修改 Builder 不改变先前构造的 Metadata。

```java
var traceId = MetadataKey.longKey(1024);
var locale = MetadataKey.stringKey(1025);
var debugEnabled = MetadataKey.booleanKey(1026);
var retryCount = MetadataKey.intKey(1027);
Metadata metadata = Metadata.builder()
    .put(traceId, 123456L)
    .put(locale, "zh-CN")
    .put(debugEnabled, true)
    .put(retryCount, 3)
    .build();
long trace = metadata.get(traceId, 0L);
boolean debug = metadata.get(debugEnabled, false);
int retries = metadata.get(retryCount, 0);
```

示例 ID 是应用选择，不是框架预置字段。默认实现每次 get 线性扫描并按需解码，不承诺解码缓存或零分配。

## 3. 所有权、并发与失败

Java byte[] 不具备语言级不可变性。wrap 与 bytes() 共享数组，调用方必须遵守标准中的只读约定；本实现不冻结数组，也不提供复制隔离。Builder 在 put 时保存 codec 返回的字节数组，在 build 时组装新数组；调用方及 codec 不得在 build 前并发修改这些编码值。

Metadata.Builder 不保证并发安全。构建完成的 Metadata 在底层字节不被修改时可共享读取；自定义 MetadataCodec 是否支持并发由提供者保证。get 按需调用 codec，异常直接传播，不做通用重试或缓存。

| 情况 | Java 行为 |
| --- | --- |
| MetadataKey ID 超出 1..65535 | IllegalArgumentException |
| null codec、编码数组、key 或 put value | NullPointerException |
| wrap 结构损坏、重复 ID、零 ID、长度越界 | IllegalArgumentException |
| Builder 单值或整个容器超长 | IllegalArgumentException |
| 读取内置 boolean/int32/int64 时值格式或长度非法 | IllegalArgumentException |
| 缺失 Key | get 返回 null，带默认值重载返回默认值 |
| 自定义 codec 失败 | 原异常传播 |

stringKey 使用 JDK StandardCharsets.UTF_8 的 String 编解码；bytesKey 解码返回该条目的独立 byte[]。默认线性扫描实现不承诺零分配或已解码值缓存。优化算法不得改变 Key 数值身份、惰性解码或数组共享契约。

## 4. 共享错误码绑定与兼容性

FrameworkErrorCodes 不发布 Router 常量：game-router 报告 RPC 错误码，已退役的 5001..5004 不重新使用。报告路径见 [Router Java 规范](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)。

Java FrameworkErrorCodes 暴露 public static final int 常量，对应 [Core 标准的错误码分配](OGBS-Core-1.0.zh-CN.md#4-frameworkerrorcode)。不得在 Java 文档复制维护第二套编号表。已定义常量值不可随意重排；新增编号先修订标准，再同步 Java 常量及消费方。`FrameworkErrorCodes.name(code)` 返回常量名（例如 `RPC_TIMEOUT`），`describe(code)` 返回名称加编号（`RPC_TIMEOUT(2003)`），未分配或业务编号为 `UNKNOWN(<code>)`；二者只用于日志与诊断，程序逻辑必须比较数值。

RPC 实现采用 Core 定义的 2001..2007 编号分配，替代未实现草案；RpcErrorCodes 只引用 FrameworkErrorCodes。errorCode 不再有高位包装，0 成功、1..9999 框架、10000..Integer.MAX_VALUE 业务，负数非法。旧 RPC 常量发生源码/编号变更，不能与旧草案混用；Runtime/Data 编号未变。

Network 当前使用 WriteStatus、NetworkException、ConnectionHandler.onException 表达状态和失败；game-core 的 Network 编号区间仅预留，不强制 Network 依赖 game-core 或为每次关闭分配错误码。

Java 方法签名或异常形式变更应说明源码兼容性；Metadata 字节格式和跨进程错误码含义变更必须先处理标准及 Wire Profile 的协议兼容性。

<a id="5-可观测性绑定"></a>

## 5. 可观测性绑定

包 `cn.managame.core.metrics`，实现 [Core §5](OGBS-Core-1.0.zh-CN.md#5-可观测性)。三个类型、零依赖：Core 依旧不依赖任何第三方，这正是该契约放在此处而不是放在某个指标库上的原因。

| 类型 | 形状 |
| --- | --- |
| `MetricSink` | `void gauge(String name, long value)`、`void counter(String name, long value)`。由应用实现；在收集线程上每条读数调用一次 |
| `MetricsSource` | `@FunctionalInterface`，`void read(MetricSink sink)`。由组件发布；上报自己本来就持有的值，且不得抛异常 |
| `MetricsRegistry` | `publish(String prefix, MetricsSource)`、`boolean withdraw(String prefix)`、`List<String> prefixes()`、`void read(MetricSink)`、`MAX_NAME_LENGTH = 128` |

`publish` 与 `withdraw` 是 `synchronized` 且会复制源表，因此属于装配与关闭阶段；`read` 遍历一个 volatile 数组且不加锁，因此可与二者并发，也可与另一次读取并发。源按前缀顺序（字典序）访问，于是收集方的输出在多次读取之间、多个进程之间都稳定。以已存在的前缀发布会原地替换其源并保持位置不变。

`read` 为每个源包装调用方的 sink，用于加上 `<prefix>.` 前缀并校验该源上报的内容：C-OBS-02 的命名规则用字符扫描实现而非正则，非法名称与加前缀后超过 `MAX_NAME_LENGTH` 的名称都抛 `IllegalArgumentException`——报给组件作者，而不是把收集方无法解析的东西导出去。前缀、源或 sink 为 null 抛 `NullPointerException`。源抛出的 `RuntimeException` 会传播给 `read` 的调用方，且其后的源不再被读取：注册表不做隔离，因为 C-OBS-03 规定源不得抛异常。

Core 里没有任何东西做收集、调度或导出。有哪些读数属于各组件的 Java 规范——[Network §10](OGBS-Network-Java-25-Specification-1.0.zh-CN.md#10-stats)、[RPC §10](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#10-stats)，以及 Runtime、Data、Router 本来就提供的 `stats()` 记录——把它们适配到注册表并对外提供则属于 [Spring §5](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#5-可观测性)。

<a id="5-源码与验证"></a>
<a id="6-源码与验证"></a>

## 6. 源码与验证

- [Metadata](../../game-core/src/main/java/cn/managame/core/Metadata.java)：结构校验、查询与 Builder。
- [MetadataKey](../../game-core/src/main/java/cn/managame/core/MetadataKey.java)：Key 校验与内置 codec。
- [FrameworkErrorCodes](../../game-core/src/main/java/cn/managame/core/FrameworkErrorCodes.java)：共享编号绑定。
- [MetricsRegistry](../../game-core/src/main/java/cn/managame/core/metrics/MetricsRegistry.java)、[MetricsSource](../../game-core/src/main/java/cn/managame/core/metrics/MetricsSource.java)、[MetricSink](../../game-core/src/main/java/cn/managame/core/metrics/MetricSink.java)：可观测性契约。
- [MetadataTest](../../game-core/src/test/java/cn/managame/core/MetadataTest.java)：共享数组、惰性解码、结构损坏、边界、Builder 快照及编码向量。
- [MetricsRegistryTest](../../game-core/src/test/java/cn/managame/core/metrics/MetricsRegistryTest.java)：前缀顺序、替换与撤销、命名规则、加前缀后的长度上界、抛异常的源。

运行 mvn -pl game-core -am test；影响模块依赖或跨组件接入时运行 mvn clean verify。测试覆盖不表示已经完成跨语言互操作或生产性能验证。
