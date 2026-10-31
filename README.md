English | [中文](README.zh-CN.md)

# managame

Game server components on Java 25 and Netty 4.2. Each is a project of its own, so an application takes only the
ones it needs:

| Project | Depends on | What it is |
| --- | --- | --- |
| [managame-common](managame-common/README.md) | nothing | `Metadata`, `MetadataKey` and the shared error codes |
| [managame-network](managame-network/README.md) | Netty | TCP / TLS / ws / wss servers, an HTTP/1.1 server and a reconnecting client, with any Netty handler |
| [managame-runtime](managame-runtime/README.md) | common, network | Every piece of business logic through one entry, with tasks of one domain and route key run one at a time: routing per domain, annotated handlers (`Dispatcher`, `EventBus`, `HttpRoutes`), interceptors and timers |
| [managame-rpc](managame-rpc/README.md) | common, network | Calls and notifies between server processes: `RpcNode`, `RpcPeer`, symmetric connections over TCP, restart detection, admission |
| [managame-data](managame-data/README.md) | common, Caffeine | Game data in memory, written behind it in batches wherever the application chooses: entities by id (`SingleRepository`), groups of entities (`GroupRepository`) and logs (`LogRepository`), through an `EntityMapper` and a `LogWriter` |
| [managame-data-mysql](managame-data-mysql/README.md) | data | `MysqlEntityMapper` and `MysqlLogWriter`: entities and logs in MySQL tables |
| [managame-data-mongo](managame-data-mongo/README.md) | data, MongoDB driver | `MongoEntityMapper`: entities in MongoDB collections |

[managame-benchmark](managame-benchmark/README.md) holds JMH benchmarks for every component, with a recorded
baseline each, so that a change that makes a component faster or slower shows at once.

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
cd ../managame-data && mvn install
cd ../managame-data-mysql && mvn install    # either or both, as the application needs
cd ../managame-data-mongo && mvn install
cd ../managame-benchmark && mvn package    # then: java -jar target/benchmarks.jar all
```

Tests open real loopback connections, and the data tests use an in-memory database. Conventions for contributors and coding agents are in [AGENTS.md](AGENTS.md).
