# OGBS Core Java 25 Development Specification 1.0

文档类型：**Java 开发规范**。对应标准：[OGBS Core Specification](OGBS-Core-1.0.md)。

状态：当前 game-core Java 25 实现约定。本文与 Core 标准共同约束 Java 实现；Metadata 格式和共享错误码的规范定义仍以标准文档为单一来源。

## 1. 模块与职责

Maven 坐标为 cn.managame:game-core:1.0.0-SNAPSHOT，要求 JDK 25，无 preview 特性。公开类型位于 cn.managame.core；不依赖 Network、Runtime、Data、RPC 或第三方运行库，不创建线程和外部资源。

公开类型包括 Metadata、MetadataKey、MetadataCodec、MetadataBuilder、MetadataKeys、Metadatas、FrameworkErrorCodes。Metadatas 的编码容器及 Builder 实现保持内部封装，不因语言分层额外拆出 Maven artifact。

## 2. Metadata API 与行为

```java
public interface Metadata {
    <T> T get(MetadataKey<T> key);
    default <T> T get(MetadataKey<T> key, T defaultValue);
    boolean contains(MetadataKey<?> key);
    boolean isEmpty();
    byte[] bytes();
}

public interface MetadataCodec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes, int offset, int length);
}

public interface MetadataBuilder {
    <T> MetadataBuilder put(MetadataKey<T> key, T value);
    Metadata build();
}
```

以上是签名摘要，省略 default 方法体。创建工具：

| 方法 | 行为 |
| --- | --- |
| MetadataKeys.booleanKey(id) | 1 字节，false=0、true=1；其他值或长度抛 IllegalArgumentException |
| MetadataKeys.longKey(id) | 8 字节 big-endian；解码长度不是 8 时失败 |
| MetadataKeys.intKey(id) | 4 字节 big-endian；解码长度不是 4 时失败 |
| MetadataKeys.stringKey(id) | UTF-8 |
| MetadataKeys.bytesKey(id) | 原始字节值 |
| MetadataKeys.of(id, codec) | 自定义编码类型 |
| Metadatas.empty() | 空 Metadata |
| Metadatas.wrap(encodedBytes) | 校验结构，包装原数组，不做防御性复制 |
| Metadatas.builder() | 构造新 Metadata |

MetadataKey.id() 返回数值 Key。不同 MetadataKey 实例只要 id 相同，就查询同一个 wire 条目；编码一致性由定义方负责，没有全局 Key 注册中心。

get 未命中返回 null，带 defaultValue 的重载在未命中时返回默认值。get 调用的 codec 出错时直接传播异常。bytes() 返回共享底层数组；bytesKey 的 get 为返回独立 Java byte[] 而提取对应范围，不是让所有 get 都复制整个 Metadata。

Builder 禁止 null value。重复 put 相同 id 会替换该 Builder 内原值；build 输出只有一个该 Key 的条目。build 重新组装编码数组，因此后续修改 Builder 不改变先前构造的 Metadata。

```java
var traceId = MetadataKeys.longKey(1024);
var locale = MetadataKeys.stringKey(1025);
var debugEnabled = MetadataKeys.booleanKey(1026);
var retryCount = MetadataKeys.intKey(1027);
Metadata metadata = Metadatas.builder()
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

MetadataBuilder 不保证并发安全。构建完成的 Metadata 在底层字节不被修改时可共享读取；自定义 MetadataCodec 是否支持并发由提供者保证。get 按需调用 codec，异常直接传播，不做通用重试或缓存。

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

Java FrameworkErrorCodes 暴露 public static final int 常量，对应 [Core 标准的错误码分配](OGBS-Core-1.0.md#4-frameworkerrorcode)。不得在 Java 文档复制维护第二套编号表。已定义常量值不可随意重排；新增编号先修订标准，再同步 Java 常量及消费方。

本次 RPC 首次实现采用最新讨论的 2001..2007 分配，替代未实现草案；RpcErrorCodes 只引用 FrameworkErrorCodes。errorCode 不再有高位包装，0 成功、1..9999 框架、10000..Integer.MAX_VALUE 业务，负数非法。旧 RPC 常量发生源码/编号变更，不能与旧草案混用；Runtime/Data 编号未变。

Network 当前使用 WriteStatus、NetworkException、ConnectionHandler.onException 表达状态和失败；game-core 的 Network 编号区间仅预留，不强制 Network 依赖 game-core 或为每次关闭分配错误码。

Java 方法签名或异常形式变更应说明源码兼容性；Metadata 字节格式和跨进程错误码含义变更必须先处理标准及 Wire Profile 的协议兼容性。

## 5. 源码与验证

- [Metadata](../../game-core/src/main/java/cn/managame/core/Metadata.java)、[MetadataKey](../../game-core/src/main/java/cn/managame/core/MetadataKey.java)：公开数据访问接口。
- [Metadatas](../../game-core/src/main/java/cn/managame/core/Metadatas.java)、[MetadataKeys](../../game-core/src/main/java/cn/managame/core/MetadataKeys.java)：结构校验、Builder、内置 codec。
- [FrameworkErrorCodes](../../game-core/src/main/java/cn/managame/core/FrameworkErrorCodes.java)：共享编号绑定。
- [MetadataTest](../../game-core/src/test/java/cn/managame/core/MetadataTest.java)：共享数组、惰性解码、结构损坏、边界、Builder 快照及编码向量。

运行 mvn -pl game-core -am test；影响模块依赖或跨组件接入时运行 mvn clean verify。测试覆盖不表示已经完成跨语言互操作或生产性能验证。
