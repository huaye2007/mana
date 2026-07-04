[中文](README.md) | English

# mana

`mana` is a collection of building blocks for game servers. The Java side is organized as a Maven multi-module reactor covering networking, internal RPC, serialization, service registry, configuration management, runtime task scheduling and JPA-style persistence; the gateway, router, host agent and a service registry library are provided as standalone Rust sidecars. Open source under the [Apache-2.0](LICENSE) license.

## Requirements

- **JDK 25 (hard requirement)**: `game-runtime` uses `ScopedValue`, finalized in JDK 25, for virtual-thread context propagation and cannot be downgraded; enforced at build time by maven-enforcer
- Maven 3.9+
- The Rust sidecar modules require the Rust toolchain (`cargo`)
- Builds on both Windows and Linux; UDS, epoll and some network integration tests need Linux or a working Netty selector environment
- Optional: Docker, for MySQL / MongoDB integration tests

Under PowerShell, quote Maven properties:

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

## Modules

### Java modules (Maven reactor)

| Module | Responsibility |
| --- | --- |
| `game-network` | Network foundation (core + Netty): TCP/WebSocket/HTTP transport, connection and session lifecycle, transport pipeline; delivers decoded messages to `INetworkHandler` (HTTP goes through `IHttpHandler`). Zero dependencies; no protocol routing or command dispatch (see [game-network/README.en.md](game-network/README.en.md)) |
| `game-rpc` | Internal RPC: protocol codec, handshake/heartbeat/reconnect, oneway/future/callback invocation, backpressure and in-flight limit protection, per-service broadcast (see [game-rpc/README.en.md](game-rpc/README.en.md)) |
| `game-serialization` | Serialization facade unifying JSON, Protobuf and Apache Fory (see [game-serialization/README.en.md](game-serialization/README.en.md)) |
| `game-registry` | Registry/discovery abstraction (`Registry` / `Discovery`), backed by ZooKeeper, Etcd, Nacos, Consul via SPI (see [game-registry/README.en.md](game-registry/README.en.md)) |
| `game-config` | Multi-source configuration loading (local file/classpath/command line/JVM/env vars/defaults/remote) with type-safe snapshots, change listeners and hot reload (see [game-config/README.en.md](game-config/README.en.md)) |
| `game-runtime` | Unified runtime: commands/events/timers/callbacks converge into tasks, hashed by routerKey to a fixed worker in the group for serial execution (see [game-runtime/README.en.md](game-runtime/README.en.md)) |
| `game-jpa` | Lightweight persistence framework with RDB, DocDB, caching, async batched writes, sharding, starter and demo (see [game-jpa/README.en.md](game-jpa/README.en.md)) |
| `game-dev` | Reference host / sample project: external GamePacket protocol + framework behaviors such as login, duplicate-login kick and idle kick; demonstrates the bridging of network/runtime/jpa/serialization (currently a single-process host, registry/config and the Rust data plane not yet integrated) |

### Rust sidecar modules

| Module | Responsibility |
| --- | --- |
| `game-gateway-rust` | Player gateway: carries massive client long connections; handles access, auth, sessions, rate limiting; forwards business payloads transparently to the backend |
| `game-router-rust` | Internal message forwarding between services: game servers register dynamically on connect; routers interconnect via service discovery and forward across nodes |
| `game-agent-rust` | Per-host local agent: local ingress routing-header handling and cross-agent backbone forwarding |
| `game-registry-rust` | Rust service registration and discovery library; each provider is a thin wrapper over the corresponding third-party SDK |

> The Rust sidecar modules are not yet included in this repository. The `game-gateway` / `game-router` / `game-agent` of early versions have been superseded by the Rust implementations above; `game-aoi` / `game-ecs` / `game-ai` / `game-match` / `game-idgen` are not yet part of the current repository.

## Build & Test

Full unit tests:

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

CI runs the same Java reactor test command on Linux and Windows; the Rust sidecar modules run `cargo fmt --check`, `cargo test --locked` and `cargo clippy -D warnings`.

JPA module only:

```powershell
mvn "-Dmaven.repo.local=.m2" -f game-jpa\pom.xml test
```

Registry integration tests: ZooKeeper uses an embedded curator-test server and is self-contained — it runs in the default build; Etcd / Nacos / Consul require the corresponding real services running locally and are off by default — enable them with the profiles in the `game-registry` POM (`registry-it-etcd`, `registry-it-nacos`, `registry-it-consul`, or `registry-it-all` for everything at once). The MySQL / MongoDB persistence integration tests use the `game-jpa` `integration-tests` profile (Testcontainers-based, requires Docker).

## Dependency Notes

- The MySQL JDBC driver (`mysql-connector-j`) is licensed GPLv2 (with the Universal FOSS Exception); `game-jpa-rdb-mysql` declares it `provided` and it is **not distributed transitively by this project**. When using the MySQL backend, add the driver dependency explicitly in your own application POM.
- All other third-party dependencies are Apache-2.0 / MIT / BSD-family licensed.

## Development Conventions

- Java package names uniformly use the `cn.managame` prefix; each module sub-packages by responsibility underneath (e.g. `cn.managame.jpa.*`, `cn.managame.runtime.*`).
- Tests uniformly use JUnit 5/Jupiter.
- The runtime hashes by routerKey to a fixed worker in the group, guaranteeing serial execution for the same routerKey (same player/room/guild) — business code needs no locks (see [game-runtime/README.en.md](game-runtime/README.en.md)).
- Module boundaries stay clean: core layers do not depend on concrete implementations — Netty, HTTP, MySQL, Mongo, registry/config providers live in separate implementation modules; `game-network` / `game-rpc` / `game-runtime` do not depend on each other, and bridging code lives in the host process.

## Common Entry Points

- Runtime (no unified assembly facade — use each component's singleton as needed): command registration `cn.managame.runtime.command.CommandRegistry` (dispatch bridged by the host: build `GameCommandTaskRunnable` → `ExecutorGroupRegistry.execute`), events `cn.managame.runtime.event.EventBus`, timers `cn.managame.runtime.timer.TimingWheel` / `CronTask`, executor group registration `cn.managame.runtime.executor.ExecutorGroupRegistry`
- Network: `cn.managame.network.server.NettyTcpServer` / `NettyWebSocketServer` / `NettyHttpServer` (implementing `INetworkServer`); the business side implements `INetworkHandler` / `IHttpHandler`
- RPC: `cn.managame.rpc.RpcClient`, `cn.managame.rpc.RpcServer`
- Registry: `cn.managame.registry.api.Registry` / `cn.managame.registry.api.Discovery`, factory `cn.managame.registry.factory.RegistryFactory`, starter `cn.managame.registry.starter.GameRegistryStarter`
- Config: `cn.managame.config.manager.GameConfigManager`, factory `cn.managame.config.factory.GameConfigFactory`
- Serialization: `cn.managame.serialization.SerializerManager`
- JPA starter: `cn.managame.jpa.starter.GameJpaBootstrap`

## License

This project is open source under the [Apache License 2.0](LICENSE).
