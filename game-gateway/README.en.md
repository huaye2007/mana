[中文](README.md) | English

# game-gateway

`game-gateway` is the edge entry point for game clients. It accepts long-lived TCP and WebSocket connections, applies connection protection, per-session rate limiting and a login gate, maintains backend pools through service discovery, and forwards opaque business payloads bidirectionally over `game-rpc`. It reads packet headers but never deserializes business objects.

## Architecture

```text
Data plane:    TCP / WebSocket → codec → admission guard → session/router → game-rpc → game server
Control plane: game-registry.watchService ───────────────────────────────→ backend directory/RPC pool
```

`game-registry` is the only service-discovery source. It owns current snapshots, incremental events, and subscription recovery. The gateway only consumes `ADDED / UPDATED / REMOVED` events and implements no polling or second discovery mechanism. Registry events stay off the data path; the backend directory publishes immutable hash-ring snapshots so forwarding reads remain lock-free.

## Core behavior

- TCP and WebSocket share one binary frame: `bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)`.
- A packet is limited to 1 MiB by default. Negative lengths, oversized frames, and unexpected message types close the connection.
- Admission checks run in this order: aggregate IP protection, session token bucket, login gate. All three share one session state, and the login command remains available before authentication.
- Sessions route by `sessionId` first and by `roleId` after a successful login binds the role. A duplicate role login receives a kick packet before the old connection closes.
- Commands first resolve to a logical service type such as authentication, scene, or chat, then select an instance within that type.
- Every service type subscribes directly through `ServiceRegistry.watchService` and has its own consistent-hash ring and RPC targets.
- Stickiness is tracked independently per service type. Losing one type's instance does not disturb the session's bindings to other backend services.

## Forwarding contract

The external packet's `command` and raw `body` map to an `RpcRequest`. `busType/busId` identifies a session or role, `routeKey` drives backend serialization, `MetadataKeys.GW_SEQ` carries the client sequence, `GW_FLAGS` preserves compression/encryption flags, and login requests also carry `GW_CLIENT_IP`. Backend downlinks use the same envelope and `GW_CODE` for the business status. A successful login response may return the role ID in `routeKey`.

If the selected RPC instance has no active connection or its local outbound buffer applies backpressure, the gateway clears that service's sticky binding and immediately replies `SERVER_BUSY` instead of silently waiting for a timeout.

## Configuration

Every setting has a default, so a complete property list is never required. Development can start with zero configuration. Production normally specifies only the backend service, registry, and advertised address:

```properties
game.gateway.backend.service=scene-service
game.registry.type=nacos
game.registry.endpoints=127.0.0.1:8848
game.gateway.advertise-address=10.0.0.12
```

The commonly used settings are limited to:

| Property | Default | Purpose |
| --- | --- | --- |
| `game.gateway.backend.service` | `game-dev` | Default backend service |
| `game.registry.type` / `.endpoints` | `memory` / `local` | Registry provider |
| `game.gateway.advertise-address` | `127.0.0.1` | Address published to discovery |
| `game.gateway.tcp.port` | `9000` | TCP port |
| `game.gateway.ws.port` | `9001` | WebSocket port; `0` disables it |

Routes, connection counts, login command, idle timeout, and rate limits are advanced tuning settings. Their existing property keys remain supported, but safe defaults apply when omitted. Java composition no longer needs a long constructor: use `new GatewayConfig()` or `new GatewayConfig("scene-service")`. Internally, settings are grouped as `Transport / Identity / Backend / Limits / Registry`.

Precedence remains command-line `--key=value`, JVM `-Dkey=value`, `GAME_*` environment variables, `config/application.properties`, then defaults.

## Build and run

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-gateway -am test
java -cp <classpath> cn.managame.gateway.bootstrap.Gateway --game.gateway.tcp.port=9000
```

Production deployments should add the selected registry provider (Nacos or Etcd) at runtime and implement this RPC envelope in backend game servers.

Composition code may also provide a custom `BackendServiceResolver` to select service types from session state, realm, or protocol headers instead of using the static command table.
