# Mana

English | [简体中文](README.zh-CN.md)

Java components for game server networking and RPC, built with JDK 25 and Netty 4.2.15.Final.

## Projects

| Project | Description | Documentation |
|---|---|---|
| game-network | TCP, WebSocket, secure WebSocket, and HTTP servers; TCP and WebSocket clients. | [English](game-network/README.md) · [简体中文](game-network/README.zh-CN.md) |
| game-rpc | RPC over game-network, with peer connections, calls, notifications, explicit replies, timeouts, and metadata. | [English](game-rpc/README.md) · [简体中文](game-rpc/README.zh-CN.md) |

`game-network` contains the JDK-only `game-network-api` module and the `game-network-netty` implementation. `game-rpc` contains `game-rpc-core` and the `game-rpc-netty` transport integration.

## Build

Use JDK 25 and Maven 3.9. From the repository root:

~~~sh
mvn verify
~~~

See each project's README for usage examples, configuration, and additional validation commands.

## Documentation languages

Each project's default `README.md` is in English. The corresponding `README.zh-CN.md` provides the Simplified Chinese version. Keep both versions in sync when updating project documentation.
