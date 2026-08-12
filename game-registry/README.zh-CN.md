[English](README.md) | 中文

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

## 监听器约定

`ServiceInstanceListener` 由 provider **同步**回调，回调线程取决于 provider 和事件来源：

| provider | 初始快照 | 增量事件 |
|---|---|---|
| memory | 调用 `watchService` 的线程 | 触发变更的那个 `register` / `deregister` 调用方线程 |
| nacos | 调用 `watchService` 的线程 | Nacos 客户端通知线程 |
| etcd | 调用 `watchService` 的线程 | jetcd 的 gRPC 回调线程 / 内部恢复线程 |

因此监听器**必须快速返回**：

- 不要在回调里做阻塞 IO、等锁或长耗时计算，重活自己丢到别的线程；
- 不要在回调里反过来调用同一个 registry 的方法，可能造成重入或死锁；
- 回调抛出的异常由 provider 记录日志后丢弃，不会重试，也不会传播给调用方。

事件本身也不提供跨 provider 的统一保证：**重复注册是否产生事件、`getInstances` 的返回顺序，都由具体 provider 决定**，不要依赖。另外 watch 断开后的重连是后台静默进行的，接口上没有错误通道——监听器收不到事件既可能是"当前没有实例"，也可能是"底层正在重连"，需要区分的话请自行结合日志或额外的健康检查。

## 服务实例

`ServiceInstance` 的身份由 `getKey()` 决定：设置了 `id` 就用 `id`，否则回落到 `address:port`。

**建议始终显式设置一个稳定的 `id`。**不设 `id` 时换地址等于换身份：新地址会被当成一个新实例注册，旧地址的那条记录要等到 provider 侧过期（etcd 是 lease 到期）才消失。

## Provider 配置

`RegistryConfig.properties(...)` 里**无法识别的键会被静默忽略**（比如把 `leaseTtlSeconds` 写成 `leaseTtlSecond` 会直接落回默认值），请对照下表核对拼写。

Nacos 使用 `.type("nacos")` 和 Nacos 地址作为 `endpoints`。本模块只消费 `group`（服务分组，默认 `DEFAULT_GROUP`），**其余属性会原样透传给 Nacos 客户端**，可用键以 Nacos 官方文档为准。注意 `getInstances` 是一次性查询，不会建立订阅；只有 `watchService` 才会订阅。

Etcd 使用 `.type("etcd")`，`endpoints` 支持逗号分隔的地址。本模块消费的属性只有这些：

| 属性 | 默认值 | 说明 |
|---|---|---|
| `prefix` | `/mana/services` | key 前缀 |
| `leaseTtlSeconds` | `10` | lease TTL，同时也是租约恢复的整体时限 |
| `operationTimeoutMillis` | `5000` | 单次 etcd 操作超时 |
| `username` / `password` | 无 | 认证凭据 |

注册实例绑定到客户端 lease，关闭 registry 时会撤销 lease 并自动删除实例。keepalive 失败后客户端会重新申请 lease 并恢复本机注册；watch 失败后会重新拉取快照、从新 revision 订阅，并补发期间漏掉的实例差异。前缀下读不懂的数据（人工写入、或更高版本写入方产生的数据）会被跳过并记录 warn，不影响同前缀下其余实例的查询与订阅。
