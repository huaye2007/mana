# game-registry

`game-registry` 是游戏服务框架的服务注册与发现模块，提供统一的 `Registry` / `Discovery` API，并通过 Java SPI 接入 Consul、Nacos、Etcd、Apache ZooKeeper 等注册中心实现。

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `game-registry-core` | API、异常、工厂、SPI 与共享支持工具 |
| `game-registry-zookeeper` | 基于 Apache Curator 的 Zookeeper 实现 |
| `game-registry-etcd` | 基于 jetcd 的 Etcd 实现 |
| `game-registry-nacos` | 基于 Nacos Java Client 的 Nacos 实现 |
| `game-registry-consul` | 基于 Consul Java Client 的 Consul 实现 |
| `game-registry-starter` | 轻量 starter，默认只引入 core；provider 按需显式引入 |

## 设计原则

- 业务侧只依赖 `Registry`、`Discovery`、`ServiceInstance` 等 core API。
- 注册中心实现通过 `RegistryProvider` SPI 挂载，新增实现不需要修改 core；`game-registry` 只做一致性包装，不替代或限制原生 SDK 的使用。
- `RegistryFactory` 使用字符串类型 key 选择 provider，同时保留 `RegistryType` 作为内置类型的兼容入口。
- `RegistryBundle` 同时暴露注册端和发现端；内置 provider 目前由同一个对象实现两种能力。
- 内置 provider 不包含 Eureka；需要 Eureka 或其他注册中心时，按 SPI 自定义扩展。

## 快速使用

### 1. 引入依赖

如果希望使用 starter 的 builder/factory 入口，引入 starter，并按目标注册中心显式引入 provider：

```xml
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-registry-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-registry-zookeeper</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

如果不使用 starter，也可以只引入 core 和对应 provider：

```xml
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-registry-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-registry-zookeeper</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 创建注册发现组件

```java
RegistryConfig config = new RegistryConfig();
config.setType(RegistryType.ZOOKEEPER);
config.setEndpoints("127.0.0.1:2181");
config.setBasePath("/services");

RegistryBundle bundle = RegistryFactory.create(config);
Registry registry = bundle.getRegistry();
Discovery discovery = bundle.getDiscovery();

bundle.start();
```

也可以使用 builder 创建配置：

```java
RegistryConfig config = RegistryConfig.builder()
        .type(RegistryType.ZOOKEEPER)
        .endpoints("127.0.0.1:2181")
        .basePath("/services")
        .property("zone", "shanghai-a")
        .build();
```

内置 provider 的注册端和发现端当前是同一个对象；第三方 provider 也可以返回两个对象。业务侧优先使用 `RegistryBundle#start()` 和 `RegistryBundle#close()` 统一管理生命周期。

也可以使用字符串类型，便于接入第三方 provider：

```java
config.setType("zookeeper");
```

启动前可以检查当前 classpath 上已加载的 provider：

```java
List<String> types = RegistryFactory.availableTypes();
boolean zookeeperAvailable = RegistryFactory.isAvailable("zookeeper");
```

### 3. 注册服务实例

```java
ServiceInstance instance = new ServiceInstance();
instance.setName("game-room");
instance.setId("room-1");
instance.setAddress("127.0.0.1");
instance.setPort(9001);
instance.setWeight(1.0D);
instance.setHealthy(true);
instance.setMetadata(Map.of("zone", "shanghai-a"));

registry.register(instance);
```

`registrationTimeUTC` 默认取实例创建时间；如果需要使用业务侧生成的时间戳，可以通过 setter 或 builder 显式覆盖。

`name`、`address` 和非空白 `id` 不能包含首尾空白；`name` 和非空白 `id` 也不能包含 `/`，这样同一套实例模型可以安全映射到 Zookeeper、Etcd 等路径型注册中心。`id` 为空或空白时，实例 key 会回落为 `address + ":" + port`，此时 `address` 也不能包含 `/`。

`metadata` 只接受非 `null` 的字符串 key/value；通过 `setMetadata(map)` 或 builder 的 `metadata(key, value)` 写入 null metadata 项会抛出配置异常。

也可以使用 builder 创建实例：

```java
ServiceInstance instance = ServiceInstance.builder()
        .name("game-room")
        .id("room-1")
        .address("127.0.0.1")
        .port(9001)
        .metadata("zone", "shanghai-a")
        .build();
```

### 4. 发现服务实例

```java
Collection<ServiceInstance> instances = discovery.getInstances("game-room");

for (ServiceInstance item : instances) {
    System.out.println(item.getAddress() + ":" + item.getPort());
}
```

### 5. 监听服务变化

```java
AutoCloseable handle = discovery.watchService("game-room", event -> {
    ServiceInstance changed = event.getInstance();
    System.out.println(event.getType() + " " + changed.getKey());
});

// 不再监听时关闭 handle
handle.close();
```

监听服务名变化：

```java
AutoCloseable handle = discovery.watchServiceNames(event -> {
    System.out.println(event.getType() + " " + event.getServiceName());
});
```

监听回调由底层注册中心的事件分发线程直接调用，初始快照在 `watchService(...)` / `watchServiceNames(...)` 调用栈内同步推送；`onEvent` 不得阻塞或抛异常，需要复杂处理时请投递到调用方自己的线程组。

## 内置类型

| 类型 | `RegistryType` | 字符串 key | endpoints 示例 |
| --- | --- | --- | --- |
| Zookeeper | `RegistryType.ZOOKEEPER` | `zookeeper` | `127.0.0.1:2181` |
| Etcd | `RegistryType.ETCD` | `etcd` | `http://127.0.0.1:2379` |
| Nacos | `RegistryType.NACOS` | `nacos` | `127.0.0.1:8848` |
| Consul | `RegistryType.CONSUL` | `consul` | `127.0.0.1:8500` |

内置 provider 的查询快照会做稳定排序：实例按 instance key 升序，服务名按字典序升序。

## 配置项

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `type` | 无 | 注册中心类型，支持内置枚举或字符串 key |
| `endpoints` | 无 | 注册中心地址，不同 provider 格式不同 |
| `basePath` | `/services` | 注册数据根路径，Zookeeper 和 Etcd 使用；会裁剪首尾空白并规范尾部斜杠 |
| `leaseTtlSeconds` | `10` | Etcd 租约 TTL |
| `properties` | 空 | provider 私有扩展配置，不同 provider 会透传或映射到对应原生客户端 |

通过 `properties(...)` 或 `property(key, value)` 设置扩展配置时，key/value 必须是字符串，且 `key` 不能为空白、`value` 不能为 `null`；非法配置会抛出配置异常。

常用 provider 私有配置：

| Provider | key | 说明 |
| --- | --- | --- |
| Zookeeper | `authScheme` / `auth` | Curator 认证信息，例如 digest 认证 |
| Zookeeper | `connectionTimeoutMillis` / `sessionTimeoutMillis` | Curator 连接和会话超时 |
| Zookeeper | `retryBaseSleepMillis` / `retryMaxRetries` | Curator 重试策略 |
| Etcd | `user` / `password` | Etcd 用户名密码 |
| Etcd | `namespace` / `authority` | jetcd namespace 和 authority |
| Etcd | `trustedCertPath` | TLS 信任证书路径 |
| Etcd | `clientCertPath` / `clientKeyPath` | 双向 TLS 客户端证书和私钥路径 |
| Etcd | `connectTimeoutMillis` / `keepaliveTimeMillis` / `keepaliveTimeoutMillis` | jetcd 连接和 keepalive 参数 |
| Etcd | `keepaliveWithoutCalls` | 是否允许无调用 keepalive，取值 `true` / `false` |
| Etcd | `retryDelayMillis` / `retryMaxDelayMillis` / `retryMaxAttempts` | jetcd 重试参数 |
| Etcd | `maxInboundMessageSize` | gRPC 最大入站消息大小 |
| Etcd | `operationTimeoutMillis` | Etcd grant/get/put/delete/revoke 等同步操作超时，默认 `5000` |
| Nacos | `serviceNameWatchIntervalMillis` | 服务名 watch 轮询间隔，默认 `5000` |
| Consul | `aclToken` / `token` / `consul.token` | Consul ACL token |
| Consul | `blockingQueryWaitSeconds` / `consul.blockingQueryWaitSeconds` | Consul blocking query 等待时间，默认 `55` |
| Consul | `heartbeatTtlSeconds` / `consul.heartbeatTtlSeconds` | 设置后为每个注册实例创建 TTL 健康检查并在虚拟线程上心跳；进程崩溃后心跳停止、TTL 过期、Consul 在 `1m` 后自动摘除。默认不开启 |
| Consul | `watchShutdownTimeoutMillis` / `consul.watchShutdownTimeoutMillis` | 关闭 Consul watch 线程池时的等待时间，默认 `2000` |
| Consul | `pingOnStart` | start 时是否调用 Consul status API 做连通性检查，默认 `true` |
| Consul | `tags` / `consul.tags` | 注册实例默认 tags，逗号分隔 |
| Consul | `checkHttp` / `checkTcp` / `checkInterval` / `checkTimeout` | 常用 Consul health check 配置；复杂场景建议直接使用原生 SDK 构建客户端或注册检查 |

布尔类配置只接受 `true` / `false`（大小写不敏感）；空白值按默认值处理，其他值会抛出配置异常。

Etcd provider 在 keepalive 断开后会重新创建租约并重注册当前进程已注册实例；watch 断开或完成后会以指数退避重新订阅，并在重订阅前拉取快照补齐变化。Etcd 同步远程操作默认 5 秒超时，可通过 `operationTimeoutMillis` 调整。Nacos provider 的 `group` / `namespace` 属于 registry 级配置，不建议在单个实例 metadata 中混用；如果 `__nacos.group` 或 `__nacos.namespace` 与 registry 配置不一致，注册和反注册会直接失败。Nacos 实例 watch 使用原生 `subscribe`；服务名 watch 通过 registry 内部单线程定时轮询 `getServicesOfServer` 并对比快照实现，间隔由 `serviceNameWatchIntervalMillis` 控制，关闭 watch handle 会取消轮询任务。Consul provider 接收单个 Consul Agent 地址，watch 使用 Consul SDK 的 blocking query 和 Consul index 变化通知，不做固定间隔 HTTP 轮询；每个 watch 和 TTL 心跳各跑在一个虚拟线程上，并发 watch 数只受 Consul 自身限制；同时保留 `ConsulRegistry(ConsulClient, Properties)` 构造入口，业务仍可直接使用 Consul 原生 SDK 处理更复杂的注册、健康检查和 ACL 场景。注意 Consul 与 Etcd/Zookeeper/Nacos 的"宕机自动摘除"语义不同：只有注册了健康检查的服务才会在实例崩溃后被 Consul 摘除。因此当配置了任意 `checkXxx` 但未显式指定 `checkDeregisterCriticalServiceAfter` 时，会默认设为 `1m`，让 critical 实例自动下线；若完全没有配置健康检查，注册时会打印一次 WARN 提示崩溃实例不会被自动摘除；需要自动下线可以设置 `heartbeatTtlSeconds`（registry 托管的 TTL 心跳检查，让 Consul 获得与其它后端一致的崩溃摘除语义），或配置 `checkHttp` / `checkTcp`，或改用原生 SDK 注册。

## 可观测性

内置实现提供轻量只读状态 getter，便于接入日志、健康检查或 metrics：

| 实现 | 观测方法 |
| --- | --- |
| `RegistryBundle` | `isStarted()`、`isClosed()` |
| `EtcdRegistry` | `isStarted()`、`isClosed()`、`getRegisteredInstanceCount()`、`getActiveWatchCount()`、`getCurrentLeaseId()`、`isReconnectScheduled()` |
| `NacosRegistry` | `isStarted()`、`isClosed()`、`getActiveWatchCount()`、`getServiceNameWatchIntervalMillis()` |
| `ConsulRegistry` | `isStarted()`、`isClosed()`、`getActiveWatchCount()`、`getWatchShutdownTimeoutMillis()`、`getHeartbeatTtlSeconds()` |
| `ZookeeperRegistry` | `isStarted()`、`isClosed()`、`getActiveWatchCount()` |

## 扩展 provider

新增注册中心实现时：

1. 实现 `Registry` 和 `Discovery`。
2. 实现 `RegistryProvider`，返回唯一字符串类型。
3. 在 `META-INF/services/com.github.huaye2007.mana.registry.spi.RegistryProvider` 写入 provider 类全名。
4. 业务侧通过 `config.setType("your-type")` 选择实现。

更多接口语义见 [API 契约文档](docs/API_CONTRACT.md)。上线前请按 [生产上线检查清单](docs/PRODUCTION_CHECKLIST.md) 完成目标注册中心验证。

## 测试

在仓库根目录运行：

```bash
mvn "-Dmaven.repo.local=.\.m2" -f game-registry\pom.xml test
```

说明：Zookeeper 测试会启动嵌入式服务，需要本机允许 loopback 网络连接。Etcd、Nacos 和 Consul 集成测试当前默认禁用，需要本地服务可用后再开启。

开启外部集成测试：

```bash
mvn "-Dmaven.repo.local=.\.m2" -f game-registry\pom.xml -Pregistry-it-zookeeper test
mvn "-Dmaven.repo.local=.\.m2" -f game-registry\pom.xml -Pregistry-it-etcd test
mvn "-Dmaven.repo.local=.\.m2" -f game-registry\pom.xml -Pregistry-it-nacos test
mvn "-Dmaven.repo.local=.\.m2" -f game-registry\pom.xml -Pregistry-it-consul test
```

这些 profile 只是把对应 `game.registry.integration.*` 系统属性设置为 `true`；仍然可以直接用 `-D` 覆盖，`registry-it-all` 会同时开启全部外部集成测试。

Etcd 集成测试覆盖注册发现、关闭时 lease revoke 清理；Nacos 集成测试覆盖注册发现、实例 watch 和服务名 watch；Consul 集成测试覆盖实例 watch 与服务名 watch。Etcd 默认连接 `http://127.0.0.1:2379`，Nacos 默认连接 `127.0.0.1:8848`，Consul 默认连接 `127.0.0.1:8500`。可以通过以下系统属性覆盖测试目标：

| 系统属性 | 默认值 | 说明 |
| --- | --- | --- |
| `game.registry.integration.zookeeper` | `false` | Zookeeper 集成测试开关 |
| `game.registry.integration.etcd.endpoints` | `http://127.0.0.1:2379` | Etcd 集成测试地址 |
| `game.registry.integration.etcd.basePath` | `/services` | Etcd 集成测试根路径 |
| `game.registry.integration.etcd.ttlSeconds` | `10` | Etcd 集成测试租约 TTL |
| `game.registry.integration.nacos.endpoints` | `127.0.0.1:8848` | Nacos 集成测试地址 |
| `game.registry.integration.nacos.group` | `DEFAULT_GROUP` | Nacos 集成测试分组 |
| `game.registry.integration.nacos.cluster` | `DEFAULT` | Nacos 集成测试集群 |
| `game.registry.integration.consul.endpoints` | `127.0.0.1:8500` | Consul 集成测试地址 |
