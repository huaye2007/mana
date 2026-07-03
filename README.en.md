# game-parent

A collection of building blocks for game servers, licensed under [Apache-2.0](LICENSE).

The Java side is a Maven multi-module reactor covering networking (Netty TCP/WebSocket/HTTP), internal RPC (handshake/heartbeat/reconnect, backpressure and in-flight limits), a serialization facade (JSON / Protobuf / Fury), service registry abstractions (ZooKeeper / Etcd / Nacos / Consul via SPI), multi-source configuration with hot reload, a unified runtime that serializes commands/events/timers/callbacks per routing key onto fixed workers, and a lightweight JPA-style persistence framework with async batched writes for RDB (MySQL) and DocDB (MongoDB). The player-facing gateway, internal message router, host agent and a Rust registry library are provided as standalone Rust sidecars.

## Requirements

- **JDK 25** (hard requirement — `game-runtime` relies on `ScopedValue`, finalized in 25; enforced by maven-enforcer)
- Maven 3.9+
- Rust toolchain (`cargo`) for the Rust sidecar modules
- Optional: Docker for MySQL / MongoDB integration tests (Testcontainers)

## Build

```
mvn "-Dmaven.repo.local=.m2" test
```

Note: the MySQL JDBC driver (`mysql-connector-j`, GPLv2 with the Universal FOSS Exception) is declared `provided` and is **not** distributed by this project — add it to your own application when using the MySQL backend.

## Documentation

Project documentation is maintained in Chinese as the primary language. Please refer to [README.md](README.md) and the per-module Chinese READMEs; this file is kept as a brief English overview only.
