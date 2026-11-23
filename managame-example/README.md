English | [中文](README.zh-CN.md)

# managame-example

Reference game server application assembled with plain Spring (`spring-context`) over `managame-network`, `managame-runtime`, `managame-rpc` and `managame-data-mysql`.

## Processes

- [WorldServerApp](src/main/java/cn/managame/example/WorldServerApp.java): standalone world server process hosting only the `WORLD` domain, `RpcNode` and `WorldHandle` (no MySQL or client gateway).
  ```
  -Drpc.node=1 -Drpc.port=7101
  ```
- [GameServerApp](src/main/java/cn/managame/example/GameServerApp.java): game server process wiring `DataConfig`, `GameServerConfig`, `RpcConfig` and login/world-entry handlers. Spring closes beans in reverse dependency order (`NetworkServer / RpcNode -> GameRuntime -> GameData -> DataSource`).
  ```
  -Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7001 -Dmysql.user=root -Dmysql.password=...
  ```
- [ClientMain](src/main/java/cn/managame/example/client/ClientMain.java): sample TCP client that connects to the game server, logs in, and enters the world via RPC.

## Run tests

Install the framework from the repository root first, then run:

```bash
mvn -o test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerIntegrationTest` boots both `WorldServerApp` and `GameServerApp` (with H2 in MySQL mode) over real loopback TCP/RPC sockets and verifies login, persistence and cross-process RPC end to end.
