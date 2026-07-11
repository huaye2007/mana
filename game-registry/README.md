[English](README.en.md) | 中文

# game-registry

`game-registry` 提供轻量、统一的服务注册与发现能力，目前只支持 memory 和 Nacos。

## 模块

- `game-registry-core`：`ServiceRegistry` API、不可变服务实例模型、SPI 工厂。
- `game-registry-memory`：同一 JVM 内按 `endpoints` 隔离和共享的注册中心，适合开发与测试。
- `game-registry-nacos`：基于 Nacos Naming Service 的临时实例注册、查询和订阅。

## 使用

依赖 core，并选择至少一个运行时 provider：

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-registry-core</artifactId>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-registry-memory</artifactId>
    <scope>runtime</scope>
</dependency>
```

```java
ServiceRegistry registry = RegistryFactory.startRegistry(RegistryConfig.builder()
        .type("memory")
        .endpoints("local")
        .build());

registry.register(ServiceInstance.builder()
        .name("game-server")
        .id("game-1")
        .address("127.0.0.1")
        .port(9000)
        .build());

AutoCloseable watch = registry.watchService("game-server", event -> {
    // ADDED / UPDATED / REMOVED
});
```

watch 建立时会同步发送当前实例的 `ADDED` 快照，随后发送增量事件。关闭 registry 会停止订阅并注销由该客户端注册的实例。

Nacos 使用 `.type("nacos")` 和 Nacos 地址作为 `endpoints`。可通过 `RegistryConfig.properties(...)` 传入 Nacos 客户端属性；`group` 用于指定服务分组，默认 `DEFAULT_GROUP`。
