English | [中文](README.zh-CN.md)

# managame-example

Reference game server application assembled with plain Spring (`spring-context`) over `managame-network`, `managame-runtime`, `managame-rpc`, `managame-ai` and `managame-data-mysql`.

## Processes

- [WorldServerApp](src/main/java/cn/managame/example/WorldServerApp.java): standalone world server process hosting only the `WORLD` domain, `RpcNode` and `WorldHandle` (no MySQL or client gateway).
  ```
  -Drpc.node=1 -Drpc.port=7101
  ```
- [GameServerApp](src/main/java/cn/managame/example/GameServerApp.java): game server process wiring `DataConfig` (`UserRepository`, `PlayerAiStateRepository`, `AiAuditLogRepository`), `GameServerConfig`, `RpcConfig`, `AiConfig` (`AiClient` with `X-Game-Server-Id`, `@McpTool(routeKey = ...)` endpoint with independent NPC/GM Bearer token rotation, `GameTask.refused` handling and write-tool audit logging) and login/world/AI services (`NpcAiService` with persisted dialogue epochs, per-player quota/cooldown, disconnect stream cancellation, sensitive-word filtering and degrade switch). Lifecycle is ordered via `SmartLifecycle` and `@DependsOn` (`McpEndpoint.stop -> NetworkServer / RpcNode -> AiClient / McpTools -> GameRuntime -> GameData -> DataSource`).
  ```
  -Drpc.node=2 -Drpc.peers=1=127.0.0.1:7101 -Dgame.port=7001 -Dai.mcp.port=8082 -Dai.service.url=http://127.0.0.1:8081/api/ai -Dmysql.user=root -Dmysql.password=...
  ```
- [ClientMain](src/main/java/cn/managame/example/client/ClientMain.java): sample TCP client that connects to the game server, logs in, and enters the world via RPC.

## Run tests

Install the framework from the repository root first, then run:

```bash
mvn -o install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

- `GameServerIntegrationTest` boots both `WorldServerApp` and `GameServerApp` (with H2 in MySQL mode) over real loopback TCP/RPC sockets and verifies login, persistence and cross-process RPC end to end.
- `AiRuntimeIntegrationExampleTest` verifies `GameRuntime` + `managame-ai` wiring (`@McpTool(routeKey = ...)`, `GameTask.refused`, `opId` idempotency, streaming content filter and decision fallback); pair with [managame-example-ai](../managame-example-ai/README.md) to run the full multi-server lifecycle suite against a real Spring Boot + Spring AI external service.
