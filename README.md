[中文](README.zh-CN.md) | English

# mana

`mana` is a collection of building blocks for game servers, organized as a Maven multi-module reactor covering networking, internal RPC, serialization, service registry, configuration management, runtime task scheduling, ECS scene simulation and JPA-style persistence. Open source under the [Apache-2.0](LICENSE) license.

## Requirements

- **JDK 25 (hard requirement)**: `game-runtime` uses `ScopedValue`, finalized in JDK 25, for virtual-thread context propagation and cannot be downgraded; enforced at build time by maven-enforcer
- Maven 3.9+
- Builds on both Windows and Linux; UDS, epoll and some network integration tests need Linux or a working Netty selector environment
- Optional: Docker, for MySQL / MongoDB integration tests

Under PowerShell, quote Maven properties:

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

## Modules

| Module | Responsibility |
| --- | --- |
| `game-network` | Network transport: unified TCP/WebSocket servers, protocol-transparent HTTP/1/HTTP/2 semantics, connection lifecycle, and native Netty pipeline extension; sessions and connection mapping belong to the business layer (see [game-network/README.md](game-network/README.md)) |
| `game-rpc` | Unified entry artifact for internal RPC; implementation is layered into `game-rpc-core` and `game-rpc-netty`, with no dependency on `game-network` (see [game-rpc/README.md](game-rpc/README.md)) |
| `game-serialization` | Serialization facade unifying JSON, Protobuf and Apache Fory (see [game-serialization/README.md](game-serialization/README.md)) |
| `game-registry` | Unified service registry/discovery API with memory, Nacos, and Etcd SPI providers (see [game-registry/README.md](game-registry/README.md)) |
| `game-config` | Immutable configuration snapshots with local, Nacos, and Etcd backends, typed reads, and change listeners (see [game-config/README.md](game-config/README.md)) |
| `game-runtime` | Unified runtime: commands/events/timers/callbacks converge into tasks, hashed by routerKey to a fixed worker in the group for serial execution (see [game-runtime/README.md](game-runtime/README.md)) |
| `game-gateway` | TCP/WebSocket game edge gateway with admission protection, login gating, sticky routing, service discovery and bidirectional RPC tunneling (see [game-gateway/README.md](game-gateway/README.md)) |
| `game-jpa` | Lightweight persistence framework with RDB, DocDB, caching, async batched writes, sharding, starter and demo (see [game-jpa/README.md](game-jpa/README.md)) |
| `game-dev` | Reference host / sample project: external GamePacket protocol + framework behaviors such as login, duplicate-login kick and idle kick; demonstrates the bridging of network/runtime/jpa/serialization (currently a single-process host, registry/config not yet integrated) |

## Build & Test

Full unit tests:

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

CI runs the same reactor test command on Linux and Windows.

JPA module only:

```powershell
mvn "-Dmaven.repo.local=.m2" -f game-jpa\pom.xml test
```

The default registry tests cover memory behavior and the Nacos/Etcd adapters with mocked clients; they do not connect to external registry services. The MySQL / MongoDB persistence integration tests use the `game-jpa` `integration-tests` profile (Testcontainers-based, requires Docker).

## Dependency Notes

- The MySQL JDBC driver (`mysql-connector-j`) is licensed GPLv2 (with the Universal FOSS Exception); `game-jpa-rdb-mysql` declares it `provided` and it is **not distributed transitively by this project**. When using the MySQL backend, add the driver dependency explicitly in your own application POM.
- All other third-party dependencies are Apache-2.0 / MIT / BSD-family licensed.

## Development Conventions

- Java package names uniformly use the `cn.managame` prefix; each module sub-packages by responsibility underneath (e.g. `cn.managame.jpa.*`, `cn.managame.runtime.*`).
- Tests uniformly use JUnit 5/Jupiter.
- The runtime hashes by routerKey to a fixed worker in the group, guaranteeing serial execution for the same routerKey (same player/room/guild) — business code needs no locks (see [game-runtime/README.md](game-runtime/README.md)).
- Module boundaries stay clean: core layers do not depend on concrete implementations — Netty, HTTP, MySQL, Mongo, registry/config providers live in separate implementation modules; `game-network` / `game-rpc` / `game-runtime` do not depend on each other, and bridging code lives in the host process.

## Common Entry Points

- Runtime (no unified assembly facade — use each component's singleton as needed): command registration `cn.managame.runtime.command.CommandRegistry` (dispatch bridged by the host: build `GameCommandTaskRunnable` → `ExecutorGroupRegistry.execute`), events `cn.managame.runtime.event.EventBus`, timers `cn.managame.runtime.timer.TimingWheel` / `CronTask`, executor group registration `cn.managame.runtime.executor.ExecutorGroupRegistry`
- ECS: core world `cn.managame.ecs.World`, system pipeline `cn.managame.ecs.SystemPipeline`, dedicated-thread world loop `cn.managame.ecs.runtime.EcsWorldRunner`
- Network: create TCP, WebSocket, or custom servers through `NettyServer`, and protocol-transparent HTTP/1/HTTP/2 servers through `NettyHttpServer`
- RPC: `cn.managame.rpc.RpcClient`, `cn.managame.rpc.RpcServer`
- Registry: `cn.managame.registry.api.ServiceRegistry`, factory `cn.managame.registry.factory.RegistryFactory`
- Config: `cn.managame.config.ConfigCenter`, factory `cn.managame.config.ConfigFactory`
- Serialization: `cn.managame.serialization.SerializerManager`
- JPA starter: `cn.managame.jpa.starter.GameJpaBootstrap`

## License

This project is open source under the [Apache License 2.0](LICENSE).
