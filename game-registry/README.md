[English](README.en.md) | 中文

# game-registry

`game-registry` 提供轻量、统一的服务注册与发现能力，目前支持 memory、Nacos 和 Etcd。

## 模块

- `game-registry-core`：`ServiceRegistry` API、不可变服务实例模型、SPI 工厂。
- `game-registry-memory`：同一 JVM 内按 `endpoints` 隔离和共享的注册中心，适合开发与测试。
- `game-registry-nacos`：基于 Nacos Naming Service 的临时实例注册、查询和订阅。
- `game-registry-etcd`：基于 lease 和前缀 watch 的 Etcd 实现。

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

Etcd 使用 `.type("etcd")`，`endpoints` 支持逗号分隔的地址。可选属性包括 `prefix`（默认 `/mana/services`）、`leaseTtlSeconds`（默认 10）、`operationTimeoutMillis`（默认 5000）、`username` 和 `password`。注册实例绑定到客户端 lease，关闭 registry 时会撤销 lease 并自动删除实例。keepalive 失败后客户端会重新申请 lease 并恢复本机注册；watch 失败后会重新拉取快照、从新 revision 订阅，并补发期间漏掉的实例差异。
