[English](README.en.md) | 中文

# game-gateway

`game-gateway` 是游戏客户端的边缘接入层：承接 TCP / WebSocket 长连接，执行连接保护、会话限流和登录门禁，通过服务发现维护后端连接池，并借助 `game-rpc` 把不透明业务包体双向转发。网关只读取帧头，不反序列化业务对象。

## 架构

```text
数据面：TCP / WebSocket → 编解码 → 接入保护 → 会话/路由 → game-rpc → 游戏服
控制面：game-registry.watchService ───────────────→ 后端目录与 RPC 连接池
```

`game-registry` 是唯一的服务发现来源，负责当前快照、增量事件和订阅恢复；网关只消费 `ADDED / UPDATED / REMOVED` 事件，不实现轮询或另一套发现机制。注册事件不进入数据热路径，后端目录使用不可变哈希环快照，转发读取无需加锁。

## 核心行为

- TCP 与 WebSocket 使用同一二进制帧：`bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)`。
- 单包默认上限 1 MiB；负长度、超长包或非法消息类型会关闭连接。
- 接入保护顺序为 IP 聚合保护、会话令牌桶、登录门禁；三项检查共用一个会话状态，登录命令在未认证时仍可通过。
- 会话先按 `sessionId` 路由，登录成功并绑定 `roleId` 后改按角色路由；同一角色重复登录会先写踢线包再关闭旧连接。
- 先按命令映射到登录服、场景服、聊天服等逻辑服务类型，再在该类型的实例中选择节点。
- 每种后端服务直接执行 `ServiceRegistry.watchService`。健康实例进入各自的一致性哈希环并建立 RPC 连接，更新或下线时同步摘除。
- 会话对每种服务分别保持粘滞；某类型的实例失效只重选该类型，不影响会话绑定的其他后端服务。

## 转发约定

外网包的 `command` 和原始 `body` 映射到 `RpcRequest`。`busType/busId` 标识 session 或 role，`routeKey` 用于后端串行路由，`MetadataKeys.GW_SEQ` 回传客户端序号，`GW_FLAGS` 透传压缩/加密标志，登录请求额外携带 `GW_CLIENT_IP`。后端下行使用相同 envelope，`GW_CODE` 表示业务码；成功登录响应可在 `routeKey` 中回带角色 ID。

如果目标 RPC 实例没有活动连接或本地出站缓冲触发背压，网关会清除该服务的粘滞绑定，并立即向客户端返回 `SERVER_BUSY`，不会静默等待超时。

## 配置

配置优先级为命令行 `--key=value`、JVM `-Dkey=value`、`GAME_*` 环境变量、`config/application.properties`、默认值。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `game.gateway.tcp.port` | `9000` | TCP 端口 |
| `game.gateway.ws.port` | `9001` | WebSocket 端口；`0` 关闭 |
| `game.gateway.ws.path` | `/ws` | WebSocket 路径 |
| `game.gateway.reader.idle.seconds` | `180` | 读空闲断连秒数；`0` 关闭 |
| `game.gateway.backend.service` | `game-dev` | 后端服务名 |
| `game.gateway.backend.routes` | 空 | 命令到服务的映射，如 `1000=auth-service,2000-2999=chat-service`；未匹配命令使用默认后端 |
| `game.gateway.backend.connections` | `4` | 每实例 RPC 连接数 |
| `game.gateway.login.command` | `1000` | 登录命令 |
| `game.gateway.rate.pps` / `.burst` | `50` / `100` | 单会话限流 |
| `game.gateway.ddos.max-connections-per-ip` | `100` | 单 IP 最大连接数 |
| `game.gateway.ddos.pps-per-ip` / `.burst-per-ip` | `500` / `1000` | 单 IP 聚合限流 |
| `game.registry.type` / `.endpoints` | `memory` / `local` | 注册中心 |

## 构建与启动

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-gateway -am test
java -cp <classpath> cn.managame.gateway.bootstrap.Gateway --game.gateway.tcp.port=9000
```

生产环境应在运行时加入对应的注册中心 provider（Nacos 或 Etcd），并让后端游戏服实现上述 RPC envelope。

也可以在装配代码中实现 `BackendServiceResolver`，根据会话状态、区服或协议头动态选择服务类型，而不限于静态命令表。
