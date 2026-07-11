[中文](README.md) | English

# game-gateway

`game-gateway` is the edge entry point for game clients. It accepts long-lived TCP and WebSocket connections, applies connection protection, per-session rate limiting and a login gate, maintains backend pools through service discovery, and forwards opaque business payloads bidirectionally over `game-rpc`. It reads packet headers but never deserializes business objects.

## Core behavior

- TCP and WebSocket share one binary frame: `bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)`.
- A packet is limited to 1 MiB by default. Negative lengths, oversized frames, and unexpected message types close the connection.
- Filters run in this order: aggregate IP protection, session token bucket, login gate. The login command remains available before authentication.
- Sessions route by `sessionId` first and by `roleId` after a successful login binds the role. A duplicate role login receives a kick packet before the old connection closes.
- Backend instances come from `ServiceRegistry.watchService`. Healthy instances enter the consistent-hash ring and RPC pool; updates and removals are reconciled in both.
- A session remains sticky to its selected backend and is reassigned only when that instance becomes unavailable.

## Forwarding contract

The external packet's `command` and raw `body` map to an `RpcRequest`. `busType/busId` identifies a session or role, `routeKey` drives backend serialization, `MetadataKeys.GW_SEQ` carries the client sequence, `GW_FLAGS` preserves compression/encryption flags, and login requests also carry `GW_CLIENT_IP`. Backend downlinks use the same envelope and `GW_CODE` for the business status. A successful login response may return the role ID in `routeKey`.

## Configuration

Precedence is command-line `--key=value`, JVM `-Dkey=value`, `GAME_*` environment variables, `config/application.properties`, then defaults.

| Property | Default | Purpose |
| --- | --- | --- |
| `game.gateway.tcp.port` | `9000` | TCP port |
| `game.gateway.ws.port` | `9001` | WebSocket port; `0` disables it |
| `game.gateway.ws.path` | `/ws` | WebSocket path |
| `game.gateway.reader.idle.seconds` | `180` | Read-idle disconnect; `0` disables it |
| `game.gateway.backend.service` | `game-dev` | Backend service name |
| `game.gateway.backend.connections` | `4` | RPC connections per instance |
| `game.gateway.login.command` | `1000` | Login command |
| `game.gateway.rate.pps` / `.burst` | `50` / `100` | Per-session rate limit |
| `game.gateway.ddos.max-connections-per-ip` | `100` | Maximum connections per IP |
| `game.gateway.ddos.pps-per-ip` / `.burst-per-ip` | `500` / `1000` | Aggregate per-IP rate limit |
| `game.registry.type` / `.endpoints` | `memory` / `local` | Registry provider |

## Build and run

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-gateway -am test
java -cp <classpath> cn.managame.gateway.bootstrap.Gateway --game.gateway.tcp.port=9000
```

Production deployments should add the selected registry provider (Nacos or Etcd) at runtime and implement this RPC envelope in backend game servers.
