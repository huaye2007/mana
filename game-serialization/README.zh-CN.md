[English](README.md) | 中文

# game-serialization

`game-serialization` 为游戏服务提供一个小而高吞吐的序列化门面，支持 JSON、Protobuf 和 Apache Fory 二进制序列化。

## 支持的格式

- JSON：Jackson `JsonMapper`，缓存 `ObjectReader`/`ObjectWriter`，支持 Java time 类型，启用 Blackbird 字节码优化。
- Protobuf：Google Protobuf `MessageLite` 载荷，缓存生成的 parser。
- Apache Fory：基于 Apache Fory（原 Apache Fury）Java SDK 实现，使用 Java 原生序列化模式并复用 `ThreadSafeFory` 运行时。

## 使用方式

通过 `SerializerManager`（已接入网络/RPC 栈的进程级单例）获取共享、预注册的序列化器：

```java
SerializerManager serializers = SerializerManager.getInstance();

ISerializer json = serializers.requireSerializer(SerializationType.JSON);
byte[] jsonBytes = json.serialize(playerState);
PlayerState fromJson = json.deserialize(jsonBytes, PlayerState.class);

ISerializer protobuf = serializers.requireSerializer(SerializationType.PROTOBUF);
byte[] protoBytes = protobuf.serialize(loginRequest);          // value 必须是 protobuf MessageLite
LoginRequest fromProto = protobuf.deserialize(protoBytes, LoginRequest.class);
```

`requireSerializer` 在格式缺失时快速抛出异常，适合业务代码；网络解码等需要自行处理未知 wire id
的场景仍可使用兼容方法 `getISerializer(byte)`，该方法对未知 id 返回 `null`。

测试或需要隔离注册表的宿主可使用 `SerializerManager.createDefault()` 创建一套独立的内置序列化器，
避免修改进程级单例。注册和读取均可并发调用。

JSON 的泛型载荷可在序列化和反序列化两侧使用 `TypeReference` 或 `JavaType`。默认 mapper 会拒绝
合法 JSON 后附带额外 token 的载荷，避免把脏包静默当成正常消息；自定义 `ObjectMapper` 应在首次
收发前配置完成，此后不要再修改。

### Apache Fory

独立的 Fory 序列化器通过 builder 构建。类注册**默认开启**（安全模式），因此所有要序列化/反序列化的类型必须先注册：

```java
ForySerializer fory = ForySerializer.builder()
    .register(PlayerState.class, 100)   // 稳定 id → 更小的载荷
    .build();
byte[] foryBytes = fory.serialize(playerState);
PlayerState fromFory = fory.deserialize(foryBytes, PlayerState.class);
```

从 `SerializerManager.getInstance()` 获取的默认 Fory 同样要求类注册。在对外提供服务前，于启动期注册所有消息类型：

```java
if (serializers.getISerializer(SerializationType.FORY.typeId()) instanceof ForySerializer fory) {
    fory.register(LoginReq.class);
    fory.register(LoginRes.class);
}
```

## 性能建议

- 复用序列化器实例。按请求创建 Jackson mapper 或 Fory 运行时会成为延迟的主要来源。
- 输出目标为流或网络缓冲区时使用 `serialize(value, outputStream)`，Jackson 和 Protobuf 会直接写入，避免中间数组复制。
- 跨服务、schema 先行的契约和紧凑稳定的线上载荷优先用 Protobuf。
- JVM 到 JVM 的游戏状态快照、缓存值、需要 Java 原生对象图的内部消息优先用 Fory。
- 高频使用的 Fory 类用稳定 ID 注册，载荷更小、分发更快。

## 默认值与安全

- **类注册默认开启**（`requireClassRegistration(true)`）。不可信边界——例如 body 用 Fory 编码的外网游戏协议——只能反序列化已注册的类型，从而封死任意类反序列化的攻击面。启动期注册所有消息类型；未注册的类型会快速失败。只有完全可信的纯内网流量才可通过 `Builder.requireClassRegistration(false)` 关闭。
- **引用跟踪默认开启**（`refTracking(true)`），共享和循环对象图可以正确往返。只有确定载荷是树形结构、且需要额外吞吐时才关闭。
