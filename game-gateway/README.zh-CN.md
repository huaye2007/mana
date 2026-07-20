[English](README.md) | 中文

# game-gateway

`game-gateway` 是游戏客户端的边缘接入层：承接 TCP / WebSocket 长连接，执行连接保护、会话限流和登录门禁，通过服务发现维护后端连接池，并借助 `game-rpc` 把不透明业务包体双向转发。网关只读取帧头，不反序列化业务对象。

## 架构

```text
数据面：TCP / WebSocket → 编解码 → 过滤器 → 会话/路由 → game-rpc → 游戏服
控制面：game-registry.watchService ───────────────→ 后端目录与 RPC 连接池
```

`game-registry` 是唯一的服务发现来源，负责当前快照、增量事件和订阅恢复；网关只消费 `ADDED / UPDATED / REMOVED` 事件，不实现轮询或另一套发现机制。注册事件不进入数据热路径，后端目录使用不可变哈希环快照，转发读取无需加锁。

## 核心行为

- TCP 与 WebSocket 使用同一二进制帧：`bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)`。
- 单包默认上限 1 MiB；负长度、超长包或非法消息类型会关闭连接。
- 默认过滤器依次为 IP 聚合保护、会话限流、登录门禁；过滤器存放在固定数组中，热路径无动态查找和集合分配。
- 会话先按 `sessionId` 路由，登录成功并绑定 `roleId` 后改按角色路由；同一角色重复登录会先写踢线包再关闭旧连接。
- 先按命令映射到登录服、场景服、聊天服等逻辑服务类型，再在该类型的实例中选择节点。
- 每种后端服务直接执行 `ServiceRegistry.watchService`。健康实例进入各自的一致性哈希环并建立 RPC 连接，更新或下线时同步摘除。
- 会话对每种服务分别保持粘滞；某类型的实例失效只重选该类型，不影响会话绑定的其他后端服务。

## 扩展过滤器

实现 `GatewayFilter` 即可增加黑名单、维护模式、协议版本或区域校验。默认过滤器后可以直接追加业务过滤器：

```java
GatewayFilter maintenance = new GatewayFilter() {
    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        return maintenanceEnabled ? GatewayErrorCode.GATEWAY_ERROR : GatewayErrorCode.OK;
    }
};

Gateway gateway = Gateway.create(config,
        Gateway.defaultFilters(config, maintenance));
```

网络处理器直接按顺序短路执行固定过滤器数组，并在连接拒绝时逆序清理已接受的过滤器，没有额外的 Guard 或 Chain 对象。限流同样是 `GatewayFilter`；内置 IP/会话过滤器使用包内令牌桶，自定义滑动窗口、分布式限流等实现直接读取 session 和 packet 上下文，不再经过单独的限流接口。过滤器运行在网络 I/O 线程，扩展实现不得执行阻塞式远程调用。

## 转发约定

外网包的 `command` 和原始 `body` 映射到 `RpcRequest`。`busType/busId` 标识 session 或 role，`routeKey` 用于后端串行路由，`MetadataKeys.GW_SEQ` 回传客户端序号，`GW_FLAGS` 透传压缩/加密标志，登录请求额外携带 `GW_CLIENT_IP`。后端下行使用相同 envelope，`GW_CODE` 表示业务码；成功登录响应可在 `routeKey` 中回带角色 ID。

如果目标 RPC 实例没有活动连接或本地出站缓冲触发背压，网关会清除该服务的粘滞绑定，并立即向客户端返回 `SERVER_BUSY`，不会静默等待超时。

## 配置

所有配置都有默认值，不需要填写完整参数表。开发环境可以零配置启动；生产环境通常只需指定后端服务、注册中心和通告地址：

```properties
game.gateway.backend.service=scene-service
game.registry.type=nacos
game.registry.endpoints=127.0.0.1:8848
game.gateway.advertise-address=10.0.0.12
```

最常用的配置只有以下几项：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `game.gateway.backend.service` | `game-dev` | 默认后端服务 |
| `game.registry.type` / `.endpoints` | `memory` / `local` | 注册中心 |
| `game.gateway.advertise-address` | `127.0.0.1` | 注册到服务发现的地址 |
| `game.gateway.tcp.port` | `9000` | TCP 端口 |
| `game.gateway.ws.port` | `9001` | WebSocket 端口；`0` 关闭 |

路由表、连接数、登录命令、空闲时间和限流参数属于高级调优项，继续支持原有配置键，但不配置时直接使用安全默认值。Java 装配也无需长构造器，可使用 `new GatewayConfig()` 或 `new GatewayConfig("scene-service")`；内部配置按 `Transport / Identity / Backend / Limits / Registry` 分组。

配置优先级仍为命令行 `--key=value`、JVM `-Dkey=value`、`GAME_*` 环境变量、`config/application.properties`、默认值。

## 构建与启动

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-gateway -am test
java -cp <classpath> cn.managame.gateway.bootstrap.Gateway --game.gateway.tcp.port=9000
```

生产环境应在运行时加入对应的注册中心 provider（Nacos 或 Etcd），并让后端游戏服实现上述 RPC envelope。

也可以在装配代码中实现 `BackendServiceResolver`，根据会话状态、区服或协议头动态选择服务类型，而不限于静态命令表。
