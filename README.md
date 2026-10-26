English | [中文](README.zh-CN.md)

# managame

Game server components on Java 25 and Netty 4.2. Each is a project of its own, so an application takes only the
ones it needs:

| Project | Depends on | What it is |
| --- | --- | --- |
| [managame-common](managame-common/README.md) | nothing | `Metadata`, `MetadataKey` and the shared error codes |
| [managame-network](managame-network/README.md) | Netty | TCP / TLS / ws / wss servers, an HTTP/1.1 server and a reconnecting client, with any Netty handler |
| [managame-runtime](managame-runtime/README.md) | common, network | Every piece of business logic through one entry, with tasks of one domain and routing key run one at a time: routing per domain, annotated handlers (`Dispatcher`, `EventBus`, `HttpRoutes`), interceptors and timers |
| [managame-rpc](managame-rpc/README.md) | common, network | Calls between server processes: `RpcNode`, `RpcPeer`, heartbeats and reconnection |

runtime and rpc know nothing of each other. An application that uses both joins them in its own code: its
`RpcHandler` hands peers' requests to its `Dispatcher`, and its `RpcCallback`s run their work through its
`GameRuntime`.

## Build

Each project builds on its own; install them in dependency order:

```bash
cd managame-common && mvn install
cd ../managame-network && mvn install
cd ../managame-runtime && mvn install
cd ../managame-rpc && mvn install
```

Tests open real loopback connections. Conventions for contributors and coding agents are in [AGENTS.md](AGENTS.md).
