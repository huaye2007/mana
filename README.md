English | [中文](README.zh-CN.md)

# managame

A game server framework on Java 25 and Netty 4.2. Every piece of business logic (player requests, events, timers,
HTTP calls and requests from other servers) runs through one entry, and tasks with the same domain and routing
key run one at a time in order.

| Project | What it is |
| --- | --- |
| [managame-common](managame-common/README.md) | `Metadata`, `MetadataKey` and the framework's error codes |
| [managame-network](managame-network/README.md) | TCP / TLS / ws / wss servers, an HTTP/1.1 server and a reconnecting client, with any Netty handler |
| [managame-runtime](managame-runtime/README.md) | `GameRuntime`, routing per domain, annotated handlers (`Dispatcher`, `EventBus`, `HttpRoutes`), interceptors and timers |
| [managame-rpc](managame-rpc/README.md) | Calls between server processes: `RpcNode` and `RpcPeer` |

## Build

Each project builds on its own; install them in dependency order:

```bash
cd managame-common && mvn install
cd ../managame-network && mvn install
cd ../managame-runtime && mvn install
cd ../managame-rpc && mvn install
```

Tests open real loopback connections. Conventions for contributors and coding agents are in [AGENTS.md](AGENTS.md).
