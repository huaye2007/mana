[English](README.md) | 中文

# managame-example

基于纯 Spring（`spring-context`）将 `managame-network`、`managame-runtime`、`managame-rpc`、`managame-data-mysql` 与 `managame-ai` 装配在一起的游戏服务器参考示例。

## 进程入口

- [WorldServerApp](src/main/java/cn/managame/example/WorldServerApp.java)：纯净世界服进程，只装配 `WORLD` 域、`RpcNode` 与 `WorldHandle`，不引入客户端网关或玩家数据库。
  ```bash
  -Drpc.node=1 -Drpc.port=7101
  ```
- [GameServerApp](src/main/java/cn/managame/example/GameServerApp.java)：游戏服进程，显式装配 `DataConfig`、`GameServerConfig`、`RpcConfig`、`AiConfig`、登录/进入世界处理器与 AI 工具处理器（`PlayerAiTools`）。Spring 自动按依赖反序优雅停服（`NetworkServer / RpcNode / AiToolGateway -> AiClient / AiEventPublisher -> GameRuntime -> GameData -> DataSource`）。
  ```bash
  -Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7001 -Dmysql.user=root -Dmysql.password=...
  ```
- [ClientMain](src/main/java/cn/managame/example/client/ClientMain.java)：示例 TCP 客户端，连接游戏服完成登录并经跨服 RPC 进入世界。

## AI 三平面接入（`cn.managame.example.ai`）

- [AiConfig](src/main/java/cn/managame/example/ai/AiConfig.java)：统一装配 `AiEventPublisher`（批量事件发布）、`AiClient`（`decision` 与 `chat` 隔离通道）、`GameplayAi` 与 `AiToolGateway`。
- [GameplayAi](src/main/java/cn/managame/example/ai/GameplayAi.java)：在业务路由中上报登录/购买事件、在 `decision` 通道请求装备推荐与 NPC 决策、在 `chat` 通道流式拉取 NPC 对话。
- [PlayerAiTools](src/main/java/cn/managame/example/ai/PlayerAiTools.java) 与 [AiToolGateway](src/main/java/cn/managame/example/ai/AiToolGateway.java)：演示同一处理器内按受众隔离（`ops` / `player`）工具目录、复用 `UserRepository` 走正常业务路由、64 位雪花 ID 字符串编码以及高危工具强制进入审批提案入口。

## 运行测试

先从仓库根目录安装框架组件，再在本目录执行：

```bash
mvn -o test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerIntegrationTest` 会在本机回环连接上同时启动 `WorldServerApp` 与 `GameServerApp`（使用 H2 MySQL 模式），端到端验证客户端登录、数据落库、跨服 RPC、AI 事件上报及受众隔离工具调用。
