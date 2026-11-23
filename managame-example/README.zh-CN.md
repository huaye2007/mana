[English](README.md) | 中文

# managame-example

基于纯 Spring（`spring-context`）将 `managame-network`、`managame-runtime`、`managame-rpc` 与 `managame-data-mysql` 装配在一起的游戏服务器参考示例。

## 进程入口

- [WorldServerApp](src/main/java/cn/managame/example/WorldServerApp.java)：纯净世界服进程，只装配 `WORLD` 域、`RpcNode` 与 `WorldHandle`，不引入客户端网关或玩家数据库。
  ```
  -Drpc.node=1 -Drpc.port=7101
  ```
- [GameServerApp](src/main/java/cn/managame/example/GameServerApp.java)：游戏服进程，显式装配 `DataConfig`、`GameServerConfig`、`RpcConfig` 与登录/进入世界处理器。Spring 自动按依赖反序优雅停服（`NetworkServer / RpcNode -> GameRuntime -> GameData -> DataSource`）。
  ```
  -Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7001 -Dmysql.user=root -Dmysql.password=...
  ```
- [ClientMain](src/main/java/cn/managame/example/client/ClientMain.java)：示例 TCP 客户端，连接游戏服完成登录并经跨服 RPC 进入世界。

## 运行测试

先从仓库根目录安装框架组件，再在本目录执行：

```bash
mvn -o test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerIntegrationTest` 会在本机回环连接上同时启动 `WorldServerApp` 与 `GameServerApp`（使用 H2 MySQL 模式），端到端验证客户端登录、数据落库及跨服 RPC。
