English | [中文](README.zh-CN.md)

# managame-example

Reference game server application assembled with plain Spring (`spring-context`) over `managame-network`, `managame-runtime`, `managame-rpc`, `managame-data-mysql` and `managame-ai`.

## Processes

- [WorldServerApp](src/main/java/cn/managame/example/WorldServerApp.java): standalone world server process hosting only the `WORLD` domain, `RpcNode` and `WorldHandle` (no MySQL or client gateway).
  ```bash
  -Drpc.node=1 -Drpc.port=7101
  ```
- [GameServerApp](src/main/java/cn/managame/example/GameServerApp.java): game server process wiring `DataConfig`, `GameServerConfig`, `RpcConfig`, `AiConfig`, login/world-entry handlers and audience-scoped AI tools (`PlayerAiTools`). Spring closes beans in reverse dependency order (`NetworkServer / RpcNode / AiToolGateway -> AiClient / AiEventPublisher -> GameRuntime -> GameData -> DataSource`).
  ```bash
  -Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7001 -Dmysql.user=root -Dmysql.password=...
  ```
- [ClientMain](src/main/java/cn/managame/example/client/ClientMain.java): sample TCP client that connects to the game server, logs in, and enters the world via RPC.

## AI integration (`cn.managame.example.ai`)

- [AiConfig](src/main/java/cn/managame/example/ai/AiConfig.java): configures `AiEventPublisher` (batched game events), `AiClient` (isolated `decision` and `chat` services), `GameplayAi` and `AiToolGateway`.
- [GameplayAi](src/main/java/cn/managame/example/ai/GameplayAi.java): called on game routes to emit login/purchase events, request equipment/NPC decisions from the `decision` service, and stream dialogue from the `chat` service.
- [PlayerAiTools](src/main/java/cn/managame/example/ai/PlayerAiTools.java) & [AiToolGateway](src/main/java/cn/managame/example/ai/AiToolGateway.java): demonstrates audience-isolated tools (`ops` vs `player`) backed by `UserRepository` on normal `GameRuntime` routes, with 64-bit string-long encoding and mandatory approval routing for high-risk ops tools.

## Run tests

Install the framework from the repository root first, then run:

```bash
mvn -o test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerIntegrationTest` boots both `WorldServerApp` and `GameServerApp` (with H2 in MySQL mode) over real loopback TCP/RPC sockets and verifies login, persistence, cross-process RPC, AI event publishing and audience-isolated tool execution end to end.
