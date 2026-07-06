# game-gateway

游戏网关：客户端外网接入的统一入口。承接 TCP / WebSocket 长连接，做接入防护与登录 gate，
把外网帧透传到后端游戏服（经 game-rpc 内网转发），并把后端的回包/推送写回对应客户端。
网关不理解 body（不反序列化业务对象），只看帧头做过滤与路由，是一条按会话粘滞的多路复用管道。

## 架构

```
cn.managame.gateway
 ├── bootstrap   启动类 Gateway + 配置装载 GatewayConfig
 ├── network     网络层
 │    ├── tcp        GatewayTcpServer / 管线（读空闲 + 帧编解码）
 │    └── websocket  GatewayWebSocketServer / 管线（帧编解码插到派发器前）
 ├── codec       GatewayPacket 帧编解码 + BodyCodec（加密/压缩钩子）
 ├── session     GatewaySession / GatewaySessionManager（sessionId、userId 双索引，顶号）
 ├── router      Router 策略：ConsistentHashRouter / RoundRobinRouter + BackendRouterManager（粘滞）
 ├── rpc         PacketForwarder 上行 + GatewayRpcMessageHandler 下行，复用 game-rpc 连接池
 ├── filter      责任链 FilterChain：DdosFilter → RateLimitFilter → AuthFilter
 └── registry    BackendDiscovery：watch 后端服务，驱动连接池与路由表
```

## 帧格式

外网帧与 game-dev 的 `GamePacket` 完全一致，保证同一客户端既能直连游戏服也能走网关：

```
bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)
```

TCP 直接是该字节流；WebSocket 的二进制帧内承载同一格式。`flags` 预留加密/压缩位，
由 `BodyCodec` 处理（默认 `IDENTITY` 不变换）。

## 数据流

1. 客户端连接 → `GatewayNetworkHandler` 建 `GatewaySession` → 过滤链 `onConnect`（IP 防护）。
2. 入站帧 → 过滤链 `onPacket`（DDoS 速率 → 会话限流 → 登录 gate）→ 放行的交 `PacketForwarder`。
3. `PacketForwarder` 按会话粘滞选后端实例，封成 game-rpc **oneway** 帧转发（seq/sessionId 走 metadata）。
4. 后端处理后经同一连接 **oneway** 回下行，`GatewayRpcMessageHandler` 按 sessionId/userId 定位会话，
   还原成外网帧写回客户端。
5. **登录数据包校验通过之后**（后端回下行 `command=登录命令 && code=0`）翻转会话认证态，
   `AuthFilter` 此后放行其余命令；后端可在下行 metadata 带 `userId` 触发 userId→会话 绑定与顶号。

转发协议的 metadata key 见 [`GatewayRpcProtocol`](src/main/java/cn/managame/gateway/rpc/GatewayRpcProtocol.java)。

> 注意：走网关要求后端游戏服暴露一个 game-rpc `RpcServer`，按上述 envelope 收发转发帧
> （从 metadata 取 sessionId/seq，回包按 sessionId 定位、带回 seq/code）。当前 game-dev 直连客户端、
> 尚未内建该 RpcServer 端点，这部分后端改造独立于本模块。

## 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `game.gateway.tcp.port` | 9000 | 外网 TCP 端口 |
| `game.gateway.ws.port` | 9001 | 外网 WebSocket 端口，0 关闭 |
| `game.gateway.ws.path` | `/ws` | WebSocket 路径 |
| `game.gateway.reader.idle.seconds` | 180 | 读空闲踢连接阈值 |
| `game.gateway.backend.service` | `game-dev` | watch 的后端服务名 |
| `game.gateway.backend.connections` | 4 | 每后端实例的连接池大小 |
| `game.gateway.login.command` | 1000 | 登录命令（对齐 game-dev `@GameMethod(1000)`） |
| `game.gateway.rate.pps` / `.burst` | 50 / 100 | 会话级限流 |
| `game.gateway.ddos.max-conn-per-ip` | 100 | 单 IP 并发连接上限 |
| `game.gateway.ddos.pps-per-ip` / `.burst-per-ip` | 500 / 1000 | 单 IP 聚合包速率 |
| `game.registry.type` / `.endpoints` | `memory` / `local` | 注册中心（复用 game-dev 键） |

配置分层：命令行 `--key=value` > `-Dkey=value` > 环境变量 > `config/application.properties`。

## 运行

```bash
mvn -pl game-gateway -am -DskipTests install
java -cp ... cn.managame.gateway.bootstrap.Gateway --game.gateway.tcp.port=9000
```

启动次序：外网端口就绪 → watch 后端建连接池 → 注册自身；停机逆序收敛到单一 shutdown hook。
